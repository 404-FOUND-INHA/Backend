package com.__found.smart_library.domain.evaluation.jev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.__found.smart_library.domain.book.entity.Book;
import com.__found.smart_library.domain.book.entity.LifecycleStatus;
import com.__found.smart_library.domain.book.entity.LoanStatus;
import com.__found.smart_library.domain.book.repository.BookRepository;
import com.__found.smart_library.domain.user.entity.AppUser;
import com.__found.smart_library.domain.user.entity.UserRole;
import com.__found.smart_library.domain.user.repository.AppUserRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Year;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 회차 JEV 평가 API 테스트(롤백). JEV 호출은 가짜 클라이언트로 대체한다. 동시 요청은 {@link JevEvaluationConcurrencyTest}. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {"app.jev.api-key=test-key", "spring.datasource.hikari.maximum-pool-size=4"})
class JevEvaluationApiTest {

    @MockitoBean JevClient jevClient;
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired AppUserRepository userRepository;
    @Autowired BookRepository bookRepository;
    @Autowired Clock clock;

    private final String prefix = "TJ" + UUID.randomUUID().toString().substring(0, 8);
    private Long userId;
    private Long shelfId;

    @BeforeEach
    void setUp() {
        userId = userRepository.saveAndFlush(AppUser.builder()
                .email("jev-" + UUID.randomUUID() + "@test.com")
                .passwordHash("hash")
                .name("사서")
                .role(UserRole.LIBRARIAN)
                .build()).getId();
        shelfId = jdbc.queryForObject("insert into shelf (shelf_code, zone, floor_no, is_active, created_at, "
                + "updated_at) values (?, ?, 1, true, now(), now()) returning shelf_id", Long.class,
                prefix + "-S", prefix + "-Z");
    }

    // ---- 평가 대상 ----

    @Test
    @DisplayName("확정된 도서만 도서당 1번 호출해 저장한다: 자동 분석 NORMAL·MISPLACED 와 사서가 확정한 도서, 제외·미확정·다른 회차는 제외")
    void evaluatesOnlyConfirmedBooks() throws Exception {
        Long sid = session("COMPLETED");
        Long a = book("A", "저자A");
        Long b = book("B", null);
        Long d = book("D", "저자D");
        Long f = book("F", "저자F");
        Long e = book("E", "저자E");
        Long g = book("G", "저자G");
        observation(sid, 1, a, "NORMAL");
        observation(sid, 2, a, "NORMAL");   // 같은 도서가 두 번 관측돼도 한 번만 평가한다
        observation(sid, 3, b, "MISPLACED");
        review(observation(sid, 4, null, "REVIEW_REQUIRED"), d, "NORMAL", "IDENTIFY_BOOK");
        review(observation(sid, 5, null, "REVIEW_REQUIRED"), f, "MISPLACED", "IDENTIFY_BOOK");
        review(observation(sid, 6, e, "REVIEW_REQUIRED"), null, "EXCLUDED", "EXCLUDE");
        observation(sid, 7, null, "REVIEW_REQUIRED");
        Long other = session("COMPLETED");
        observation(other, 1, g, "NORMAL");
        when(jevClient.evaluate(any())).thenReturn(validResponse());

        JsonNode data = data(evaluate(sid, librarian()).andExpect(status().isOk()));

        assertThat(data.get("requested").asInt()).isEqualTo(4);
        assertThat(data.get("succeeded").asInt()).isEqualTo(4);
        assertThat(data.get("skipped").asInt()).isZero();
        assertThat(data.get("failed")).isEmpty();
        em.flush();
        assertThat(evaluatedBookIds(sid)).containsExactlyInAnyOrder(a, b, d, f);
        assertThat(count("select count(*) from book_evaluation where scan_session_id = ?", other)).isZero();
        verify(jevClient, times(4)).evaluate(any());
    }

    @Test
    @DisplayName("요청에는 서지 정보·평가 기준 연도·질문 2개가 들어가고 비어 있는 정보는 보내지 않는다")
    void requestContent() throws Exception {
        Long sid = session("COMPLETED");
        Long bookId = book("요청", null);
        observation(sid, 1, bookId, "NORMAL");
        when(jevClient.evaluate(any())).thenReturn(validResponse());

        evaluate(sid, librarian()).andExpect(status().isOk());

        ArgumentCaptor<JevRequest> captor = ArgumentCaptor.forClass(JevRequest.class);
        verify(jevClient).evaluate(captor.capture());
        JevRequest request = captor.getValue();
        assertThat(request.model()).isEqualTo("jev-latest");
        assertThat(request.state()).containsEntry("title", prefix + "-요청")
                .containsEntry("classificationNo", "005.74")
                .containsEntry("evaluationYear", Year.now(clock).getValue())
                .doesNotContainKeys("author", "publisher", "isbn", "publishedYear");
        assertThat(request.questions()).containsOnlyKeys("RARITY", "CONTENT_VALIDITY");
    }

    @Test
    @DisplayName("응답은 도서마다 평가 1건과 평가축 2건으로 저장되고 값이 정규화·반올림되어 들어간다")
    void storesResultsPerBook() throws Exception {
        Long sid = session("COMPLETED");
        Long bookId = book("저장", null);
        observation(sid, 1, bookId, "NORMAL");
        when(jevClient.evaluate(any())).thenReturn(validResponse());

        evaluate(sid, librarian()).andExpect(status().isOk());
        em.flush();

        Map<String, Object> evaluation = jdbc.queryForMap(
                "select * from book_evaluation where scan_session_id = ? and book_id = ?", sid, bookId);
        assertThat(evaluation.get("evaluation_type")).isEqualTo("JEV");
        assertThat(evaluation.get("model_version")).isEqualTo("jev-1.14.0");
        assertThat(evaluation.get("candidate_status")).isEqualTo("UNDECIDED");
        assertThat(evaluation.get("final_score")).isNull();
        assertThat(evaluation.get("observation_id")).isNull();
        assertThat(evaluation.get("evaluated_at")).isNotNull();

        Map<String, Object> rarity = jdbc.queryForMap("select *, probability_distribution ->> '3' as p3 "
                + "from evaluation_criterion_result where evaluation_id = ? and criterion_type = 'RARITY'",
                evaluation.get("evaluation_id"));
        assertThat(rarity.get("evaluator_type")).isEqualTo("JEV");
        assertThat(rarity.get("model_version")).isEqualTo("jev-1.14.0");
        assertThat(rarity.get("raw_score")).hasToString("2.53");
        assertThat(rarity.get("normalized_score")).hasToString("63.25");
        assertThat(rarity.get("confidence")).hasToString("0.7800");
        assertThat(rarity.get("selected_grade")).isEqualTo(3);
        assertThat(rarity.get("p3")).isEqualTo("0.45");
        Map<String, Object> validity = jdbc.queryForMap("select * from evaluation_criterion_result "
                + "where evaluation_id = ? and criterion_type = 'CONTENT_VALIDITY'", evaluation.get("evaluation_id"));
        assertThat(validity.get("normalized_score")).hasToString("66.25");
        assertThat(validity.get("selected_grade")).isEqualTo(3);
        assertThat(count("select count(*) from evaluation_criterion_result where evaluation_id = ?",
                evaluation.get("evaluation_id"))).isEqualTo(2);
    }

    // ---- 재요청·부분 실패 ----

    @Test
    @DisplayName("이미 평가된 도서는 다시 호출하지 않고 skipped 로 센다")
    void secondRequestSkipsEvaluatedBooks() throws Exception {
        Long sid = session("COMPLETED");
        observation(sid, 1, book("A", null), "NORMAL");
        observation(sid, 2, book("B", null), "NORMAL");
        when(jevClient.evaluate(any())).thenReturn(validResponse());
        evaluate(sid, librarian()).andExpect(status().isOk());
        em.flush();

        JsonNode second = data(evaluate(sid, librarian()).andExpect(status().isOk()));

        assertThat(second.get("requested").asInt()).isEqualTo(2);
        assertThat(second.get("succeeded").asInt()).isZero();
        assertThat(second.get("skipped").asInt()).isEqualTo(2);
        verify(jevClient, times(2)).evaluate(any());
        assertThat(count("select count(*) from book_evaluation where scan_session_id = ?", sid)).isEqualTo(2);
    }

    @Test
    @DisplayName("일부 도서가 실패해도 성공한 도서는 저장되고, 다시 요청하면 실패한 도서만 평가한다")
    void partialFailureThenRetry() throws Exception {
        Long sid = session("COMPLETED");
        Long a = book("A", null);
        Long b = book("B", null);
        Long c = book("C", null);
        observation(sid, 1, a, "NORMAL");
        observation(sid, 2, b, "NORMAL");
        observation(sid, 3, c, "NORMAL");
        when(jevClient.evaluate(any())).thenAnswer(invocation -> {
            String title = (String) ((JevRequest) invocation.getArgument(0)).state().get("title");
            if (title.endsWith("-B")) {
                throw new JevCallException("JEV 호출 실패(HTTP 429)", 429, true, null);
            }
            if (title.endsWith("-C")) {
                return responseWithRarityScore("9");
            }
            return validResponse();
        });

        JsonNode first = data(evaluate(sid, librarian()).andExpect(status().isOk()));

        assertThat(first.get("requested").asInt()).isEqualTo(3);
        assertThat(first.get("succeeded").asInt()).isEqualTo(1);
        assertThat(first.get("failed")).hasSize(2);
        assertThat(first.get("failed").get(0).get("bookId").asLong()).isEqualTo(b);
        assertThat(first.get("failed").get(0).get("reason").asString()).contains("429");
        assertThat(first.get("failed").get(1).get("bookId").asLong()).isEqualTo(c);
        assertThat(first.get("failed").get(1).get("reason").asString()).contains("점수");
        em.flush();
        assertThat(evaluatedBookIds(sid)).containsExactly(a);
        assertThat(count("select count(*) from evaluation_criterion_result r join book_evaluation e "
                + "on e.evaluation_id = r.evaluation_id where e.scan_session_id = ?", sid)).isEqualTo(2);

        // 이미 answer 가 걸린 목에 when() 으로 다시 걸면 이전 answer 가 null 인자로 실행되므로 doReturn 을 쓴다.
        doReturn(validResponse()).when(jevClient).evaluate(any());
        JsonNode second = data(evaluate(sid, librarian()).andExpect(status().isOk()));

        assertThat(second.get("succeeded").asInt()).isEqualTo(2);
        assertThat(second.get("skipped").asInt()).isEqualTo(1);
        assertThat(second.get("failed")).isEmpty();
        em.flush();
        assertThat(evaluatedBookIds(sid)).containsExactlyInAnyOrder(a, b, c);
    }

    @Test
    @DisplayName("평가할 도서가 없으면 호출 없이 0 건으로 응답한다")
    void noBooks() throws Exception {
        Long sid = session("COMPLETED");
        observation(sid, 1, null, "REVIEW_REQUIRED");

        JsonNode data = data(evaluate(sid, librarian()).andExpect(status().isOk()));

        assertThat(data.get("requested").asInt()).isZero();
        assertThat(data.get("failed")).isEmpty();
        verify(jevClient, never()).evaluate(any());
    }

    // ---- 거부 ----

    @Test
    @DisplayName("점검이 확정되지 않은 회차는 409(EVAL_003)이고 JEV 를 호출하지 않는다")
    void sessionNotCompleted() throws Exception {
        for (String status : List.of("IN_PROGRESS", "REVIEW")) {
            Long sid = session(status);
            observation(sid, 1, book(status, null), "NORMAL");

            assertError(evaluate(sid, librarian()), 409, "EVAL_003");
        }
        verify(jevClient, never()).evaluate(any());
    }

    @Test
    @DisplayName("없는 회차는 404, 사서가 아니면 403, 로그인하지 않으면 401 이다")
    void notFoundAndAuthorization() throws Exception {
        Long sid = session("COMPLETED");

        assertError(evaluate(999_999_999L, librarian()), 404, "SCAN_001");
        assertError(evaluate(sid, auth("ROLE_STUDENT_WORKER")), 403, "COMMON_005");
        assertThat(mockMvc.perform(post("/api/scan-sessions/" + sid + "/jev-evaluations"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        verify(jevClient, never()).evaluate(any());
    }

    @Test
    @DisplayName("호출 실패 메시지에는 도서 정보나 API Key 가 들어가지 않는다")
    void failureReasonIsSafe() throws Exception {
        Long sid = session("COMPLETED");
        observation(sid, 1, book("민감", null), "NORMAL");
        when(jevClient.evaluate(any())).thenThrow(new JevCallException("JEV 호출 실패(HTTP 500)", 500, true, null));

        String body = evaluate(sid, librarian()).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain("test-key").doesNotContain(prefix);
        verify(jevClient, atLeastOnce()).evaluate(any());
    }

    // ---- helpers ----

    private ResultActions evaluate(Long sid, RequestPostProcessor auth) throws Exception {
        return mockMvc.perform(post("/api/scan-sessions/" + sid + "/jev-evaluations").with(auth));
    }

    private RequestPostProcessor librarian() {
        return auth("ROLE_LIBRARIAN");
    }

    private RequestPostProcessor auth(String role) {
        return jwt().jwt(j -> j.subject(String.valueOf(userId))).authorities(new SimpleGrantedAuthority(role));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }

    private void assertError(ResultActions result, int httpStatus, String code) throws Exception {
        var response = result.andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(httpStatus);
        assertThat(objectMapper.readTree(response.getContentAsString()).get("code").asString()).isEqualTo(code);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private List<Long> evaluatedBookIds(Long sid) {
        return jdbc.queryForList("select book_id from book_evaluation where scan_session_id = ? "
                + "and evaluation_type = 'JEV' order by book_id", Long.class, sid);
    }

    static JevResponse validResponse() {
        return JevResultMapperTest.response(
                JevResultMapperTest.answer("2.53", "0.78",
                        JevResultMapperTest.probabilities("0.02", "0.08", "0.35", "0.45", "0.10")),
                JevResultMapperTest.answer("2.65", "0.64",
                        JevResultMapperTest.probabilities("0.05", "0.10", "0.20", "0.45", "0.20")));
    }

    static JevResponse responseWithRarityScore(String score) {
        return JevResultMapperTest.response(
                JevResultMapperTest.answer(score, "0.78",
                        JevResultMapperTest.probabilities("0.02", "0.08", "0.35", "0.45", "0.10")),
                JevResultMapperTest.answer("2.65", "0.64",
                        JevResultMapperTest.probabilities("0.05", "0.10", "0.20", "0.45", "0.20")));
    }

    private Long session(String status) {
        return jdbc.queryForObject("insert into scan_session (session_name, target_area, status, created_by, "
                + "created_at, updated_at) values ('JEV 테스트', ?, ?, ?, now(), now()) returning scan_session_id",
                Long.class, prefix + "-Z", status, userId);
    }

    private Long book(String suffix, String author) {
        return bookRepository.saveAndFlush(Book.builder()
                .registrationNo(prefix + "-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 6))
                .title(prefix + "-" + suffix)
                .author(author)
                .callNumberRaw("005.74 김12ㄷ")
                .classificationNo("005.74")
                .bookSymbol("김12ㄷ")
                .lifecycleStatus(LifecycleStatus.NORMAL)
                .loanStatus(LoanStatus.AVAILABLE)
                .build()).getId();
    }

    private Long observation(Long sid, int order, Long bookId, String analysisStatus) {
        return jdbc.queryForObject("insert into book_observation (scan_session_id, book_id, detected_shelf_id, "
                + "detected_label_raw, detected_order, analysis_status, received_at) "
                + "values (?, ?, ?, ?, ?, ?, now()) returning observation_id", Long.class,
                sid, bookId, shelfId, "라벨-" + order, order, analysisStatus);
    }

    private void review(Long observationId, Long finalBookId, String finalStatus, String action) {
        jdbc.update("insert into review (observation_id, final_book_id, final_status, review_action, reviewer_id, "
                + "reviewed_at) values (?, ?, ?, ?, ?, now())", observationId, finalBookId, finalStatus, action,
                userId);
    }
}
