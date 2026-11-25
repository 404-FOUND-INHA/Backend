package com.__found.smart_library.domain.observation.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

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

    @Test
    void rawTextResolvesPartialCopyAndChecksReplayEvidence() throws Exception {
        Fixture fx = fixture(1);
        Long shelfId = fx.shelfIds().get(0);
        Long original = book("K", null, shelfId, "NORMAL");
        Long copy = book("K", "c.2", shelfId, "NORMAL");
        String text = cls("K") + " " + sym("K");
        var partial = new HashMap<String, Object>(Map.of("detectedOrder", 2, "ocrText", text, "partialCopyMark", ".2"));
        var request = body(fx.sessionId(), shelf(fx.shelfCodes().get(0),
                Map.of("detectedOrder", 1, "ocrText", text), partial, Map.of("detectedOrder", 3, "ocrText", "")));

        assertThat(status(send(request))).isEqualTo(201);
        var rows = observations(fx.sessionId());
        assertThat(rows.get(0).get("book_id")).isEqualTo(original);
        assertThat(rows.get(1).get("book_id")).isEqualTo(copy);
        assertThat(rows.get(2).get("book_id")).isNull();
        assertThat(status(send(request))).isEqualTo(200);
        partial.remove("partialCopyMark");
        assertThat(status(send(request))).isEqualTo(409);
        partial.put("partialCopyMark", ".2");
        partial.put("ocrText", text + " altered");
        assertThat(status(send(request))).isEqualTo(409);
        assertThat(observations(fx.sessionId())).isEqualTo(rows);
    }

    @Test
    void rawTextBreaksRemainingTiesByBookId() throws Exception {
        Fixture fx = fixture(1);
        Long first = book("T", "c.2", fx.shelfIds().get(0), "NORMAL");
        book("T", "c.3", fx.shelfIds().get(0), "NORMAL");
        var request = body(fx.sessionId(), shelf(fx.shelfCodes().get(0),
                Map.of("detectedOrder", 1, "ocrText", cls("T") + sym("T"))));
        assertThat(status(send(request))).isEqualTo(201);
        assertThat(observations(fx.sessionId()).get(0).get("book_id")).isEqualTo(first);
    }
}
