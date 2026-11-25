package com.__found.smart_library.domain.evaluation.jev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withRawStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.web.client.RestClient;

class TypeSafeJevClientTest {

    private static final String URL = "https://jev.test/v1/systemone";
    private static final String OK_BODY = """
            {"model":"jev-1.14.0","answers":{
              "RARITY":{"type":"score","score":2.53,"confidence":0.78,
                        "probabilities":{"0":0.02,"1":0.08,"2":0.35,"3":0.45,"4":0.10},
                        "legend":{"0":"a","1":"b"}},
              "CONTENT_VALIDITY":{"type":"score","score":1.2,"confidence":0.5,
                        "probabilities":{"0":0.3,"1":0.4,"2":0.3}}},
             "usage":{"input_tokens":120,"output_tokens":12}}
            """;

    private MockRestServiceServer server;
    private TypeSafeJevClient client;
    private final List<Duration> sleeps = new ArrayList<>();

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://jev.test")
                .defaultHeader("Authorization", "Bearer secret-key-123");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TypeSafeJevClient(builder.build(), 3, Duration.ofSeconds(1), sleeps::add);
    }

    private JevRequest request() {
        return new JevRequest("jev-latest", Map.of("title", "예시 도서"), JevQuestions.build());
    }

    @Test
    @DisplayName("요청은 Bearer 키·모델·질문을 담아 POST /v1/systemone 으로 보내고, 응답의 필요한 값만 읽는다")
    void sendsRequestAndParsesResponse() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer secret-key-123"))
                .andExpect(jsonPath("$.model").value("jev-latest"))
                .andExpect(jsonPath("$.state.title").value("예시 도서"))
                .andExpect(jsonPath("$.questions.RARITY.type").value("score"))
                .andExpect(jsonPath("$.questions.RARITY.criteria.length()").value(5))
                .andExpect(jsonPath("$.questions.CONTENT_VALIDITY.criteria[4]").value("내용이 낡아 더는 유효하지 않음"))
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        JevResponse response = client.evaluate(request());

        assertThat(response.model()).isEqualTo("jev-1.14.0");
        JevResponse.Answer rarity = response.answers().get("RARITY");
        assertThat(rarity.type()).isEqualTo("score");
        assertThat(rarity.score()).isEqualByComparingTo("2.53");
        assertThat(rarity.confidence()).isEqualByComparingTo("0.78");
        assertThat(rarity.probabilities()).hasSize(5);
        assertThat(response.answers().get("CONTENT_VALIDITY").probabilities()).hasSize(3);
        assertThat(sleeps).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("429·529·5xx 는 간격을 2배씩 늘려 다시 호출하고, 성공하면 그 응답을 돌려준다")
    void retriesTransientStatuses() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(URL)).andRespond(withRawStatus(529));
        server.expect(requestTo(URL)).andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        JevResponse response = client.evaluate(request());

        assertThat(response.model()).isEqualTo("jev-1.14.0");
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
        server.verify();
    }

    @Test
    @DisplayName("재시도 횟수를 다 쓰면 마지막 오류로 실패하고, 메시지에 API Key 가 없다")
    void givesUpAfterMaxAttempts() {
        server.expect(ExpectedCount.times(3), requestTo(URL))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("secret-key-123 leaked?"));

        assertThatThrownBy(() -> client.evaluate(request()))
                .isInstanceOfSatisfying(JevCallException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(500);
                    assertThat(e.isRetryable()).isTrue();
                    assertThat(e.getMessage()).contains("500").doesNotContain("secret-key-123");
                });
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
        server.verify();
    }

    @Test
    @DisplayName("400·401·422 는 다시 보내도 같으므로 재시도하지 않는다")
    void doesNotRetryClientErrors() {
        for (HttpStatus status : List.of(HttpStatus.BAD_REQUEST, HttpStatus.UNAUTHORIZED,
                HttpStatus.UNPROCESSABLE_CONTENT)) {
            server.reset();
            sleeps.clear();
            server.expect(requestTo(URL)).andRespond(withStatus(status));

            assertThatThrownBy(() -> client.evaluate(request()))
                    .isInstanceOfSatisfying(JevCallException.class, e -> {
                        assertThat(e.getHttpStatus()).isEqualTo(status.value());
                        assertThat(e.isRetryable()).isFalse();
                    });
            assertThat(sleeps).isEmpty();
            server.verify();
        }
    }

    @Test
    @DisplayName("연결 실패·시간 초과도 재시도한다")
    void retriesIoFailures() {
        server.expect(requestTo(URL)).andRespond(withException(new IOException("timeout")));
        server.expect(requestTo(URL)).andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        assertThat(client.evaluate(request()).model()).isEqualTo("jev-1.14.0");
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
        server.verify();
    }

    @Test
    @DisplayName("연결 실패가 계속되면 응답 코드 없는 재시도 가능 오류로 실패한다")
    void ioFailureGivesUp() {
        server.expect(ExpectedCount.times(3), requestTo(URL)).andRespond(withException(new IOException("down")));

        assertThatThrownBy(() -> client.evaluate(request()))
                .isInstanceOfSatisfying(JevCallException.class, e -> {
                    assertThat(e.getHttpStatus()).isNull();
                    assertThat(e.isRetryable()).isTrue();
                });
        server.verify();
    }

    /** 상태와 헤더는 정상으로 주고 본문을 읽는 도중 연결이 끊기는 응답. */
    private static ResponseCreator bodyReadFails() {
        return request -> {
            MockClientHttpResponse response = new MockClientHttpResponse(new InputStream() {
                @Override
                public int read() throws IOException {
                    throw new IOException("connection reset");
                }
            }, HttpStatus.OK);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return response;
        };
    }

    @Test
    @DisplayName("본문을 읽다가 연결이 끊기면 재시도하고, 계속 끊기면 응답 코드 없는 재시도 가능 오류로 실패한다")
    void retriesWhenBodyReadFails() {
        server.expect(requestTo(URL)).andRespond(bodyReadFails());
        server.expect(requestTo(URL)).andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));
        assertThat(client.evaluate(request()).model()).isEqualTo("jev-1.14.0");
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
        server.verify();

        server.reset();
        sleeps.clear();
        server.expect(ExpectedCount.times(3), requestTo(URL)).andRespond(bodyReadFails());
        assertThatThrownBy(() -> client.evaluate(request()))
                .isInstanceOfSatisfying(JevCallException.class, e -> {
                    assertThat(e.getHttpStatus()).isNull();
                    assertThat(e.isRetryable()).isTrue();
                });
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
        server.verify();
    }

    @Test
    @DisplayName("잘못된 JSON(깨졌거나 중간에 끝남)은 연결 문제가 아니므로 재시도하지 않는다")
    void doesNotRetryMalformedJson() {
        for (String body : List.of("not json", "{\"model\":\"jev-1.14.0\",\"answers\":{")) {
            server.reset();
            server.expect(requestTo(URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> client.evaluate(request()))
                    .isInstanceOfSatisfying(JevCallException.class, e -> assertThat(e.isRetryable()).isFalse());
            server.verify();
        }
        assertThat(sleeps).isEmpty();
    }

    @Test
    @DisplayName("해석할 수 없는 응답이나 빈 응답은 재시도 없이 실패한다")
    void unreadableOrEmptyResponse() {
        server.expect(requestTo(URL)).andRespond(withSuccess("not json", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.evaluate(request()))
                .isInstanceOfSatisfying(JevCallException.class, e -> assertThat(e.isRetryable()).isFalse());

        server.reset();
        server.expect(requestTo(URL)).andRespond(withSuccess());
        assertThatThrownBy(() -> client.evaluate(request()))
                .isInstanceOfSatisfying(JevCallException.class, e -> assertThat(e.isRetryable()).isFalse());
        assertThat(sleeps).isEmpty();
    }

    @Test
    @DisplayName("대기 중 인터럽트되면 재시도하지 않고 중단 상태를 유지한다")
    void interruptedWhileWaiting() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://jev.test");
        MockRestServiceServer mock = MockRestServiceServer.bindTo(builder).build();
        TypeSafeJevClient interrupting = new TypeSafeJevClient(builder.build(), 3, Duration.ofSeconds(1), d -> {
            throw new InterruptedException();
        });
        mock.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        try {
            assertThatThrownBy(() -> interrupting.evaluate(request()))
                    .isInstanceOfSatisfying(JevCallException.class, e -> assertThat(e.isRetryable()).isFalse());
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();   // 다른 테스트에 영향을 주지 않게 상태를 지운다
        }
        mock.verify();
    }
}
