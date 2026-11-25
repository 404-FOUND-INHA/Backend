package com.__found.smart_library.domain.evaluation.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.__found.smart_library.domain.shelf.entity.Shelf;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * 평가 결과·폐기 검토 후보 조회 API 테스트(롤백).
 * 서가 s1: A(80)·B(60) 후보, s2: C(40) 보존·E 평가 불가, s3: F(90)·G(70)·H(55) 후보, 서가 없음: D(50) 후보.
 */
class EvaluationQueryApiTest extends EvaluationQueryTestSupport {

    private Long sid;
    private Shelf s1;
    private Shelf s2;
    private Shelf s3;
    private Long a;
    private Long b;
    private Long c;
    private Long d;
    private Long e;
    private Long f;
    private Long g;
    private Long h;

    @BeforeEach
    void setUp() {
        createUser();
        sid = session();
        s1 = shelf("S1", 1, "10.50", "20.00", "0.00");
        s2 = shelf("S2", 2, "1.00", "2.00", "3.00");
        s3 = shelf("S3", 1, "30.00", "40.00", "5.00");
        a = book("A", s1);
        b = book("B", s1);
        c = book("C", s2);
        d = book("D", null);
        e = book("E", s2);
        f = book("F", s3);
        g = book("G", s3);
        h = book("H", s3);
        backend(sid, a, "80.00", "DISPOSAL_REVIEW", "A 근거");
        backend(sid, b, "60.00", "DISPOSAL_REVIEW", "B 근거");
        backend(sid, c, "40.00", "KEEP", "C 근거");
        backend(sid, d, "50.00", "DISPOSAL_REVIEW", "D 근거");
        backend(sid, e, null, "NOT_EVALUABLE", "E 근거");
        backend(sid, f, "90.00", "DISPOSAL_REVIEW", "F 근거");
        backend(sid, g, "70.00", "DISPOSAL_REVIEW", "G 근거");
        backend(sid, h, "55.00", "DISPOSAL_REVIEW", "H 근거");
    }

    // ---- 목록 ----

    @Test
    @DisplayName("목록은 점수가 높은 순이고 점수 없는 도서는 마지막이며, 서가 위치·현황을 함께 돌려준다")
    void listOrderedByScore() throws Exception {
        JsonNode data = data(fetch(url("/evaluations")).andExpect(status().isOk()));

        assertThat(titles(data)).containsExactly("F", "A", "G", "B", "H", "D", "C", "E");
        assertThat(data.get("summary").get("total").asLong()).isEqualTo(8);
        assertThat(data.get("summary").get("disposalReview").asLong()).isEqualTo(6);
        assertThat(data.get("summary").get("keep").asLong()).isEqualTo(1);
        assertThat(data.get("summary").get("notEvaluable").asLong()).isEqualTo(1);
        assertThat(data.get("page").get("totalElements").asLong()).isEqualTo(8);

        JsonNode itemA = data.get("page").get("content").get(1);
        assertThat(itemA.get("bookId").asLong()).isEqualTo(a);
        assertThat(itemA.get("finalScore").decimalValue()).isEqualByComparingTo("80.00");
        assertThat(itemA.get("candidateStatus").asString()).isEqualTo("DISPOSAL_REVIEW");
        assertThat(itemA.get("profileVersion").asString()).isEqualTo("v3");
        assertThat(itemA.get("registrationNo").asString()).startsWith(prefix + "-A");
        assertThat(itemA.get("shelf").get("shelfId").asLong()).isEqualTo(s1.getId());
        assertThat(itemA.get("shelf").get("shelfCode").asString()).isEqualTo(prefix + "-S1");
        assertThat(itemA.get("shelf").get("floorNo").asInt()).isEqualTo(1);
        assertThat(itemA.get("shelf").get("mapX").decimalValue()).isEqualByComparingTo("10.50");
        JsonNode itemD = data.get("page").get("content").get(5);
        assertThat(itemD.get("shelf").isNull()).isTrue();
        JsonNode itemE = data.get("page").get("content").get(7);
        assertThat(itemE.get("finalScore").isNull()).isTrue();
        assertThat(itemE.get("candidateStatus").asString()).isEqualTo("NOT_EVALUABLE");
    }

    @Test
    @DisplayName("판정·점수 범위로 거른다(경계 포함). 현황은 필터와 상관없이 회차 전체이다")
    void listFilters() throws Exception {
        JsonNode disposal = data(fetch(url("/evaluations?status=DISPOSAL_REVIEW")).andExpect(status().isOk()));
        assertThat(titles(disposal)).containsExactly("F", "A", "G", "B", "H", "D");
        assertThat(disposal.get("page").get("totalElements").asLong()).isEqualTo(6);
        assertThat(disposal.get("summary").get("total").asLong()).isEqualTo(8);

        assertThat(titles(data(fetch(url("/evaluations?status=NOT_EVALUABLE")).andExpect(status().isOk()))))
                .containsExactly("E");
        assertThat(titles(data(fetch(url("/evaluations?status=KEEP")).andExpect(status().isOk()))))
                .containsExactly("C");
        assertThat(titles(data(fetch(url("/evaluations?minScore=60")).andExpect(status().isOk()))))
                .containsExactly("F", "A", "G", "B");
        assertThat(titles(data(fetch(url("/evaluations?maxScore=50")).andExpect(status().isOk()))))
                .containsExactly("D", "C");
        assertThat(titles(data(fetch(url("/evaluations?minScore=40&maxScore=60")).andExpect(status().isOk()))))
                .containsExactly("B", "H", "D", "C");
        assertThat(titles(data(fetch(url("/evaluations?status=DISPOSAL_REVIEW&minScore=55&maxScore=70"))
                .andExpect(status().isOk())))).containsExactly("G", "B", "H");
    }

    @Test
    @DisplayName("페이지는 0부터이고 크기는 1~100 으로 보정하며 합계는 필터 결과 기준이다")
    void listPaging() throws Exception {
        JsonNode first = data(fetch(url("/evaluations?size=3&page=0")).andExpect(status().isOk()));
        JsonNode second = data(fetch(url("/evaluations?size=3&page=1")).andExpect(status().isOk()));
        JsonNode last = data(fetch(url("/evaluations?size=3&page=2")).andExpect(status().isOk()));

        assertThat(titles(first)).containsExactly("F", "A", "G");
        assertThat(titles(second)).containsExactly("B", "H", "D");
        assertThat(titles(last)).containsExactly("C", "E");
        assertThat(first.get("page").get("totalElements").asLong()).isEqualTo(8);
        assertThat(titles(data(fetch(url("/evaluations?size=500&page=-4")).andExpect(status().isOk()))))
                .hasSize(8);
        assertThat(titles(data(fetch(url("/evaluations?size=0")).andExpect(status().isOk())))).hasSize(1);
        assertThat(titles(data(fetch(url("/evaluations?page=9")).andExpect(status().isOk())))).isEmpty();
    }

    @Test
    @DisplayName("잘못된 필터는 400 이다: 알 수 없는 상태, UNDECIDED, 0~100 밖의 점수, 최소가 최대보다 큼")
    void listInvalidFilters() throws Exception {
        for (String query : List.of("status=NOPE", "status=UNDECIDED", "minScore=-1", "maxScore=100.01",
                "minScore=70&maxScore=60", "minScore=abc")) {
            assertError(fetch(url("/evaluations?" + query)), 400, "COMMON_001");
        }
    }

    @Test
    @DisplayName("다른 회차의 평가와 JEV 원본 평가는 목록에 섞이지 않는다")
    void listIsolatedBySessionAndType() throws Exception {
        Long other = session();
        backend(other, a, "1.00", "KEEP", "다른 회차");
        jevEvaluation(sid, a);

        JsonNode data = data(fetch(url("/evaluations")).andExpect(status().isOk()));
        assertThat(titles(data)).hasSize(8);
        JsonNode otherData = data(fetch("/api/scan-sessions/" + other + "/evaluations").andExpect(status().isOk()));
        assertThat(titles(otherData)).containsExactly("A");
        assertThat(otherData.get("summary").get("keep").asLong()).isEqualTo(1);
        assertThat(otherData.get("summary").get("total").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("평가가 없는 회차는 빈 목록과 0 현황이고, 없는 회차는 404 이다")
    void listEmptyAndNotFound() throws Exception {
        Long empty = session();

        JsonNode data = data(fetch("/api/scan-sessions/" + empty + "/evaluations").andExpect(status().isOk()));
        assertThat(titles(data)).isEmpty();
        assertThat(data.get("summary").get("total").asLong()).isZero();
        assertError(fetch("/api/scan-sessions/999999999/evaluations"), 404, "SCAN_001");
        assertError(fetch("/api/scan-sessions/999999999/disposal-candidates"), 404, "SCAN_001");
        assertError(fetch("/api/scan-sessions/999999999/disposal-candidates/by-shelf"), 404, "SCAN_001");
        assertError(fetch("/api/scan-sessions/999999999/books/" + a + "/evaluation"), 404, "SCAN_001");
    }

    // ---- 폐기 검토 후보 ----

    @Test
    @DisplayName("폐기 검토 후보 목록은 후보만 점수 높은 순으로 서가 위치와 함께 돌려준다")
    void disposalCandidates() throws Exception {
        JsonNode data = data(fetch(url("/disposal-candidates")).andExpect(status().isOk()));

        assertThat(titles(data)).containsExactly("F", "A", "G", "B", "H", "D");
        assertThat(data.get("page").get("totalElements").asLong()).isEqualTo(6);
        assertThat(data.get("summary").get("disposalReview").asLong()).isEqualTo(6);
        assertThat(data.get("page").get("content").get(0).get("shelf").get("shelfCode").asString())
                .isEqualTo(prefix + "-S3");
        assertThat(titles(data(fetch(url("/disposal-candidates?size=2&page=1")).andExpect(status().isOk()))))
                .containsExactly("G", "B");
    }

    @Test
    @DisplayName("서가별 후보는 후보 수가 많은 서가 순이고 서가 미지정 도서는 마지막 묶음이다")
    void candidatesByShelf() throws Exception {
        JsonNode data = data(fetch(url("/disposal-candidates/by-shelf")).andExpect(status().isOk()));

        assertThat(data.get("totalCandidates").asLong()).isEqualTo(6);
        JsonNode shelves = data.get("shelves");
        assertThat(shelves).hasSize(3);
        assertThat(shelves.get(0).get("shelf").get("shelfId").asLong()).isEqualTo(s3.getId());
        assertThat(shelves.get(0).get("candidateCount").asLong()).isEqualTo(3);
        assertThat(shelves.get(0).get("maxScore").decimalValue()).isEqualByComparingTo("90.00");
        assertThat(shelves.get(0).get("shelf").get("mapZ").decimalValue()).isEqualByComparingTo("5.00");
        assertThat(shelves.get(0).get("books")).hasSize(3);
        assertThat(shelves.get(0).get("books").get(0).get("bookId").asLong()).isEqualTo(f);
        assertThat(shelves.get(0).get("books").get(0).get("finalScore").decimalValue()).isEqualByComparingTo("90.00");
        assertThat(shelves.get(0).get("books").get(2).get("bookId").asLong()).isEqualTo(h);
        assertThat(shelves.get(1).get("shelf").get("shelfId").asLong()).isEqualTo(s1.getId());
        assertThat(shelves.get(1).get("candidateCount").asLong()).isEqualTo(2);
        assertThat(shelves.get(2).get("shelf").isNull()).isTrue();
        assertThat(shelves.get(2).get("books").get(0).get("bookId").asLong()).isEqualTo(d);
        // 보존·평가 불가 도서는 후보가 아니므로 s2 는 나오지 않는다
        assertThat(shelves.toString()).doesNotContain(String.valueOf(c)).doesNotContain(prefix + "-S2");
    }

    @Test
    @DisplayName("서가 미지정 도서가 더 많아도 그 묶음은 항상 마지막이다")
    void unplacedGroupIsAlwaysLast() throws Exception {
        Long other = session();
        backend(other, book("U1", null), "70.00", "DISPOSAL_REVIEW", "근거");
        backend(other, book("U2", null), "60.00", "DISPOSAL_REVIEW", "근거");
        backend(other, book("U3", null), "50.00", "DISPOSAL_REVIEW", "근거");
        backend(other, book("P1", s1), "99.00", "DISPOSAL_REVIEW", "근거");

        JsonNode shelves = data(fetch("/api/scan-sessions/" + other + "/disposal-candidates/by-shelf")
                .andExpect(status().isOk())).get("shelves");

        assertThat(shelves).hasSize(2);
        assertThat(shelves.get(0).get("shelf").get("shelfId").asLong()).isEqualTo(s1.getId());
        assertThat(shelves.get(0).get("candidateCount").asLong()).isEqualTo(1);
        assertThat(shelves.get(1).get("shelf").isNull()).isTrue();
        assertThat(shelves.get(1).get("candidateCount").asLong()).isEqualTo(3);
    }

    @Test
    @DisplayName("서가를 지정하면 그 서가의 후보만 돌려주고, 후보가 없는 서가는 빈 목록, 없는 서가는 404 이다")
    void candidatesBySingleShelf() throws Exception {
        JsonNode one = data(fetch(url("/disposal-candidates/by-shelf?shelfId=" + s1.getId()))
                .andExpect(status().isOk()));
        assertThat(one.get("totalCandidates").asLong()).isEqualTo(2);
        assertThat(one.get("shelves")).hasSize(1);
        assertThat(one.get("shelves").get(0).get("books").get(0).get("bookId").asLong()).isEqualTo(a);

        JsonNode none = data(fetch(url("/disposal-candidates/by-shelf?shelfId=" + s2.getId()))
                .andExpect(status().isOk()));
        assertThat(none.get("totalCandidates").asLong()).isZero();
        assertThat(none.get("shelves")).isEmpty();
        assertError(fetch(url("/disposal-candidates/by-shelf?shelfId=999999999")), 404, "SHELF_001");
    }

    // ---- 상세 ----

    @Test
    @DisplayName("상세는 종합 결과·선정 근거·서가 위치와 평가축별 결과를 평가축 순서로 돌려주고 JEV 원본과 Backend 계산을 구분한다")
    void detail() throws Exception {
        Long evaluationId = jdbc.queryForObject("select evaluation_id from book_evaluation where scan_session_id = ? "
                + "and book_id = ? and evaluation_type = 'BACKEND'", Long.class, sid, a);
        backendAxis(evaluationId, "PHYSICAL_CONDITION", "30.00", "30.00");
        backendAxis(evaluationId, "DEMAND", "2.00", "70.00");
        backendAxis(evaluationId, "DUPLICATION", "3.00", "50.00");
        Long jev = jevEvaluation(sid, a);
        jevAxis(jev, "CONTENT_VALIDITY", "66.00", 3);
        jevAxis(jev, "RARITY", "60.00", 2);

        JsonNode data = data(fetch(url("/books/" + a + "/evaluation")).andExpect(status().isOk()));

        assertThat(data.get("sessionId").asLong()).isEqualTo(sid);
        assertThat(data.get("book").get("bookId").asLong()).isEqualTo(a);
        assertThat(data.get("book").get("title").asString()).isEqualTo(prefix + "-A");
        assertThat(data.get("book").get("author").asString()).isEqualTo("저자-A");
        assertThat(data.get("book").get("lifecycleStatus").asString()).isEqualTo("NORMAL");
        assertThat(data.get("shelf").get("shelfCode").asString()).isEqualTo(prefix + "-S1");
        assertThat(data.get("composite").get("evaluationId").asLong()).isEqualTo(evaluationId);
        assertThat(data.get("composite").get("finalScore").decimalValue()).isEqualByComparingTo("80.00");
        assertThat(data.get("composite").get("candidateStatus").asString()).isEqualTo("DISPOSAL_REVIEW");
        assertThat(data.get("composite").get("profileVersion").asString()).isEqualTo("v3");
        assertThat(data.get("composite").get("reason").asString()).isEqualTo("A 근거");
        assertThat(data.get("jev").get("evaluationId").asLong()).isEqualTo(jev);
        assertThat(data.get("jev").get("modelVersion").asString()).isEqualTo("jev-1.14.0");

        JsonNode axes = data.get("axes");
        assertThat(axes).hasSize(5);
        List<String> order = new ArrayList<>();
        axes.forEach(x -> order.add(x.get("criterionType").asString()));
        assertThat(order).containsExactly("DEMAND", "RARITY", "DUPLICATION", "CONTENT_VALIDITY", "PHYSICAL_CONDITION");
        JsonNode demand = axes.get(0);
        assertThat(demand.get("evaluatorType").asString()).isEqualTo("BACKEND");
        assertThat(demand.get("rawScore").decimalValue()).isEqualByComparingTo("2.00");
        assertThat(demand.get("normalizedScore").decimalValue()).isEqualByComparingTo("70.00");
        assertThat(demand.get("confidence").isNull()).isTrue();
        assertThat(demand.get("selectedGrade").isNull()).isTrue();
        assertThat(demand.get("probabilityDistribution").isNull()).isTrue();
        JsonNode rarity = axes.get(1);
        assertThat(rarity.get("evaluatorType").asString()).isEqualTo("JEV");
        assertThat(rarity.get("modelVersion").asString()).isEqualTo("jev-1.14.0");
        assertThat(rarity.get("confidence").decimalValue()).isEqualByComparingTo("0.7800");
        assertThat(rarity.get("selectedGrade").asInt()).isEqualTo(2);
        assertThat(rarity.get("probabilityDistribution").get("3").decimalValue()).isEqualByComparingTo("0.45");
    }

    @Test
    @DisplayName("JEV 결과가 없으면 jev 는 null 이고 Backend 축만 나오며, 서가가 없는 도서는 shelf 가 null 이다. 평가 불가는 점수가 null 이다")
    void detailWithoutJevOrShelfOrScore() throws Exception {
        Long evaluationId = jdbc.queryForObject("select evaluation_id from book_evaluation where scan_session_id = ? "
                + "and book_id = ? and evaluation_type = 'BACKEND'", Long.class, sid, d);
        backendAxis(evaluationId, "DEMAND", "0.00", "100.00");

        JsonNode noJev = data(fetch(url("/books/" + d + "/evaluation")).andExpect(status().isOk()));
        assertThat(noJev.get("jev").isNull()).isTrue();
        assertThat(noJev.get("shelf").isNull()).isTrue();
        assertThat(noJev.get("axes")).hasSize(1);

        JsonNode notEvaluable = data(fetch(url("/books/" + e + "/evaluation")).andExpect(status().isOk()));
        assertThat(notEvaluable.get("composite").get("finalScore").isNull()).isTrue();
        assertThat(notEvaluable.get("composite").get("candidateStatus").asString()).isEqualTo("NOT_EVALUABLE");
        assertThat(notEvaluable.get("axes")).isEmpty();
    }

    @Test
    @DisplayName("종합 평가가 없는 도서(JEV 결과만 있어도)는 404(EVAL_006)이고, 다른 회차 평가는 보이지 않는다")
    void detailNotFound() throws Exception {
        Long lonely = book("Lonely", null);
        jevEvaluation(sid, lonely);
        Long other = session();
        backend(other, lonely, "10.00", "KEEP", "다른 회차");

        assertError(fetch(url("/books/" + lonely + "/evaluation")), 404, "EVAL_006");
        assertError(fetch(url("/books/999999999/evaluation")), 404, "EVAL_006");
        assertThat(data(fetch("/api/scan-sessions/" + other + "/books/" + lonely + "/evaluation")
                .andExpect(status().isOk())).get("composite").get("reason").asString()).isEqualTo("다른 회차");
    }

    // ---- 인증 ----

    @Test
    @DisplayName("로그인한 사용자는 근로생도 볼 수 있고, 로그인하지 않으면 401 이다")
    void authorization() throws Exception {
        for (String path : List.of("/evaluations", "/disposal-candidates", "/disposal-candidates/by-shelf",
                "/books/" + a + "/evaluation")) {
            fetch(url(path), auth("ROLE_STUDENT_WORKER")).andExpect(status().isOk());
            assertThat(mockMvc.perform(get(url(path))).andReturn().getResponse().getStatus()).isEqualTo(401);
        }
    }

    // ---- helpers ----

    private String url(String path) {
        return "/api/scan-sessions/" + sid + path;
    }

    private List<String> titles(JsonNode data) {
        List<String> titles = new ArrayList<>();
        data.get("page").get("content").forEach(n -> titles.add(n.get("title").asString().substring(prefix.length() + 1)));
        return titles;
    }

    private void assertError(ResultActions result, int httpStatus, String code) throws Exception {
        var response = result.andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(httpStatus);
        assertThat(objectMapper.readTree(response.getContentAsString()).get("code").asString()).isEqualTo(code);
    }
}
