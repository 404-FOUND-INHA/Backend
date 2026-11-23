package com.__found.smart_library.domain.observation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;

/**
 * 같은 회차에 대한 동시 수신. 회차 행 잠금으로 직렬화되어 정확히 한 요청만 저장하고, 나머지는 먼저 커밋된 결과와 비교해
 * 동일하면 200, 다르면 409 가 되어야 한다. 실제로 커밋하므로 정리 후 잔여 데이터가 없는지 확인한다.
 */
@Import(VisionTestParserConfig.class)
class VisionObservationConcurrencyTest extends VisionReceiveTestSupport {

    private static final int THREADS = 5;   // Hikari 풀(10)보다 작게 유지

    @Test
    @DisplayName("같은 결과를 5개 동시에 보내면 201 은 정확히 1개, 나머지는 200 이고 한 세트만 저장된다")
    void receive_fiveConcurrentIdenticalRequests_exactlyOneCreated() throws Exception {
        Fixture fx = fixture(3);
        Map<String, Object> request = standardScenario(fx).body();

        List<Integer> statuses = runConcurrently(List.of(request, request, request, request, request));

        assertThat(statuses.stream().filter(s -> s == 201)).hasSize(1);
        assertThat(statuses.stream().filter(s -> s == 200)).hasSize(THREADS - 1);
        assertThat(observationCount(fx.sessionId())).isEqualTo(5);
        assertThat(count("select count(*) from observation_match_candidate c join book_observation o "
                + "on o.observation_id = c.observation_id where o.scan_session_id = ?", fx.sessionId())).isEqualTo(1);
        assertThat(sessionStatus(fx.sessionId())).isEqualTo("REVIEW");
    }

    @Test
    @DisplayName("모든 서가가 0권인 같은 결과를 5개 동시에 보내도 201 은 1개, 나머지는 200 이다")
    void receive_fiveConcurrentIdenticalEmptyRequests_exactlyOneCreated() throws Exception {
        Fixture fx = fixture(2);
        Map<String, Object> request = emptyBody(fx);

        List<Integer> statuses = runConcurrently(List.of(request, request, request, request, request));

        assertThat(statuses.stream().filter(s -> s == 201)).hasSize(1);
        assertThat(statuses.stream().filter(s -> s == 200)).hasSize(THREADS - 1);
        assertThat(observationCount(fx.sessionId())).isZero();
        assertThat(sessionStatus(fx.sessionId())).isEqualTo("REVIEW");
    }

    @Test
    @DisplayName("서로 다른 결과를 동시에 보내면 하나만 201 로 저장되고 나머지는 409 이며 저장된 데이터는 하나의 요청 것이다")
    void receive_concurrentDifferentRequests_oneCreatedOthersConflict() throws Exception {
        Fixture fx = fixture(1);
        Map<String, Object> one = body(fx.sessionId(), shelf(fx.shelfCodes().get(0), obs(1, "해석불가")));
        Map<String, Object> two = body(fx.sessionId(),
                shelf(fx.shelfCodes().get(0), obs(1, "해석불가"), obs(2, "해석불가")));

        List<Integer> statuses = runConcurrently(List.of(one, two, one, two));

        // 먼저 저장된 요청이 201, 그와 같은 요청 1개는 200, 다른 요청 2개는 409
        assertThat(statuses.stream().filter(s -> s == 201)).hasSize(1);
        assertThat(statuses.stream().filter(s -> s == 200)).hasSize(1);
        assertThat(statuses.stream().filter(s -> s == 409)).hasSize(2);
        assertThat(observationCount(fx.sessionId())).isIn(1L, 2L);
        assertThat(sessionStatus(fx.sessionId())).isEqualTo("REVIEW");
    }

    /** 모든 스레드가 준비된 뒤 동시에 출발시켜 각 요청의 HTTP 상태를 모은다. */
    private List<Integer> runConcurrently(List<Map<String, Object>> requests) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(requests.size());
        CountDownLatch ready = new CountDownLatch(requests.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (Map<String, Object> request : requests) {
                String json = objectMapper.writeValueAsString(request);
                Callable<Integer> task = () -> {
                    ready.countDown();
                    start.await();
                    return mockMvc.perform(post(URL).header(HEADER, API_KEY)
                            .contentType(MediaType.APPLICATION_JSON).content(json))
                            .andReturn().getResponse().getStatus();
                };
                futures.add(pool.submit(task));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) {
                statuses.add(f.get(60, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            requestThreadsStillRunning = !shutdownAndAwait(pool);
        }
    }

    private boolean shutdownAndAwait(ExecutorService pool) {
        pool.shutdownNow();
        try {
            return pool.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
