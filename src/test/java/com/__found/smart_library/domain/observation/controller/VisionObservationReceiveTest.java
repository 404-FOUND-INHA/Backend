package com.__found.smart_library.domain.observation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Vision 결과 수신 API 의 신규 수신 테스트(실제 커밋). 파싱은 테스트 전용 파서를 쓰고, 데이터는 테스트가 직접 만든다.
 */
@Import(VisionTestParserConfig.class)
class VisionObservationReceiveTest extends VisionReceiveTestSupport {

    // ---- 정상 수신 ----

    @Test
    @DisplayName("정상 수신 시 201 이고 응답에 회차 상태(REVIEW)와 상태별 건수가 담긴다")
    void receive_validResult_returns201WithCounts() throws Exception {
        Fixture fx = fixture(3);
        Scenario scenario = standardScenario(fx);

        MvcResult result = send(scenario.body());

        assertThat(status(result)).isEqualTo(201);
        JsonNode data = json(result).get("data");
        assertThat(data.get("scanSessionId").asLong()).isEqualTo(fx.sessionId());
        assertThat(data.get("status").asString()).isEqualTo("REVIEW");
        assertThat(data.get("shelfCount").asInt()).isEqualTo(3);
        assertThat(data.get("observationCount").asInt()).isEqualTo(5);
        assertThat(data.get("normalCount").asInt()).isEqualTo(2);
        assertThat(data.get("misplacedCount").asInt()).isEqualTo(1);
        assertThat(data.get("reviewRequiredCount").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("모든 관측이 서가·순서·위치·원문과 함께 분석 결과로 저장되고, 도서를 식별하지 못한 관측도 저장된다")
    void receive_validResult_savesEveryObservationWithAnalysis() throws Exception {
        Fixture fx = fixture(3);
        Scenario scenario = standardScenario(fx);
        LocalDateTime before = LocalDateTime.now().minusSeconds(5);

        assertThat(status(send(scenario.body()))).isEqualTo(201);

        List<Map<String, Object>> rows = observations(fx.sessionId());
        assertThat(rows).hasSize(5);
        Long shelfA = fx.shelfIds().get(0);
        Long shelfB = fx.shelfIds().get(1);

        Map<String, Object> normal = rows.get(0);
        assertThat(normal.get("detected_shelf_id")).isEqualTo(shelfA);
        assertThat(normal.get("detected_order")).isEqualTo(1);
        assertThat(normal.get("detected_label_raw")).isEqualTo(label("N", "c.1"));
        assertThat(normal.get("detected_title")).isEqualTo("제목 1");
        assertThat((BigDecimal) normal.get("x_position_cm")).isEqualByComparingTo("10.50");
        assertThat(time(normal, "detected_at")).isEqualTo(LocalDateTime.of(2026, 11, 24, 10, 0, 1));
        assertThat(normal.get("detected_classification_no")).isEqualTo(cls("N"));
        assertThat(normal.get("detected_book_symbol")).isEqualTo(sym("N"));
        assertThat(normal.get("detected_copy_number")).isEqualTo("c.1");
        assertThat(normal.get("book_id")).isEqualTo(scenario.normalBook());
        assertThat((BigDecimal) normal.get("match_confidence")).isEqualByComparingTo("1.0000");
        assertThat(normal.get("analysis_status")).isEqualTo("NORMAL");
        assertThat(normal.get("status_reason")).isNull();
        assertThat(time(normal, "received_at")).isAfterOrEqualTo(before).isBeforeOrEqualTo(
                LocalDateTime.now().plusSeconds(5));

        Map<String, Object> misplaced = rows.get(1);
        assertThat(misplaced.get("book_id")).isEqualTo(scenario.misplacedBook());
        assertThat(misplaced.get("analysis_status")).isEqualTo("MISPLACED");
        assertThat(misplaced.get("status_reason")).isEqualTo("WRONG_SHELF");

        Map<String, Object> noMatch = rows.get(2);
        assertThat(noMatch.get("detected_shelf_id")).isEqualTo(shelfA);
        assertThat(noMatch.get("detected_order")).isEqualTo(3);
        assertThat(noMatch.get("book_id")).isNull();
        assertThat(noMatch.get("match_confidence")).isNull();
        assertThat(noMatch.get("analysis_status")).isEqualTo("REVIEW_REQUIRED");
        assertThat(noMatch.get("status_reason")).isEqualTo("NO_MATCH");
        assertThat(noMatch.get("detected_copy_number")).isEqualTo("c.2");
        assertThat((BigDecimal) noMatch.get("x_position_cm")).isEqualByComparingTo("31.50");

        Map<String, Object> ocrFailed = rows.get(3);
        assertThat(ocrFailed.get("detected_label_raw")).isEqualTo("해석불가");
        assertThat(ocrFailed.get("book_id")).isNull();
        assertThat(ocrFailed.get("detected_classification_no")).isNull();
        assertThat(ocrFailed.get("analysis_status")).isEqualTo("REVIEW_REQUIRED");
        assertThat(ocrFailed.get("status_reason")).isEqualTo("OCR_FAILED");

        Map<String, Object> shelfBRow = rows.get(4);
        assertThat(shelfBRow.get("detected_shelf_id")).isEqualTo(shelfB);
        assertThat(shelfBRow.get("book_id")).isEqualTo(scenario.normalBook2());
        assertThat(shelfBRow.get("analysis_status")).isEqualTo("NORMAL");
    }

    @Test
    @DisplayName("매칭 후보는 후보가 있는 관측에만 순위·도서·선정 근거와 함께 저장된다")
    void receive_validResult_savesCandidatesOnlyForUnresolvedObservations() throws Exception {
        Fixture fx = fixture(3);
        Scenario scenario = standardScenario(fx);

        assertThat(status(send(scenario.body()))).isEqualTo(201);

        List<Map<String, Object>> candidates = jdbc.queryForList("select c.* from observation_match_candidate c "
                + "join book_observation o on o.observation_id = c.observation_id "
                + "where o.scan_session_id = ?", fx.sessionId());
        assertThat(candidates).hasSize(1);
        Map<String, Object> candidate = candidates.get(0);
        assertThat(candidate.get("candidate_rank")).isEqualTo(1);
        assertThat(candidate.get("book_id")).isEqualTo(scenario.copyMismatchBook());
        assertThat(candidate.get("match_reason")).isEqualTo("COPY_MISMATCH");
        assertThat(candidate.get("observation_id"))
                .isEqualTo(observations(fx.sessionId()).get(2).get("observation_id"));
    }

    @Test
    @DisplayName("서가별 건수·촬영 시각이 기록되고 결과가 없는 서가도 수신 완료·0권으로 기록되며 회차는 REVIEW 가 된다")
    void receive_validResult_updatesShelvesAndSession() throws Exception {
        Fixture fx = fixture(3);
        assertThat(status(send(standardScenario(fx).body()))).isEqualTo(201);

        List<Map<String, Object>> shelves = jdbc.queryForList("select * from scan_session_shelf "
                + "where scan_session_id = ? order by shelf_id", fx.sessionId());
        assertThat(shelves).hasSize(3).allSatisfy(s -> assertThat(s.get("scan_status")).isEqualTo("RESULT_RECEIVED"));
        assertThat(shelves.get(0).get("detected_book_count")).isEqualTo(4);
        assertThat(time(shelves.get(0), "scanned_at")).isEqualTo(LocalDateTime.of(2026, 11, 24, 10, 0, 4));
        assertThat(shelves.get(1).get("detected_book_count")).isEqualTo(1);
        assertThat(time(shelves.get(1), "scanned_at")).isEqualTo(LocalDateTime.of(2026, 11, 24, 10, 0, 1));
        assertThat(shelves.get(2).get("detected_book_count")).isEqualTo(0);
        assertThat(shelves.get(2).get("scanned_at")).isNull();
        assertThat(sessionStatus(fx.sessionId())).isEqualTo("REVIEW");
    }

    @Test
    @DisplayName("수신 후 A1 회차 상세 조회는 status=REVIEW, processingStage=ANALYSIS_DONE 을 돌려주고 자료실은 여전히 진행 중이다")
    void receive_thenA1SessionDetail_showsReviewAndAnalysisDone() throws Exception {
        Fixture fx = fixture(3);
        assertThat(status(send(standardScenario(fx).body()))).isEqualTo(201);

        MvcResult detail = mockMvc.perform(get("/api/scan-sessions/" + fx.sessionId()).with(jwt())).andReturn();
        MvcResult rooms = mockMvc.perform(get("/api/scan-sessions/rooms").with(jwt())).andReturn();

        assertThat(status(detail)).isEqualTo(200);
        JsonNode data = json(detail).get("data");
        assertThat(data.get("status").asString()).isEqualTo("REVIEW");
        assertThat(data.get("processingStage").asString()).isEqualTo("ANALYSIS_DONE");
        JsonNode room = null;
        for (JsonNode r : json(rooms).get("data")) {
            if (fx.zone().equals(r.get("zone").asString())) {
                room = r;
            }
        }
        assertThat(room).isNotNull();
        assertThat(room.get("hasOpenSession").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("이미 REVIEW 인 회차와 같은 자료실에 새 회차를 만들 수 없다 (A1 중복 방지 유지)")
    void receive_thenCreateSessionInSameZone_returns409() throws Exception {
        Fixture fx = fixture(3);
        assertThat(status(send(standardScenario(fx).body()))).isEqualTo(201);

        MvcResult create = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/scan-sessions")
                .with(jwt().jwt(j -> j.subject(String.valueOf(userId)))
                        .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "ROLE_LIBRARIAN")))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"sessionName\":\"중복\",\"zone\":\"" + fx.zone() + "\"}")).andReturn();

        assertThat(status(create)).isEqualTo(409);
        assertThat(json(create).get("code").asString()).isEqualTo("SCAN_002");
    }

    // ---- 회차 전체 1회 분석 (Codex Medium) ----

    @Test
    @DisplayName("같은 도서가 서로 다른 서가에서 관측되면 두 관측 모두 DUPLICATE_OBSERVATION 이고 도서는 유지된다")
    void receive_sameBookOnDifferentShelves_marksBothAsDuplicate() throws Exception {
        Fixture fx = fixture(2);
        Long book = book("D", "c.1", fx.shelfIds().get(0), "NORMAL");

        MvcResult result = send(body(fx.sessionId(),
                shelf(fx.shelfCodes().get(0), obs(1, label("D", "c.1"))),
                shelf(fx.shelfCodes().get(1), obs(1, label("D", "c.1")))));

        assertThat(status(result)).isEqualTo(201);
        List<Map<String, Object>> rows = observations(fx.sessionId());
        assertThat(rows).hasSize(2).allSatisfy(r -> {
            assertThat(r.get("analysis_status")).isEqualTo("REVIEW_REQUIRED");
            assertThat(r.get("status_reason")).isEqualTo("DUPLICATE_OBSERVATION");
            assertThat(r.get("book_id")).isEqualTo(book);
        });
        assertThat(json(result).get("data").get("reviewRequiredCount").asInt()).isEqualTo(2);
    }

    // ---- 후보 저장 ----

    @Test
    @DisplayName("도서를 식별하지 못한 관측은 book_id 가 비고 후보는 최대 3건까지 순위대로 저장된다")
    void receive_unresolvedObservation_savesAtMostThreeRankedCandidates() throws Exception {
        Fixture fx = fixture(1);
        List<Long> sameClass = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            sameClass.add(bookRaw(cls("K"), sym("K" + i), "c.1", null));
        }

        MvcResult result = send(body(fx.sessionId(),
                shelf(fx.shelfCodes().get(0), obs(1, cls("K") + "|" + sym("none") + "|c.1"))));

        assertThat(status(result)).isEqualTo(201);
        Map<String, Object> row = observations(fx.sessionId()).get(0);
        assertThat(row.get("book_id")).isNull();
        assertThat(row.get("status_reason")).isEqualTo("NO_MATCH");
        List<Map<String, Object>> candidates = jdbc.queryForList("select * from observation_match_candidate "
                + "where observation_id = ? order by candidate_rank", row.get("observation_id"));
        assertThat(candidates).extracting(c -> c.get("candidate_rank")).containsExactly(1, 2, 3);
        assertThat(candidates).extracting(c -> c.get("book_id")).containsExactly(
                sameClass.get(0), sameClass.get(1), sameClass.get(2));
        assertThat(candidates).extracting(c -> c.get("match_reason")).containsOnly("CLASSIFICATION_ONLY");
    }

    @Test
    @DisplayName("완전 일치 도서가 여러 권이면 AMBIGUOUS_MATCH 로 저장되고 그 도서들이 EXACT_MATCH 후보가 된다")
    void receive_ambiguousObservation_savesExactMatchCandidates() throws Exception {
        Fixture fx = fixture(1);
        Long first = book("X", "c.1", fx.shelfIds().get(0), "NORMAL");
        Long second = book("X", "c.1", fx.shelfIds().get(0), "NORMAL");   // 같은 청구기호·복본기호 중복 등록

        MvcResult result = send(body(fx.sessionId(),
                shelf(fx.shelfCodes().get(0), obs(1, label("X", "c.1")))));

        assertThat(status(result)).isEqualTo(201);
        Map<String, Object> row = observations(fx.sessionId()).get(0);
        assertThat(row.get("book_id")).isNull();
        assertThat(row.get("status_reason")).isEqualTo("AMBIGUOUS_MATCH");
        List<Map<String, Object>> candidates = jdbc.queryForList("select * from observation_match_candidate "
                + "where observation_id = ? order by candidate_rank", row.get("observation_id"));
        assertThat(candidates).extracting(c -> c.get("book_id")).containsExactly(first, second);
        assertThat(candidates).extracting(c -> c.get("match_reason")).containsOnly("EXACT_MATCH");
    }

    // ---- 값 정규화 ----

    @Test
    @DisplayName("x 좌표는 소수 둘째 자리로 반올림하고 촬영 시각은 마이크로초로 절삭해 저장한다")
    void receive_xAndDetectedAt_areNormalizedBeforeSaving() throws Exception {
        Fixture fx = fixture(1);
        Map<String, Object> o = obs(1, "해석불가");
        o.put("xPositionCm", new BigDecimal("12.345"));
        o.put("detectedAt", "2026-11-24T10:00:00.123456789");

        assertThat(status(send(body(fx.sessionId(), shelf(fx.shelfCodes().get(0), o))))).isEqualTo(201);

        Map<String, Object> row = observations(fx.sessionId()).get(0);
        assertThat((BigDecimal) row.get("x_position_cm")).isEqualByComparingTo("12.35");
        assertThat(time(row, "detected_at")).isEqualTo(LocalDateTime.of(2026, 11, 24, 10, 0, 0, 123_456_000));
    }

    @Test
    @DisplayName("라벨·제목·x 좌표·촬영 시각은 없어도 저장되며 라벨이 없으면 OCR_FAILED 이다")
    void receive_optionalFieldsAbsent_savedAsOcrFailed() throws Exception {
        Fixture fx = fixture(1);
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("detectedOrder", 1);

        assertThat(status(send(body(fx.sessionId(), shelf(fx.shelfCodes().get(0), o))))).isEqualTo(201);

        Map<String, Object> row = observations(fx.sessionId()).get(0);
        assertThat(row.get("detected_label_raw")).isNull();
        assertThat(row.get("detected_title")).isNull();
        assertThat(row.get("x_position_cm")).isNull();
        assertThat(row.get("detected_at")).isNull();
        assertThat(row.get("status_reason")).isEqualTo("OCR_FAILED");
    }

    // ---- 입력 검증(400) ----

    @Test
    @DisplayName("필수값 누락·범위·길이 위반은 400 이고 아무것도 저장되지 않는다")
    void receive_invalidFields_return400AndSaveNothing() throws Exception {
        Fixture fx = fixture(1);
        String code = fx.shelfCodes().get(0);
        Long id = fx.sessionId();
        Map<String, Object> noOrder = new LinkedHashMap<>(obs(1, "라벨"));
        noOrder.remove("detectedOrder");
        Map<String, Object> noObservations = new LinkedHashMap<>();
        noObservations.put("shelfCode", code);
        Map<String, Object> noSession = new LinkedHashMap<>(body(id, shelf(code)));
        noSession.remove("scanSessionId");
        Map<String, Object> longLabel = obs(1, "라".repeat(201));
        Map<String, Object> longTitle = obs(1, "라벨");
        longTitle.put("detectedTitle", "제".repeat(256));

        List<Object> bodies = List.of(
                noSession,
                body(id),                                               // shelves 비어 있음
                body(id, shelf("  ")),                                  // shelfCode 공백
                body(id, noObservations),                               // observations 없음
                body(id, shelf(code, obs(0, "라벨"))),                  // detectedOrder 0
                body(id, shelf(code, noOrder)),                         // detectedOrder 없음
                body(id, shelf(code, longLabel)),                       // 라벨 200자 초과
                body(id, shelf(code, longTitle)),                       // 제목 255자 초과
                body(id, shelf("c".repeat(51))));                       // shelfCode 50자 초과

        for (Object bad : bodies) {
            assertRejected(fx, send(bad));
        }
    }

    @Test
    @DisplayName("서가 코드 중복, 서가 내 detectedOrder 중복, x 좌표 범위 초과는 400 이다")
    void receive_duplicatesAndOutOfRange_return400() throws Exception {
        Fixture fx = fixture(2);
        String a = fx.shelfCodes().get(0);
        String b = fx.shelfCodes().get(1);
        Long id = fx.sessionId();
        Map<String, Object> tooFar = obs(1, "라벨");
        tooFar.put("xPositionCm", new BigDecimal("1234567.0"));
        Map<String, Object> roundsUp = obs(1, "라벨");
        roundsUp.put("xPositionCm", new BigDecimal("999999.995"));   // 반올림하면 1000000.00

        assertRejected(fx, send(body(id, shelf(a), shelf(a), shelf(b))));
        assertRejected(fx, send(body(id, shelf(a, obs(1, "라벨"), obs(1, "라벨2")), shelf(b))));
        assertRejected(fx, send(body(id, shelf(a, tooFar), shelf(b))));
        assertRejected(fx, send(body(id, shelf(a, roundsUp), shelf(b))));
    }

    @Test
    @DisplayName("등록되지 않은 서가 코드와 이 회차의 대상이 아닌 서가는 400 이고 아무것도 저장되지 않는다")
    void receive_unknownOrNonTargetShelf_return400() throws Exception {
        Fixture fx = fixture(1);
        Fixture other = fixture(1);   // 존재하지만 다른 회차의 서가
        Long id = fx.sessionId();

        MvcResult unknown = send(body(id, shelf(fx.shelfCodes().get(0)), shelf(prefix + "-nope")));
        MvcResult nonTarget = send(body(id, shelf(fx.shelfCodes().get(0)), shelf(other.shelfCodes().get(0))));

        assertRejected(fx, unknown);
        assertThat(json(unknown).get("message").asString()).contains(prefix + "-nope");
        assertRejected(fx, nonTarget);
        assertThat(json(nonTarget).get("message").asString()).contains(other.shelfCodes().get(0));
    }

    @Test
    @DisplayName("회차의 대상 서가가 하나라도 빠지면 400 이고 누락된 서가 코드를 알려 준다")
    void receive_missingTargetShelf_return400WithMissingCode() throws Exception {
        Fixture fx = fixture(3);

        MvcResult result = send(body(fx.sessionId(),
                shelf(fx.shelfCodes().get(0)), shelf(fx.shelfCodes().get(2))));

        assertRejected(fx, result);
        assertThat(json(result).get("message").asString()).contains(fx.shelfCodes().get(1));
    }

    @Test
    @DisplayName("관측이 5,000건을 넘으면 400 이다")
    void receive_overObservationLimit_return400() throws Exception {
        Fixture fx = fixture(1);
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 1; i <= 5001; i++) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("detectedOrder", i);
            many.add(o);
        }
        Map<String, Object> shelf = new LinkedHashMap<>();
        shelf.put("shelfCode", fx.shelfCodes().get(0));
        shelf.put("observations", many);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("scanSessionId", fx.sessionId());
        request.put("shelves", List.of(shelf));

        MvcResult result = send(request);

        assertRejected(fx, result);
        assertThat(json(result).get("message").asString()).contains("5000");
    }

    @Test
    @DisplayName("JSON 형식이 잘못되었거나 날짜를 해석할 수 없으면 400 이다")
    void receive_malformedJson_return400() throws Exception {
        Fixture fx = fixture(1);

        assertRejected(fx, sendRaw("{bad json"));
        assertRejected(fx, sendRaw("{\"scanSessionId\":" + fx.sessionId() + ",\"shelves\":[{\"shelfCode\":\""
                + fx.shelfCodes().get(0) + "\",\"observations\":[{\"detectedOrder\":1,"
                + "\"detectedAt\":\"not-a-date\"}]}]}"));
    }

    // ---- 회차 상태 ----

    @Test
    @DisplayName("없는 회차는 404 이다")
    void receive_unknownSession_returns404() throws Exception {
        Fixture fx = fixture(1);

        MvcResult result = send(body(Long.MAX_VALUE, shelf(fx.shelfCodes().get(0))));

        assertThat(status(result)).isEqualTo(404);
        assertThat(json(result).get("code").asString()).isEqualTo("SCAN_001");
    }

    @Test
    @DisplayName("수신한 적 없는 REVIEW·COMPLETED 회차는 409(SCAN_006)이고 아무것도 바뀌지 않는다")
    void receive_reviewOrCompletedWithoutReceivedResult_returns409() throws Exception {
        for (String state : new String[] {"REVIEW", "COMPLETED"}) {
            Fixture fx = fixture(1);
            jdbc.update("update scan_session set status = ? where scan_session_id = ?", state, fx.sessionId());
            Map<String, Object> before = snapshot(fx.sessionId());

            MvcResult result = send(emptyBody(fx));

            assertThat(status(result)).as(state).isEqualTo(409);
            assertThat(json(result).get("code").asString()).isEqualTo("SCAN_006");
            assertThat(snapshot(fx.sessionId())).isEqualTo(before);
        }
    }

    @Test
    @DisplayName("IN_PROGRESS 인데 기존 관측이 남아 있으면 409(SCAN_006)이고 덮어쓰거나 전이하지 않는다")
    void receive_inProgressWithLeftoverObservation_returns409() throws Exception {
        Fixture fx = fixture(1);
        jdbc.update("insert into book_observation (scan_session_id, detected_shelf_id, detected_order, "
                + "analysis_status, received_at) values (?, ?, 1, 'NORMAL', now())",
                fx.sessionId(), fx.shelfIds().get(0));
        Map<String, Object> before = snapshot(fx.sessionId());

        MvcResult result = send(emptyBody(fx));

        assertThat(status(result)).isEqualTo(409);
        assertThat(json(result).get("code").asString()).isEqualTo("SCAN_006");
        assertThat(snapshot(fx.sessionId())).isEqualTo(before);
        assertThat(sessionStatus(fx.sessionId())).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("IN_PROGRESS 인데 서가가 이미 수신 완료로 남아 있으면 409(SCAN_006)이고 아무것도 바뀌지 않는다")
    void receive_inProgressWithReceivedShelf_returns409() throws Exception {
        Fixture fx = fixture(2);
        jdbc.update("update scan_session_shelf set scan_status = 'RESULT_RECEIVED' where scan_session_id = ? "
                + "and shelf_id = ?", fx.sessionId(), fx.shelfIds().get(0));
        Map<String, Object> before = snapshot(fx.sessionId());

        MvcResult result = send(emptyBody(fx));

        assertThat(status(result)).isEqualTo(409);
        assertThat(json(result).get("code").asString()).isEqualTo("SCAN_006");
        assertThat(snapshot(fx.sessionId())).isEqualTo(before);
    }

    // ---- 파서 ----

    @Test
    @DisplayName("파서가 내부 오류를 내면 500 이고 저장·상태 변경 없이 롤백된다 (OCR_FAILED 로 숨기지 않는다)")
    void receive_parserThrows_returns500AndLeavesStateUntouched() throws Exception {
        Fixture fx = fixture(1);
        book("N", "c.1", fx.shelfIds().get(0), "NORMAL");
        Map<String, Object> before = snapshot(fx.sessionId());

        MvcResult result = send(body(fx.sessionId(),
                shelf(fx.shelfCodes().get(0), obs(1, label("N", "c.1")), obs(2, "BOOM"))));

        assertThat(status(result)).isEqualTo(500);
        assertThat(snapshot(fx.sessionId())).isEqualTo(before);
        assertThat(sessionStatus(fx.sessionId())).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("파서 결과가 저장 컬럼 길이를 넘으면 자르거나 OCR_FAILED 로 숨기지 않고 500 이며 앞선 관측도 저장되지 않는다")
    void receive_parserResultOverColumnLength_returns500AndSavesNothing() throws Exception {
        for (String over : new String[] {"OVER_CLASS", "OVER_SYMBOL", "OVER_COPY"}) {
            Fixture fx = fixture(2);
            book("N", "c.1", fx.shelfIds().get(0), "NORMAL");
            Map<String, Object> before = snapshot(fx.sessionId());

            MvcResult result = send(body(fx.sessionId(),
                    shelf(fx.shelfCodes().get(0), obs(1, label("N", "c.1")), obs(2, over)),
                    shelf(fx.shelfCodes().get(1), obs(1, label("N", "c.1")))));

            assertThat(status(result)).as(over).isEqualTo(500);
            assertThat(snapshot(fx.sessionId())).as(over).isEqualTo(before);
            assertThat(observationCount(fx.sessionId())).isZero();
            assertThat(sessionStatus(fx.sessionId())).isEqualTo("IN_PROGRESS");
        }
    }

    @Test
    @DisplayName("파서 결과가 저장 컬럼 길이와 정확히 같으면(분류기호 50·도서기호 50·복본기호 30) 그대로 저장된다")
    void receive_parserResultAtColumnLength_isSavedAsIs() throws Exception {
        Fixture fx = fixture(1);
        String classification = ("T" + prefix + ".").concat("x".repeat(50 - (prefix.length() + 2)));
        String symbol = "s".repeat(50);
        String copy = "c".repeat(30);
        Long bookId = bookRaw(classification, symbol, copy, fx.shelfIds().get(0));

        MvcResult result = send(body(fx.sessionId(),
                shelf(fx.shelfCodes().get(0), obs(1, classification + "|" + symbol + "|" + copy))));

        assertThat(status(result)).isEqualTo(201);
        Map<String, Object> row = observations(fx.sessionId()).get(0);
        assertThat(row.get("detected_classification_no")).isEqualTo(classification);
        assertThat(row.get("detected_book_symbol")).isEqualTo(symbol);
        assertThat(row.get("detected_copy_number")).isEqualTo(copy);
        assertThat(row.get("book_id")).isEqualTo(bookId);
        assertThat(row.get("analysis_status")).isEqualTo("NORMAL");
    }

    @Test
    @DisplayName("점검 대상 서가가 501개 이상이어도(관측은 몇 건뿐) 한 번에 수신·저장되고 동일 재전송은 200 이다")
    void receive_moreThan500Shelves_isAccepted() throws Exception {
        Fixture fx = fixture(600);
        List<Map<String, Object>> shelves = new ArrayList<>();
        for (int i = 0; i < fx.shelfCodes().size(); i++) {
            // 앞의 3개 서가에만 관측이 있고 나머지 597개는 도서 0권
            shelves.add(i < 3 ? shelf(fx.shelfCodes().get(i), obs(1, "해석불가")) : shelf(fx.shelfCodes().get(i)));
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("scanSessionId", fx.sessionId());
        request.put("shelves", shelves);

        MvcResult created = send(request);

        assertThat(status(created)).isEqualTo(201);
        assertThat(json(created).get("data").get("shelfCount").asInt()).isEqualTo(600);
        assertThat(json(created).get("data").get("observationCount").asInt()).isEqualTo(3);
        assertThat(count("select count(*) from scan_session_shelf where scan_session_id = ? "
                + "and scan_status = 'RESULT_RECEIVED'", fx.sessionId())).isEqualTo(600);
        assertThat(count("select coalesce(sum(detected_book_count), 0) from scan_session_shelf "
                + "where scan_session_id = ?", fx.sessionId())).isEqualTo(3);
        assertThat(sessionStatus(fx.sessionId())).isEqualTo("REVIEW");
        Map<String, Object> before = snapshot(fx.sessionId());

        assertThat(status(send(request))).isEqualTo(200);
        assertThat(snapshot(fx.sessionId())).isEqualTo(before);
    }

    @Test
    @DisplayName("서가가 501개 이상인 회차에서 서가가 하나라도 빠지면 400 이고 누락된 서가 코드를 알려 준다")
    void receive_moreThan500ShelvesWithOneMissing_returns400() throws Exception {
        Fixture fx = fixture(600);
        List<Map<String, Object>> shelves = new ArrayList<>();
        for (int i = 1; i < fx.shelfCodes().size(); i++) {   // 0번 서가를 뺀다
            shelves.add(shelf(fx.shelfCodes().get(i)));
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("scanSessionId", fx.sessionId());
        request.put("shelves", shelves);

        MvcResult result = send(request);

        assertRejected(fx, result);
        assertThat(json(result).get("message").asString()).contains(fx.shelfCodes().get(0));
    }

    // ---- 도우미 ----

    /** 400 이고 저장·상태 변경이 전혀 없음을 확인한다. */
    private void assertRejected(Fixture fx, MvcResult result) throws Exception {
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(400);
        assertThat(json(result).get("code").asString()).isEqualTo("COMMON_001");
        assertThat(sessionStatus(fx.sessionId())).isEqualTo("IN_PROGRESS");
        assertThat(observationCount(fx.sessionId())).isZero();
        assertThat(count("select count(*) from scan_session_shelf where scan_session_id = ? "
                + "and scan_status <> 'WAITING'", fx.sessionId())).isZero();
    }

    private static LocalDateTime time(Map<String, Object> row, String column) {
        return ((Timestamp) row.get(column)).toLocalDateTime();
    }
}
