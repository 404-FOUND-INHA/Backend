package com.__found.smart_library.domain.observation.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * 동일 결과 재전송과 충돌 처리. 재전송은 관측·후보·서가·회차·검토 어느 것도 바꾸지 않아야 하므로
 * 매번 전체 저장 상태(snapshot, 회차 updated_at 포함)를 비교한다.
 */
@Import(VisionTestParserConfig.class)
class VisionObservationIdempotencyTest extends VisionReceiveTestSupport {

    @Test
    @DisplayName("동일한 결과를 다시 보내면 200 이고 응답은 같으며 저장된 어떤 데이터(검토 기록 포함)도 바뀌지 않는다")
    void resend_identicalResult_returns200AndChangesNothing() throws Exception {
        Fixture fx = fixture(3);
        Map<String, Object> request = standardScenario(fx).body();
        MvcResult first = send(request);
        assertThat(status(first)).isEqualTo(201);
        insertReview(fx.sessionId());
        Map<String, Object> before = snapshot(fx.sessionId());

        MvcResult second = send(request);

        assertThat(status(second)).isEqualTo(200);
        assertThat(json(second).get("data")).isEqualTo(json(first).get("data"));
        assertThat(snapshot(fx.sessionId())).isEqualTo(before);
        assertThat(((List<?>) before.get("reviews"))).hasSize(1);
    }

    @Test
    @DisplayName("반올림·절삭 대상 값(x 소수 셋째 자리, 나노초 촬영 시각)이 있어도 같은 요청의 재전송은 200 이다")
    void resend_valuesNeedingNormalization_returns200() throws Exception {
        Fixture fx = fixture(1);
        Map<String, Object> o = obs(1, "해석불가");
        o.put("xPositionCm", new BigDecimal("12.345"));
        o.put("detectedAt", "2026-11-24T10:00:00.123456789");
        Map<String, Object> request = body(fx.sessionId(), shelf(fx.shelfCodes().get(0), o));
        assertThat(status(send(request))).isEqualTo(201);
        Map<String, Object> before = snapshot(fx.sessionId());

        assertThat(status(send(request))).isEqualTo(200);
        assertThat(snapshot(fx.sessionId())).isEqualTo(before);
    }

    @Test
    @DisplayName("모든 서가가 0권인 결과도 최초 201, 동일 재전송 200 이고 관측은 저장되지 않는다")
    void resend_allShelvesEmpty_returns200() throws Exception {
        Fixture fx = fixture(2);
        MvcResult first = send(emptyBody(fx));
        assertThat(status(first)).isEqualTo(201);
        assertThat(json(first).get("data").get("observationCount").asInt()).isZero();
        assertThat(json(first).get("data").get("status").asString()).isEqualTo("REVIEW");
        Map<String, Object> before = snapshot(fx.sessionId());

        MvcResult second = send(emptyBody(fx));

        assertThat(status(second)).isEqualTo(200);
        assertThat(json(second).get("data")).isEqualTo(json(first).get("data"));
        assertThat(snapshot(fx.sessionId())).isEqualTo(before);
        assertThat(observationCount(fx.sessionId())).isZero();
    }

    @Test
    @DisplayName("0권 회차에 이후 관측이 있는 다른 결과를 보내면 409 이다")
    void resend_differentResultToEmptyResult_returns409() throws Exception {
        Fixture fx = fixture(1);
        assertThat(status(send(emptyBody(fx)))).isEqualTo(201);
        Map<String, Object> before = snapshot(fx.sessionId());

        MvcResult result = send(body(fx.sessionId(), shelf(fx.shelfCodes().get(0), obs(1, "해석불가"))));

        assertConflict(result);
        assertThat(snapshot(fx.sessionId())).isEqualTo(before);
    }

    @Test
    @DisplayName("COMPLETED 회차에 동일 결과를 다시 보내면 200 이고 현재 상태 COMPLETED 를 돌려주며 아무것도 바뀌지 않는다")
    void resend_toCompletedSession_returns200WithCompletedStatus() throws Exception {
        Fixture fx = fixture(3);
        Map<String, Object> request = standardScenario(fx).body();
        assertThat(status(send(request))).isEqualTo(201);
        insertReview(fx.sessionId());
        jdbc.update("update scan_session set status = 'COMPLETED', finalized_at = now() where scan_session_id = ?",
                fx.sessionId());
        Map<String, Object> before = snapshot(fx.sessionId());

        MvcResult result = send(request);

        assertThat(status(result)).isEqualTo(200);
        JsonNode data = json(result).get("data");
        assertThat(data.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(data.get("observationCount").asInt()).isEqualTo(5);
        assertThat(snapshot(fx.sessionId())).isEqualTo(before);
        assertThat(sessionStatus(fx.sessionId())).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("COMPLETED 회차에 다른 결과를 보내면 409 이고 아무것도 바뀌지 않는다")
    void resend_differentResultToCompletedSession_returns409() throws Exception {
        Fixture fx = fixture(1);
        assertThat(status(send(emptyBody(fx)))).isEqualTo(201);
        jdbc.update("update scan_session set status = 'COMPLETED', finalized_at = now() where scan_session_id = ?",
                fx.sessionId());
        Map<String, Object> before = snapshot(fx.sessionId());

        assertConflict(send(body(fx.sessionId(), shelf(fx.shelfCodes().get(0), obs(1, "해석불가")))));
        assertThat(snapshot(fx.sessionId())).isEqualTo(before);
    }

    @Test
    @DisplayName("최초 수신 뒤 서가의 사용 여부와 자료실이 바뀌어도 같은 요청의 재전송은 거부되지 않는다")
    void resend_afterShelfDeactivatedAndZoneChanged_returns200() throws Exception {
        Fixture fx = fixture(3);
        Map<String, Object> request = standardScenario(fx).body();
        assertThat(status(send(request))).isEqualTo(201);
        jdbc.update("update shelf set is_active = false, zone = ? where shelf_code like ?",
                prefix + "-moved", prefix + "%");
        Map<String, Object> before = snapshot(fx.sessionId());

        assertThat(status(send(request))).isEqualTo(200);
        assertThat(snapshot(fx.sessionId())).isEqualTo(before);
    }

    @Test
    @DisplayName("서가가 비활성화된 회차도 신규 수신이 거부되지 않는다 (대상 서가 기준은 회차 등록 정보)")
    void receive_deactivatedTargetShelf_isStillAccepted() throws Exception {
        Fixture fx = fixture(1);
        jdbc.update("update shelf set is_active = false where shelf_code = ?", fx.shelfCodes().get(0));

        assertThat(status(send(emptyBody(fx)))).isEqualTo(201);
    }

    @Test
    @DisplayName("이미 수신한 결과와 입력이 다른 재전송은 모두 409(SCAN_005)이고 저장된 데이터는 그대로다")
    void resend_differentInputs_return409AndKeepData() throws Exception {
        Fixture fx = fixture(3);
        Map<String, Object> original = standardScenario(fx).body();
        assertThat(status(send(original))).isEqualTo(201);
        Map<String, Object> before = snapshot(fx.sessionId());

        for (Map<String, Object> changed : variants(original)) {
            assertConflict(send(changed));
            assertThat(snapshot(fx.sessionId())).isEqualTo(before);
        }
    }

    @Test
    @DisplayName("재전송이라도 등록되지 않았거나 대상이 아닌 서가 코드는 400 이고 아무것도 바뀌지 않는다")
    void resend_unknownOrNonTargetShelf_returns400() throws Exception {
        Fixture fx = fixture(1);
        Fixture other = fixture(1);
        assertThat(status(send(emptyBody(fx)))).isEqualTo(201);
        Map<String, Object> before = snapshot(fx.sessionId());

        MvcResult unknown = send(body(fx.sessionId(), shelf(fx.shelfCodes().get(0)), shelf(prefix + "-nope")));
        MvcResult nonTarget = send(body(fx.sessionId(), shelf(fx.shelfCodes().get(0)),
                shelf(other.shelfCodes().get(0))));

        assertThat(status(unknown)).isEqualTo(400);
        assertThat(status(nonTarget)).isEqualTo(400);
        assertThat(snapshot(fx.sessionId())).isEqualTo(before);
    }

    // ---- 도우미 ----

    private void assertConflict(MvcResult result) throws Exception {
        assertThat(status(result)).isEqualTo(409);
        assertThat(json(result).get("code").asString()).isEqualTo("SCAN_005");
    }

    /** 원본과 한 가지씩만 다른 요청들. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> variants(Map<String, Object> original) throws Exception {
        java.util.ArrayList<Map<String, Object>> list = new java.util.ArrayList<>();
        // 라벨 변경
        Map<String, Object> m = copy(original);
        observationsOf(m, 0).get(0).put("detectedLabelRaw", "다른 라벨");
        list.add(m);
        // 제목 변경
        m = copy(original);
        observationsOf(m, 0).get(0).put("detectedTitle", "다른 제목");
        list.add(m);
        // x 좌표 변경
        m = copy(original);
        observationsOf(m, 0).get(1).put("xPositionCm", 99.99);
        list.add(m);
        // 촬영 시각 변경
        m = copy(original);
        observationsOf(m, 0).get(1).put("detectedAt", "2026-11-25T00:00:00");
        list.add(m);
        // 순서 변경
        m = copy(original);
        observationsOf(m, 1).get(0).put("detectedOrder", 7);
        list.add(m);
        // 관측 추가
        m = copy(original);
        observationsOf(m, 1).add(obs(2, "해석불가"));
        list.add(m);
        // 관측 삭제
        m = copy(original);
        observationsOf(m, 0).remove(3);
        list.add(m);
        // 0권이던 서가에 관측 추가
        m = copy(original);
        observationsOf(m, 2).add(obs(1, "해석불가"));
        list.add(m);
        // 대상 서가 누락
        m = copy(original);
        ((List<Object>) m.get("shelves")).remove(2);
        list.add(m);
        return list;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> copy(Map<String, Object> source) throws Exception {
        return objectMapper.readValue(objectMapper.writeValueAsString(source), Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> observationsOf(Map<String, Object> request, int shelfIndex) {
        Map<String, Object> shelf = ((List<Map<String, Object>>) request.get("shelves")).get(shelfIndex);
        return (List<Map<String, Object>>) shelf.get("observations");
    }
}
