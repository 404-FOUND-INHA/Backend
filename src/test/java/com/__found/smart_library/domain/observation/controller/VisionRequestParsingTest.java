package com.__found.smart_library.domain.observation.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 요청 JSON 의 정수 필드 엄격성. 소수·지수·문자열·불리언 값을 정수로 바꿔 처리하지 않고(예: 42.9 → 42),
 * 회차 조회나 DB 변경 전에 400 으로 거부해야 한다.
 */
@Import(VisionTestParserConfig.class)
class VisionRequestParsingTest extends VisionReceiveTestSupport {

    @Test
    @DisplayName("scanSessionId 에 소수·지수·문자열·불리언이 오면 정수로 바꾸지 않고 400 이며 아무것도 변경되지 않는다")
    void receive_nonIntegerScanSessionId_returns400() throws Exception {
        Fixture fx = fixture(1);
        Long id = fx.sessionId();

        for (String value : new String[] {id + ".9", id + ".0", id + "e0", "\"" + id + "\"", "true"}) {
            MvcResult result = sendRaw(rawBody(value, "1", fx));

            assertRejectedWithoutChange(fx, result, value);
        }
    }

    @Test
    @DisplayName("detectedOrder 에 소수·지수·문자열·불리언이 오면 정수로 바꾸지 않고 400 이며 아무것도 변경되지 않는다")
    void receive_nonIntegerDetectedOrder_returns400() throws Exception {
        Fixture fx = fixture(1);

        for (String value : new String[] {"1.9", "1.0", "1e0", "\"1\"", "true"}) {
            MvcResult result = sendRaw(rawBody(String.valueOf(fx.sessionId()), value, fx));

            assertRejectedWithoutChange(fx, result, value);
        }
    }

    @Test
    @DisplayName("정수 범위를 벗어난 detectedOrder·scanSessionId 도 400 이다")
    void receive_integerOutOfRange_returns400() throws Exception {
        Fixture fx = fixture(1);

        assertRejectedWithoutChange(fx, sendRaw(rawBody(String.valueOf(fx.sessionId()), "3000000000", fx)),
                "detectedOrder=3000000000");
        assertRejectedWithoutChange(fx, sendRaw(rawBody("99999999999999999999", "1", fx)),
                "scanSessionId=99999999999999999999");
    }

    @Test
    @DisplayName("JSON 정수로 보낸 scanSessionId 와 detectedOrder 는 기존과 같이 정상 처리된다")
    void receive_integerValues_areProcessedNormally() throws Exception {
        Fixture fx = fixture(1);

        MvcResult result = sendRaw(rawBody(String.valueOf(fx.sessionId()), "1", fx));

        assertThat(status(result)).isEqualTo(201);
        assertThat(observations(fx.sessionId())).hasSize(1);
        assertThat(observations(fx.sessionId()).get(0).get("detected_order")).isEqualTo(1);
        assertThat(sessionStatus(fx.sessionId())).isEqualTo("REVIEW");
    }

    private String rawBody(String scanSessionId, String detectedOrder, Fixture fx) {
        return "{\"scanSessionId\":" + scanSessionId + ",\"shelves\":[{\"shelfCode\":\"" + fx.shelfCodes().get(0)
                + "\",\"observations\":[{\"detectedOrder\":" + detectedOrder
                + ",\"detectedLabelRaw\":\"해석불가\"}]}]}";
    }

    private void assertRejectedWithoutChange(Fixture fx, MvcResult result, String value) throws Exception {
        assertThat(status(result)).as("value=%s body=%s", value, result.getResponse().getContentAsString())
                .isEqualTo(400);
        assertThat(json(result).get("code").asString()).isEqualTo("COMMON_001");
        assertThat(sessionStatus(fx.sessionId())).isEqualTo("IN_PROGRESS");
        assertThat(observationCount(fx.sessionId())).isZero();
        assertThat(count("select count(*) from scan_session_shelf where scan_session_id = ? "
                + "and scan_status <> 'WAITING'", fx.sessionId())).isZero();
    }
}
