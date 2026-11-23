package com.__found.smart_library.domain.observation.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 운영과 같은 구성(CallNumberParser 구현 빈 없음). 신규 수신은 성공한 것처럼 처리하지 않고 503 으로 실패해야 하며,
 * 이미 수신한 결과의 동일 재전송은 파서가 필요 없으므로 영향을 받지 않는다. 이 클래스는 테스트 파서를 등록하지 않는다.
 */
class VisionObservationParserAbsentApiTest extends VisionReceiveTestSupport {

    @Test
    @DisplayName("파서 구현이 없으면 신규 수신은 503(SCAN_004)이고 저장·상태 변경이 없다")
    void receive_parserAbsent_returns503AndSavesNothing() throws Exception {
        Fixture fx = fixture(3);
        Scenario scenario = standardScenario(fx);
        Map<String, Object> before = snapshot(fx.sessionId());

        MvcResult result = send(scenario.body());

        assertThat(status(result)).isEqualTo(503);
        assertThat(json(result).get("code").asString()).isEqualTo("SCAN_004");
        assertThat(snapshot(fx.sessionId())).isEqualTo(before);
        assertThat(sessionStatus(fx.sessionId())).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("관측이 0권인 요청도 파서 구현이 없으면 503 이다 (성공한 것처럼 REVIEW 로 넘어가지 않는다)")
    void receive_parserAbsentWithEmptyShelves_returns503() throws Exception {
        Fixture fx = fixture(2);

        MvcResult result = send(emptyBody(fx));

        assertThat(status(result)).isEqualTo(503);
        assertThat(sessionStatus(fx.sessionId())).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("이미 수신한 결과의 동일 재전송은 파서 구현이 없어도 200 이다")
    void resend_parserAbsent_returns200() throws Exception {
        Fixture fx = fixture(2);
        // 수신이 끝난 상태(REVIEW, 모든 서가 수신 완료, 0권)를 직접 만든다.
        jdbc.update("update scan_session set status = 'REVIEW' where scan_session_id = ?", fx.sessionId());
        jdbc.update("update scan_session_shelf set scan_status = 'RESULT_RECEIVED', detected_book_count = 0 "
                + "where scan_session_id = ?", fx.sessionId());
        Map<String, Object> before = snapshot(fx.sessionId());

        MvcResult result = send(emptyBody(fx));

        assertThat(status(result)).isEqualTo(200);
        assertThat(json(result).get("data").get("status").asString()).isEqualTo("REVIEW");
        assertThat(snapshot(fx.sessionId())).isEqualTo(before);
    }
}
