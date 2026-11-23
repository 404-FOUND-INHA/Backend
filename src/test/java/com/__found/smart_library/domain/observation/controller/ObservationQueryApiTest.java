package com.__found.smart_library.domain.observation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/** 자동 분석 결과 조회 API 3개의 응답·정렬·오류·인증. 데이터는 수신 API 로 만든다(테스트 전용 파서 사용). */
@Import(VisionTestParserConfig.class)
class ObservationQueryApiTest extends ObservationQueryTestSupport {

    // ---- 1. 회차 요약 ----

    @Test
    @DisplayName("요약: 분석 상태별·사유별·서가별 건수와 모든 대상 서가(0권 서가·서가 폭 포함)를 돌려준다")
    void analysis_afterReceive_returnsCountsAndShelves() throws Exception {
        Fixture fx = fixture(3);
        standardScenarioReceived(fx);
        jdbc.update("update shelf set width_cm = 90.00 where shelf_id = ?", fx.shelfIds().get(0));

        JsonNode data = data(fetch(analysisUrl(fx.sessionId())));

        assertThat(data.get("session").get("id").asLong()).isEqualTo(fx.sessionId());
        assertThat(data.get("session").get("status").asString()).isEqualTo("REVIEW");
        assertThat(data.get("session").get("processingStage").asString()).isEqualTo("ANALYSIS_DONE");
        assertCounts(data.get("counts"), 5, 2, 1, 2);
        JsonNode reasons = data.get("reasonCounts");
        assertThat(reasons.size()).isEqualTo(3);
        assertThat(reasons.get("WRONG_SHELF").asLong()).isEqualTo(1);
        assertThat(reasons.get("NO_MATCH").asLong()).isEqualTo(1);
        assertThat(reasons.get("OCR_FAILED").asLong()).isEqualTo(1);

        JsonNode shelves = data.get("shelves");
        assertThat(shelves.size()).isEqualTo(3);
        JsonNode s0 = shelves.get(0);
        assertThat(s0.get("shelfId").asLong()).isEqualTo(fx.shelfIds().get(0));
        assertThat(s0.get("shelfCode").asString()).isEqualTo(fx.shelfCodes().get(0));
        assertThat(s0.get("shelfWidthCm").asDouble()).isEqualTo(90.0);
        assertThat(s0.get("scanStatus").asString()).isEqualTo("RESULT_RECEIVED");
        assertThat(s0.get("detectedBookCount").asInt()).isEqualTo(4);
        assertThat(s0.get("scannedAt").isNull()).isFalse();
        assertCounts(s0.get("counts"), 4, 1, 1, 2);
        assertCounts(shelves.get(1).get("counts"), 1, 1, 0, 0);
        assertThat(shelves.get(1).get("shelfWidthCm").isNull()).isTrue();
        // 도서 0권 서가도 포함되고 수신 완료 상태·0건이다.
        JsonNode s2 = shelves.get(2);
        assertThat(s2.get("scanStatus").asString()).isEqualTo("RESULT_RECEIVED");
        assertThat(s2.get("detectedBookCount").asInt()).isZero();
        assertCounts(s2.get("counts"), 0, 0, 0, 0);
    }

    @Test
    @DisplayName("요약: 결과 수신 전(IN_PROGRESS)에는 건수가 모두 0 이고 서가는 WAITING 이다")
    void analysis_beforeReceive_returnsZerosAndWaiting() throws Exception {
        Fixture fx = fixture(2);

        JsonNode data = data(fetch(analysisUrl(fx.sessionId())));

        assertThat(data.get("session").get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(data.get("session").get("processingStage").asString()).isEqualTo("VISION_WAITING");
        assertCounts(data.get("counts"), 0, 0, 0, 0);
        assertThat(data.get("reasonCounts").size()).isZero();
        assertThat(data.get("shelves").size()).isEqualTo(2);
        for (JsonNode shelf : data.get("shelves")) {
            assertThat(shelf.get("scanStatus").asString()).isEqualTo("WAITING");
            assertThat(shelf.get("detectedBookCount").isNull()).isTrue();
            assertCounts(shelf.get("counts"), 0, 0, 0, 0);
        }
    }

    @Test
    @DisplayName("요약: 서가는 표시 순서(없으면 뒤) → 서가 ID 순으로 나온다")
    void analysis_shelvesOrderedByDisplayOrderThenId() throws Exception {
        Fixture fx = fixture(3);
        jdbc.update("update shelf set display_order = 2 where shelf_id = ?", fx.shelfIds().get(0));
        jdbc.update("update shelf set display_order = 1 where shelf_id = ?", fx.shelfIds().get(1));

        JsonNode shelves = data(fetch(analysisUrl(fx.sessionId()))).get("shelves");

        assertThat(shelves.get(0).get("shelfId").asLong()).isEqualTo(fx.shelfIds().get(1));
        assertThat(shelves.get(1).get("shelfId").asLong()).isEqualTo(fx.shelfIds().get(0));
        assertThat(shelves.get(2).get("shelfId").asLong()).isEqualTo(fx.shelfIds().get(2));
    }

    @Test
    @DisplayName("요약: 없는 회차는 404 SCAN_001")
    void analysis_sessionNotFound_returns404() throws Exception {
        assertError(fetch(analysisUrl(Long.MAX_VALUE)), 404, "SCAN_001");
    }

    // ---- 2. 서가 관측 목록 ----

    @Test
    @DisplayName("목록: 배치 순서대로 상태·사유·매칭 도서·기대 서가·두께·후보 수를 돌려주고 매칭 실패 관측도 포함한다")
    void shelfObservations_returnsPlacementStatusAndMatchedBooks() throws Exception {
        Fixture fx = fixture(3);
        Scenario sc = standardScenarioReceived(fx);
        jdbc.update("update book set book_width_cm = 3.20 where book_id = ?", sc.normalBook());
        jdbc.update("update shelf set width_cm = 90.00 where shelf_id = ?", fx.shelfIds().get(0));

        JsonNode data = data(fetch(shelfUrl(fx.sessionId(), fx.shelfIds().get(0))));

        assertThat(data.get("shelf").get("shelfId").asLong()).isEqualTo(fx.shelfIds().get(0));
        assertThat(data.get("shelf").get("shelfWidthCm").asDouble()).isEqualTo(90.0);
        assertThat(data.get("shelf").get("scanStatus").asString()).isEqualTo("RESULT_RECEIVED");
        assertThat(data.get("shelf").get("detectedBookCount").asInt()).isEqualTo(4);
        JsonNode items = data.get("observations");
        assertThat(items.size()).isEqualTo(4);
        for (int i = 0; i < 4; i++) {
            assertThat(items.get(i).get("detectedOrder").asInt()).isEqualTo(i + 1);
        }

        JsonNode normal = items.get(0);
        assertThat(normal.get("observationId").asLong())
                .isEqualTo(observationId(fx.sessionId(), fx.shelfIds().get(0), 1));
        assertThat(normal.get("xPositionCm").asDouble()).isEqualTo(10.5);
        assertThat(normal.get("analysisStatus").asString()).isEqualTo("NORMAL");
        assertThat(normal.get("statusReason").isNull()).isTrue();
        assertThat(normal.get("matchConfidence").asDouble()).isEqualTo(1.0);
        assertThat(normal.get("bookId").asLong()).isEqualTo(sc.normalBook());
        assertThat(normal.get("matchedBook").get("bookWidthCm").asDouble()).isEqualTo(3.2);
        assertThat(normal.get("matchedBook").get("expectedShelfId").asLong()).isEqualTo(fx.shelfIds().get(0));
        assertThat(normal.get("matchedBook").get("expectedShelfCode").asString()).isEqualTo(fx.shelfCodes().get(0));
        assertThat(normal.get("matchedBook").get("registrationNo").asString()).startsWith(prefix);
        assertThat(normal.get("matchedBook").has("loanStatus")).isFalse();
        assertThat(normal.get("candidateCount").asLong()).isZero();

        // 오배치: 어느 서가의 도서인지(기대 서가)가 나온다.
        JsonNode misplaced = items.get(1);
        assertThat(misplaced.get("analysisStatus").asString()).isEqualTo("MISPLACED");
        assertThat(misplaced.get("statusReason").asString()).isEqualTo("WRONG_SHELF");
        assertThat(misplaced.get("matchedBook").get("expectedShelfId").asLong()).isEqualTo(fx.shelfIds().get(1));
        assertThat(misplaced.get("matchedBook").get("expectedShelfCode").asString())
                .isEqualTo(fx.shelfCodes().get(1));
        assertThat(misplaced.get("matchedBook").get("bookWidthCm").isNull()).isTrue();

        // 매칭 실패: 도서는 없지만 원문·파싱값·후보 수가 있다.
        JsonNode noMatch = items.get(2);
        assertThat(noMatch.get("analysisStatus").asString()).isEqualTo("REVIEW_REQUIRED");
        assertThat(noMatch.get("statusReason").asString()).isEqualTo("NO_MATCH");
        assertThat(noMatch.get("bookId").isNull()).isTrue();
        assertThat(noMatch.get("matchedBook").isNull()).isTrue();
        assertThat(noMatch.get("matchConfidence").isNull()).isTrue();
        assertThat(noMatch.get("detectedLabelRaw").asString()).isEqualTo(label("C", "c.2"));
        assertThat(noMatch.get("detectedCopyNumber").asString()).isEqualTo("c.2");
        assertThat(noMatch.get("candidateCount").asLong()).isEqualTo(1);
        assertThat(noMatch.has("candidates")).as("후보 전체는 상세 API 에서만 제공한다").isFalse();

        // 해석 불가 라벨
        JsonNode ocr = items.get(3);
        assertThat(ocr.get("statusReason").asString()).isEqualTo("OCR_FAILED");
        assertThat(ocr.get("detectedClassificationNo").isNull()).isTrue();
        assertThat(ocr.get("detectedLabelRaw").asString()).isEqualTo("해석불가");
        assertThat(ocr.get("candidateCount").asLong()).isZero();
    }

    @Test
    @DisplayName("목록: 요청에 담긴 순서와 상관없이 detectedOrder 오름차순으로 나오고 xPositionCm 이 없으면 null 이다")
    void shelfObservations_sortedByDetectedOrder_andNullPosition() throws Exception {
        Fixture fx = fixture(1);
        Map<String, Object> third = obs(3, "해석불가");
        Map<String, Object> first = obs(1, "해석불가");
        Map<String, Object> second = obs(2, "해석불가");
        second.remove("xPositionCm");
        receive(body(fx.sessionId(), shelf(fx.shelfCodes().get(0), third, first, second)));

        JsonNode items = data(fetch(shelfUrl(fx.sessionId(), fx.shelfIds().get(0)))).get("observations");

        assertThat(items.size()).isEqualTo(3);
        assertThat(items.get(0).get("detectedOrder").asInt()).isEqualTo(1);
        assertThat(items.get(1).get("detectedOrder").asInt()).isEqualTo(2);
        assertThat(items.get(2).get("detectedOrder").asInt()).isEqualTo(3);
        assertThat(items.get(1).get("xPositionCm").isNull()).isTrue();
        assertThat(items.get(0).get("xPositionCm").isNull()).isFalse();
    }

    @Test
    @DisplayName("목록: 도서 0권 서가와 수신 전 서가는 빈 배열이다")
    void shelfObservations_emptyShelf_returnsEmptyList() throws Exception {
        Fixture fx = fixture(3);
        // 수신 전
        JsonNode before = data(fetch(shelfUrl(fx.sessionId(), fx.shelfIds().get(0))));
        assertThat(before.get("observations").size()).isZero();
        assertThat(before.get("shelf").get("scanStatus").asString()).isEqualTo("WAITING");

        standardScenarioReceived(fx);
        JsonNode after = data(fetch(shelfUrl(fx.sessionId(), fx.shelfIds().get(2))));
        assertThat(after.get("observations").size()).isZero();
        assertThat(after.get("shelf").get("scanStatus").asString()).isEqualTo("RESULT_RECEIVED");
        assertThat(after.get("shelf").get("detectedBookCount").asInt()).isZero();
    }

    @Test
    @DisplayName("목록: 다른 서가의 관측은 섞이지 않는다")
    void shelfObservations_onlyOwnShelf() throws Exception {
        Fixture fx = fixture(3);
        standardScenarioReceived(fx);

        JsonNode items = data(fetch(shelfUrl(fx.sessionId(), fx.shelfIds().get(1)))).get("observations");

        assertThat(items.size()).isEqualTo(1);
        assertThat(items.get(0).get("analysisStatus").asString()).isEqualTo("NORMAL");
    }

    @Test
    @DisplayName("목록: 없는 회차는 404 SCAN_001, 회차 대상이 아니거나 없는 서가는 404 SCAN_007")
    void shelfObservations_notFound() throws Exception {
        Fixture fx = fixture(1);
        Long otherShelf = insertShelf(prefix + "-OTHER", fx.zone());
        Fixture otherSession = fixture(1);

        assertError(fetch(shelfUrl(Long.MAX_VALUE, fx.shelfIds().get(0))), 404, "SCAN_001");
        assertError(fetch(shelfUrl(fx.sessionId(), otherShelf)), 404, "SCAN_007");
        assertError(fetch(shelfUrl(fx.sessionId(), otherSession.shelfIds().get(0))), 404, "SCAN_007");
        assertError(fetch(shelfUrl(fx.sessionId(), Long.MAX_VALUE)), 404, "SCAN_007");
    }

    // ---- 3. 관측 상세 ----

    @Test
    @DisplayName("상세: 매칭 실패 관측은 원문·파싱 결과·후보(순위·근거·도서·기대 서가)를 돌려주고 도서는 null 이다")
    void observation_noMatch_returnsRawParsedAndCandidates() throws Exception {
        Fixture fx = fixture(3);
        Scenario sc = standardScenarioReceived(fx);
        Long id = observationId(fx.sessionId(), fx.shelfIds().get(0), 3);

        JsonNode d = data(fetch(observationUrl(fx.sessionId(), id)));

        assertThat(d.get("observationId").asLong()).isEqualTo(id);
        assertThat(d.get("detectedShelfId").asLong()).isEqualTo(fx.shelfIds().get(0));
        assertThat(d.get("detectedShelfCode").asString()).isEqualTo(fx.shelfCodes().get(0));
        assertThat(d.get("detectedOrder").asInt()).isEqualTo(3);
        assertThat(d.get("xPositionCm").asDouble()).isEqualTo(31.5);
        assertThat(d.get("detectedLabelRaw").asString()).isEqualTo(label("C", "c.2"));
        assertThat(d.get("detectedTitle").asString()).isEqualTo("제목 3");
        assertThat(d.get("detectedClassificationNo").asString()).isEqualTo(cls("C"));
        assertThat(d.get("detectedBookSymbol").asString()).isEqualTo(sym("C"));
        assertThat(d.get("detectedCopyNumber").asString()).isEqualTo("c.2");
        assertThat(d.get("detectedAt").asString()).isEqualTo("2026-11-24T10:00:03");
        assertThat(d.get("receivedAt").isNull()).isFalse();
        assertThat(d.get("analysisStatus").asString()).isEqualTo("REVIEW_REQUIRED");
        assertThat(d.get("statusReason").asString()).isEqualTo("NO_MATCH");
        assertThat(d.get("bookId").isNull()).isTrue();
        assertThat(d.get("matchedBook").isNull()).isTrue();
        JsonNode candidates = d.get("candidates");
        assertThat(candidates.size()).isEqualTo(1);
        assertThat(candidates.get(0).get("rank").asInt()).isEqualTo(1);
        assertThat(candidates.get(0).get("reason").asString()).isEqualTo("COPY_MISMATCH");
        JsonNode book = candidates.get(0).get("book");
        assertThat(book.get("bookId").asLong()).isEqualTo(sc.copyMismatchBook());
        assertThat(book.get("copyNumber").asString()).isEqualTo("c.1");
        assertThat(book.get("expectedShelfId").asLong()).isEqualTo(fx.shelfIds().get(0));
        assertThat(book.get("expectedShelfCode").asString()).isEqualTo(fx.shelfCodes().get(0));
        assertThat(book.has("loanStatus")).isFalse();
        assertThat(d.get("duplicateObservations").size()).isZero();
    }

    @Test
    @DisplayName("상세: 해석 불가 관측은 파싱값이 null 이고 후보가 없다")
    void observation_ocrFailed_hasNoParsedValuesOrCandidates() throws Exception {
        Fixture fx = fixture(3);
        standardScenarioReceived(fx);

        JsonNode d = data(fetch(observationUrl(fx.sessionId(),
                observationId(fx.sessionId(), fx.shelfIds().get(0), 4))));

        assertThat(d.get("statusReason").asString()).isEqualTo("OCR_FAILED");
        assertThat(d.get("detectedClassificationNo").isNull()).isTrue();
        assertThat(d.get("detectedBookSymbol").isNull()).isTrue();
        assertThat(d.get("detectedCopyNumber").isNull()).isTrue();
        assertThat(d.get("detectedLabelRaw").asString()).isEqualTo("해석불가");
        assertThat(d.get("candidates").size()).isZero();
        assertThat(d.get("matchedBook").isNull()).isTrue();
    }

    @Test
    @DisplayName("상세: 오배치 관측은 매칭 도서와 기대 서가를, 정상 관측은 후보·중복 없이 돌려준다")
    void observation_misplacedAndNormal() throws Exception {
        Fixture fx = fixture(3);
        Scenario sc = standardScenarioReceived(fx);

        JsonNode misplaced = data(fetch(observationUrl(fx.sessionId(),
                observationId(fx.sessionId(), fx.shelfIds().get(0), 2))));
        assertThat(misplaced.get("analysisStatus").asString()).isEqualTo("MISPLACED");
        assertThat(misplaced.get("bookId").asLong()).isEqualTo(sc.misplacedBook());
        assertThat(misplaced.get("matchedBook").get("expectedShelfCode").asString())
                .isEqualTo(fx.shelfCodes().get(1));
        assertThat(misplaced.get("candidates").size()).isZero();

        JsonNode normal = data(fetch(observationUrl(fx.sessionId(),
                observationId(fx.sessionId(), fx.shelfIds().get(0), 1))));
        assertThat(normal.get("analysisStatus").asString()).isEqualTo("NORMAL");
        assertThat(normal.get("statusReason").isNull()).isTrue();
        assertThat(normal.get("duplicateObservations").size()).isZero();
    }

    @Test
    @DisplayName("상세: 동일 청구기호 도서가 여럿이면 도서는 null 이고 후보 2건이 EXACT_MATCH 순위순으로 나온다")
    void observation_ambiguous_returnsCandidatesWithoutBook() throws Exception {
        Fixture fx = fixture(1);
        Long first = bookRaw(cls("A"), sym("A"), "c.1", fx.shelfIds().get(0));
        Long second = bookRaw(cls("A"), sym("A"), "c.1", fx.shelfIds().get(0));
        receive(body(fx.sessionId(), shelf(fx.shelfCodes().get(0), obs(1, label("A", "c.1")))));

        JsonNode d = data(fetch(observationUrl(fx.sessionId(),
                observationId(fx.sessionId(), fx.shelfIds().get(0), 1))));

        assertThat(d.get("statusReason").asString()).isEqualTo("AMBIGUOUS_MATCH");
        assertThat(d.get("bookId").isNull()).isTrue();
        assertThat(d.get("matchedBook").isNull()).isTrue();
        JsonNode candidates = d.get("candidates");
        assertThat(candidates.size()).isEqualTo(2);
        assertThat(candidates.get(0).get("rank").asInt()).isEqualTo(1);
        assertThat(candidates.get(1).get("rank").asInt()).isEqualTo(2);
        assertThat(candidates.get(0).get("reason").asString()).isEqualTo("EXACT_MATCH");
        List<Long> bookIds = new ArrayList<>(List.of(candidates.get(0).get("book").get("bookId").asLong(),
                candidates.get(1).get("book").get("bookId").asLong()));
        assertThat(bookIds).containsExactlyInAnyOrder(first, second);

        // 목록의 후보 수와 일치한다.
        JsonNode item = data(fetch(shelfUrl(fx.sessionId(), fx.shelfIds().get(0)))).get("observations").get(0);
        assertThat(item.get("candidateCount").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("상세: 같은 도서가 3번 관측되면 각 관측은 자신을 제외한 나머지 모두를 서가·순서순으로 돌려준다")
    void observation_duplicate_returnsAllOtherObservations() throws Exception {
        Fixture fx = fixture(2);
        Long dup = book("D", "c.1", fx.shelfIds().get(0), "NORMAL");
        receive(body(fx.sessionId(),
                shelf(fx.shelfCodes().get(0), obs(1, label("D", "c.1")), obs(2, label("D", "c.1"))),
                shelf(fx.shelfCodes().get(1), obs(1, label("D", "c.1")))));
        Long s0o1 = observationId(fx.sessionId(), fx.shelfIds().get(0), 1);
        Long s0o2 = observationId(fx.sessionId(), fx.shelfIds().get(0), 2);
        Long s1o1 = observationId(fx.sessionId(), fx.shelfIds().get(1), 1);

        JsonNode first = data(fetch(observationUrl(fx.sessionId(), s0o1)));
        assertThat(first.get("statusReason").asString()).isEqualTo("DUPLICATE_OBSERVATION");
        assertThat(first.get("analysisStatus").asString()).isEqualTo("REVIEW_REQUIRED");
        assertThat(first.get("bookId").asLong()).isEqualTo(dup);
        assertThat(first.get("matchedBook").isNull()).isFalse();
        assertDuplicates(first.get("duplicateObservations"), fx, new long[][] {{0, 2, s0o2}, {1, 1, s1o1}});

        JsonNode second = data(fetch(observationUrl(fx.sessionId(), s0o2)));
        assertDuplicates(second.get("duplicateObservations"), fx, new long[][] {{0, 1, s0o1}, {1, 1, s1o1}});

        JsonNode third = data(fetch(observationUrl(fx.sessionId(), s1o1)));
        assertDuplicates(third.get("duplicateObservations"), fx, new long[][] {{0, 1, s0o1}, {0, 2, s0o2}});
    }

    @Test
    @DisplayName("상세: 제적 도서와 예상 서가가 없는 도서는 도서 정보를 유지한 채 사유로 구분된다")
    void observation_disposedAndNoExpectedShelf() throws Exception {
        Fixture fx = fixture(1);
        Long disposed = book("X", "c.1", fx.shelfIds().get(0), "DISPOSED");
        Long noShelf = book("Y", "c.1", null, "NORMAL");
        receive(body(fx.sessionId(), shelf(fx.shelfCodes().get(0), obs(1, label("X", "c.1")),
                obs(2, label("Y", "c.1")))));

        JsonNode d1 = data(fetch(observationUrl(fx.sessionId(),
                observationId(fx.sessionId(), fx.shelfIds().get(0), 1))));
        assertThat(d1.get("statusReason").asString()).isEqualTo("DISPOSED_BOOK");
        assertThat(d1.get("bookId").asLong()).isEqualTo(disposed);
        assertThat(d1.get("matchedBook").get("lifecycleStatus").asString()).isEqualTo("DISPOSED");

        JsonNode d2 = data(fetch(observationUrl(fx.sessionId(),
                observationId(fx.sessionId(), fx.shelfIds().get(0), 2))));
        assertThat(d2.get("statusReason").asString()).isEqualTo("NO_EXPECTED_SHELF");
        assertThat(d2.get("bookId").asLong()).isEqualTo(noShelf);
        assertThat(d2.get("matchedBook").get("expectedShelfId").isNull()).isTrue();
        assertThat(d2.get("matchedBook").get("expectedShelfCode").isNull()).isTrue();
    }

    @Test
    @DisplayName("상세: 없는 회차는 404 SCAN_001, 없는 관측과 다른 회차의 관측은 404 SCAN_008")
    void observation_notFound() throws Exception {
        Fixture fx = fixture(3);
        standardScenarioReceived(fx);
        Fixture other = fixture(1);
        receive(body(other.sessionId(), shelf(other.shelfCodes().get(0), obs(1, "해석불가"))));
        Long otherObservation = observationId(other.sessionId(), other.shelfIds().get(0), 1);

        assertError(fetch(observationUrl(Long.MAX_VALUE, 1L)), 404, "SCAN_001");
        assertError(fetch(observationUrl(fx.sessionId(), Long.MAX_VALUE)), 404, "SCAN_008");
        assertError(fetch(observationUrl(fx.sessionId(), otherObservation)), 404, "SCAN_008");
        // 같은 관측이라도 올바른 회차로는 조회된다.
        assertThat(status(fetch(observationUrl(other.sessionId(), otherObservation)))).isEqualTo(200);
    }

    // ---- 인증 ----

    @Test
    @DisplayName("인증: 토큰이 없으면 401, Vision API Key 만으로는 조회할 수 없다")
    void auth_requiresJwt() throws Exception {
        Fixture fx = fixture(1);
        receive(body(fx.sessionId(), shelf(fx.shelfCodes().get(0), obs(1, "해석불가"))));
        Long id = observationId(fx.sessionId(), fx.shelfIds().get(0), 1);

        for (String url : new String[] {analysisUrl(fx.sessionId()), shelfUrl(fx.sessionId(), fx.shelfIds().get(0)),
                observationUrl(fx.sessionId(), id)}) {
            MvcResult noToken = mockMvc.perform(get(url)).andReturn();
            assertError(noToken, 401, "COMMON_004");
            MvcResult visionKey = mockMvc.perform(get(url).header(HEADER, API_KEY)).andReturn();
            assertError(visionKey, 401, "COMMON_004");
        }
    }

    @Test
    @DisplayName("인증: 로그인한 사용자는 역할(사서·근로학생)과 무관하게 조회할 수 있다")
    void auth_anyLoggedInRoleCanRead() throws Exception {
        Fixture fx = fixture(1);
        receive(body(fx.sessionId(), shelf(fx.shelfCodes().get(0), obs(1, "해석불가"))));
        Long id = observationId(fx.sessionId(), fx.shelfIds().get(0), 1);

        for (String role : new String[] {"ROLE_LIBRARIAN", "ROLE_STUDENT_WORKER"}) {
            assertThat(status(fetchAs(analysisUrl(fx.sessionId()), role))).isEqualTo(200);
            assertThat(status(fetchAs(shelfUrl(fx.sessionId(), fx.shelfIds().get(0)), role))).isEqualTo(200);
            assertThat(status(fetchAs(observationUrl(fx.sessionId(), id), role))).isEqualTo(200);
        }
    }

    // ---- 도우미 ----

    /** 대표 시나리오를 수신 API 로 저장한다(서가 3개 회차). */
    private Scenario standardScenarioReceived(Fixture fx) throws Exception {
        Scenario sc = standardScenario(fx);
        receive(sc.body());
        return sc;
    }

    private static void assertCounts(JsonNode counts, long total, long normal, long misplaced, long reviewRequired) {
        assertThat(counts.get("total").asLong()).isEqualTo(total);
        assertThat(counts.get("normal").asLong()).isEqualTo(normal);
        assertThat(counts.get("misplaced").asLong()).isEqualTo(misplaced);
        assertThat(counts.get("reviewRequired").asLong()).isEqualTo(reviewRequired);
    }

    /** expected: {서가 인덱스, 배치 순서, 관측 ID} 를 순서대로. */
    private void assertDuplicates(JsonNode actual, Fixture fx, long[][] expected) {
        assertThat(actual.size()).isEqualTo(expected.length);
        for (int i = 0; i < expected.length; i++) {
            int shelfIndex = (int) expected[i][0];
            assertThat(actual.get(i).get("detectedShelfId").asLong()).isEqualTo(fx.shelfIds().get(shelfIndex));
            assertThat(actual.get(i).get("detectedShelfCode").asString()).isEqualTo(fx.shelfCodes().get(shelfIndex));
            assertThat(actual.get(i).get("detectedOrder").asInt()).isEqualTo((int) expected[i][1]);
            assertThat(actual.get(i).get("observationId").asLong()).isEqualTo(expected[i][2]);
        }
    }
}
