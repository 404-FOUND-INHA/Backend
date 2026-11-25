package com.__found.smart_library.domain.evaluation.composite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.__found.smart_library.domain.book.entity.Book;
import com.__found.smart_library.domain.book.entity.LifecycleStatus;
import com.__found.smart_library.domain.book.entity.LoanStatus;
import com.__found.smart_library.domain.book.repository.BookRepository;
import com.__found.smart_library.domain.evaluation.dto.CriterionWeight;
import com.__found.smart_library.domain.evaluation.dto.EvaluationSettingsUpdateRequest;
import com.__found.smart_library.domain.evaluation.entity.CriterionType;
import com.__found.smart_library.domain.evaluation.service.EvaluationSettingsService;
import com.__found.smart_library.domain.user.entity.AppUser;
import com.__found.smart_library.domain.user.entity.UserRole;
import com.__found.smart_library.domain.user.repository.AppUserRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 회차 종합 평가 API 테스트(롤백). 평가 설정은 테스트 안에서 직접 저장해 고정한다.
 * 도서는 대출·오손 기록이 없고 복본이 없어 Backend 지표가 수요성 100, 중복성 0, 물리적 상태 0 이다.
 * 동시 실행은 {@link CompositeEvaluationConcurrencyTest} 에서 실제 커밋으로 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CompositeEvaluationApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired AppUserRepository userRepository;
    @Autowired BookRepository bookRepository;
    @Autowired EvaluationSettingsService settingsService;

    private final String prefix = "TV" + UUID.randomUUID().toString().substring(0, 8);
    private Long userId;
    private Long shelfId;
    private Long sessionId;
    private String profileVersion;

    @BeforeEach
    void setUp() {
        userId = userRepository.saveAndFlush(AppUser.builder()
                .email("composite-" + UUID.randomUUID() + "@test.com")
                .passwordHash("hash")
                .name("사서")
                .role(UserRole.LIBRARIAN)
                .build()).getId();
        shelfId = jdbc.queryForObject("insert into shelf (shelf_code, zone, floor_no, is_active, created_at, "
                + "updated_at) values (?, ?, 1, true, now(), now()) returning shelf_id", Long.class,
                prefix + "-S", prefix + "-Z");
        sessionId = session("COMPLETED");
        profileVersion = saveSettings("50.00", "0.2", "0.2", "0.2", "0.2", "0.2");
    }

    // ---- 계산과 저장 ----

    @Test
    @DisplayName("종합 점수·후보 판정·선정 근거를 Backend 평가로 저장하고 JEV 원본은 건드리지 않는다")
    void computesAndStores() throws Exception {
        Long boundary = confirmedBook("경계");
        Long below = confirmedBook("미만");
        Long noJev = confirmedBook("JEV없음");
        Long jevA = jev(sessionId, boundary, "100", "50");
        jev(sessionId, below, "100", "49.95");
        em.flush();

        JsonNode data = data(evaluate(sessionId, librarian()).andExpect(status().isOk()));

        assertThat(data.get("total").asInt()).isEqualTo(3);
        assertThat(data.get("disposalReview").asInt()).isEqualTo(1);
        assertThat(data.get("keep").asInt()).isEqualTo(1);
        assertThat(data.get("notEvaluable").asInt()).isEqualTo(1);
        assertThat(data.get("created").asInt()).isEqualTo(3);
        assertThat(data.get("updated").asInt()).isZero();
        assertThat(data.get("unchanged").asInt()).isZero();
        assertThat(data.get("profileVersion").asString()).isEqualTo(profileVersion);
        assertThat(data.get("disposalThreshold").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(data.get("notEvaluableBookIds")).hasSize(1);
        assertThat(data.get("notEvaluableBookIds").get(0).asLong()).isEqualTo(noJev);

        Map<String, Object> candidate = backend(boundary);
        assertThat(candidate.get("final_score")).hasToString("50.00");
        assertThat(candidate.get("candidate_status")).isEqualTo("DISPOSAL_REVIEW");
        assertThat(candidate.get("model_version")).isEqualTo(profileVersion);
        assertThat(candidate.get("observation_id")).isNull();
        String reason = (String) candidate.get("evaluation_reason");
        assertThat(reason).contains("종합 점수 50.00점으로 기준 점수 50.00점 이상이라 폐기 검토 후보입니다.")
                .contains("(평가 설정 " + profileVersion + ")")
                .contains("- 수요성 100.00점 × 0.2000 = 20.00점")
                .contains("- 희소성 100.00점 × 0.2000 = 20.00점 · JEV 단계 3, 신뢰도 0.7800")
                .contains("- 내용 유효성 50.00점 × 0.2000 = 10.00점");
        assertThat(backend(below).get("final_score")).hasToString("49.99");
        assertThat(backend(below).get("candidate_status")).isEqualTo("KEEP");
        Map<String, Object> notEvaluable = backend(noJev);
        assertThat(notEvaluable.get("final_score")).isNull();
        assertThat(notEvaluable.get("candidate_status")).isEqualTo("NOT_EVALUABLE");
        assertThat((String) notEvaluable.get("evaluation_reason")).contains("없는 평가축: 희소성, 내용 유효성");

        // Backend 지표 3개가 평가축별 결과로 남는다(JEV 평가축은 JEV 원본 행에만 있다)
        List<Map<String, Object>> rows = jdbc.queryForList("select * from evaluation_criterion_result "
                + "where evaluation_id = ? order by criterion_type", candidate.get("evaluation_id"));
        assertThat(rows).extracting(r -> r.get("criterion_type"))
                .containsExactly("DEMAND", "DUPLICATION", "PHYSICAL_CONDITION");
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("evaluator_type")).isEqualTo("BACKEND");
            assertThat(r.get("model_version")).isEqualTo(profileVersion);
            assertThat(r.get("confidence")).isNull();
            assertThat(r.get("selected_grade")).isNull();
        });
        assertThat(rows.get(0).get("normalized_score")).hasToString("100.00");
        assertThat(rows.get(1).get("normalized_score")).hasToString("0.00");
        assertThat(rows.get(2).get("normalized_score")).hasToString("0.00");

        // 평가 불가 도서도 Backend 지표는 남긴다
        assertThat(count("select count(*) from evaluation_criterion_result where evaluation_id = ?",
                notEvaluable.get("evaluation_id"))).isEqualTo(3);
        // JEV 원본은 그대로
        Map<String, Object> jevRow = jdbc.queryForMap("select * from book_evaluation where evaluation_id = ?", jevA);
        assertThat(jevRow.get("evaluation_type")).isEqualTo("JEV");
        assertThat(jevRow.get("candidate_status")).isEqualTo("UNDECIDED");
        assertThat(jevRow.get("final_score")).isNull();
        assertThat(count("select count(*) from evaluation_criterion_result where evaluation_id = ?", jevA))
                .isEqualTo(2);
        // 감사 로그
        assertThat(count("select count(*) from audit_log where entity_type = 'BOOK_EVALUATION' "
                + "and action = 'CREATE' and actor_id = ?", userId)).isEqualTo(3);
    }

    @Test
    @DisplayName("점검에서 확정된 도서만 평가하고, 확정 도서가 없으면 0 건이다")
    void onlyConfirmedBooks() throws Exception {
        Long confirmed = confirmedBook("확정");
        Long notConfirmed = book("미확정");
        observation(sessionId, 90, notConfirmed, "REVIEW_REQUIRED");
        Long other = session("COMPLETED");
        Long elsewhere = book("다른회차");
        observation(other, 1, elsewhere, "NORMAL");
        jev(sessionId, confirmed, "10", "10");
        em.flush();

        JsonNode data = data(evaluate(sessionId, librarian()).andExpect(status().isOk()));

        assertThat(data.get("total").asInt()).isEqualTo(1);
        assertThat(count("select count(*) from book_evaluation where evaluation_type = 'BACKEND' "
                + "and scan_session_id = ?", sessionId)).isEqualTo(1);
        assertThat(count("select count(*) from book_evaluation where scan_session_id = ?", other)).isZero();

        Long empty = session("COMPLETED");
        JsonNode none = data(evaluate(empty, librarian()).andExpect(status().isOk()));
        assertThat(none.get("total").asInt()).isZero();
        assertThat(none.get("notEvaluableBookIds")).isEmpty();
    }

    // ---- 다시 실행 ----

    @Test
    @DisplayName("결과가 같으면 다시 실행해도 아무것도 바뀌지 않고 감사 로그도 늘지 않는다")
    void rerunUnchanged() throws Exception {
        Long bookId = confirmedBook("재실행");
        jev(sessionId, bookId, "100", "50");
        em.flush();
        evaluate(sessionId, librarian()).andExpect(status().isOk());
        em.flush();
        Object evaluatedAt = backend(bookId).get("evaluated_at");

        JsonNode second = data(evaluate(sessionId, librarian()).andExpect(status().isOk()));

        assertThat(second.get("created").asInt()).isZero();
        assertThat(second.get("updated").asInt()).isZero();
        assertThat(second.get("unchanged").asInt()).isEqualTo(1);
        assertThat(backend(bookId).get("evaluated_at")).isEqualTo(evaluatedAt);
        assertThat(count("select count(*) from audit_log where entity_type = 'BOOK_EVALUATION'")).isEqualTo(1);
        assertThat(count("select count(*) from book_evaluation where evaluation_type = 'BACKEND' "
                + "and scan_session_id = ?", sessionId)).isEqualTo(1);
    }

    @Test
    @DisplayName("JEV 결과가 뒤늦게 들어오면 평가 불가였던 도서가 판정되고, 축별 결과는 중복 없이 교체되며 변경이 감사 로그에 남는다")
    void jevArrivesLater() throws Exception {
        Long waiting = confirmedBook("대기");
        Long stable = confirmedBook("그대로");
        jev(sessionId, stable, "10", "10");
        em.flush();
        evaluate(sessionId, librarian()).andExpect(status().isOk());
        em.flush();
        assertThat(backend(waiting).get("candidate_status")).isEqualTo("NOT_EVALUABLE");

        jev(sessionId, waiting, "100", "100");
        em.flush();
        JsonNode second = data(evaluate(sessionId, librarian()).andExpect(status().isOk()));
        em.flush();

        assertThat(second.get("updated").asInt()).isEqualTo(1);
        assertThat(second.get("unchanged").asInt()).isEqualTo(1);
        Map<String, Object> row = backend(waiting);
        assertThat(row.get("final_score")).hasToString("60.00");
        assertThat(row.get("candidate_status")).isEqualTo("DISPOSAL_REVIEW");
        assertThat(count("select count(*) from evaluation_criterion_result where evaluation_id = ?",
                row.get("evaluation_id"))).isEqualTo(3);
        Map<String, Object> audit = jdbc.queryForMap("select before_data ->> 'candidateStatus' as bs, "
                + "after_data ->> 'candidateStatus' as a_s, before_data ->> 'finalScore' as bf, "
                + "after_data ->> 'finalScore' as af from audit_log where entity_type = 'BOOK_EVALUATION' "
                + "and action = 'UPDATE' and entity_id = ?", row.get("evaluation_id"));
        assertThat(audit.get("bs")).isEqualTo("NOT_EVALUABLE");
        assertThat(audit.get("a_s")).isEqualTo("DISPOSAL_REVIEW");
        assertThat(audit.get("bf")).isNull();
        assertThat(audit.get("af")).isEqualTo("60.00");
        assertThat(count("select count(*) from audit_log where entity_type = 'BOOK_EVALUATION' "
                + "and action = 'UPDATE'")).isEqualTo(1);
    }

    @Test
    @DisplayName("가중치·기준 점수를 바꾸면 새 설정 버전으로 다시 계산하고 바뀐 값만 감사 로그에 남긴다")
    void settingsChangeRecalculates() throws Exception {
        Long bookId = confirmedBook("설정변경");
        jev(sessionId, bookId, "100", "50");
        em.flush();
        evaluate(sessionId, librarian()).andExpect(status().isOk());
        em.flush();

        String newVersion = saveSettings("70.00", "0.6", "0.1", "0.1", "0.1", "0.1");
        JsonNode second = data(evaluate(sessionId, librarian()).andExpect(status().isOk()));
        em.flush();

        assertThat(newVersion).isNotEqualTo(profileVersion);
        assertThat(second.get("profileVersion").asString()).isEqualTo(newVersion);
        assertThat(second.get("updated").asInt()).isEqualTo(1);
        Map<String, Object> row = backend(bookId);
        assertThat(row.get("model_version")).isEqualTo(newVersion);
        // 60 + 10 + 0 + 5 + 0
        assertThat(row.get("final_score")).hasToString("75.00");
        assertThat(row.get("candidate_status")).isEqualTo("DISPOSAL_REVIEW");
        Map<String, Object> audit = jdbc.queryForMap("select before_data::text as b, after_data::text as a "
                + "from audit_log where entity_type = 'BOOK_EVALUATION' and action = 'UPDATE' and entity_id = ?",
                row.get("evaluation_id"));
        assertThat((String) audit.get("a")).contains("finalScore").contains("profileVersion")
                .doesNotContain("candidateStatus");
        assertThat((String) audit.get("b")).contains(profileVersion);
    }

    @Test
    @DisplayName("가중치가 0 인 평가축은 점수가 없어도 평가한다")
    void zeroWeightAxesNotRequired() throws Exception {
        saveSettings("50.00", "0.5", "0", "0.25", "0", "0.25");
        Long bookId = confirmedBook("JEV무관");
        em.flush();

        JsonNode data = data(evaluate(sessionId, librarian()).andExpect(status().isOk()));
        em.flush();

        assertThat(data.get("notEvaluable").asInt()).isZero();
        Map<String, Object> row = backend(bookId);
        assertThat(row.get("final_score")).hasToString("50.00");
        assertThat(row.get("candidate_status")).isEqualTo("DISPOSAL_REVIEW");
        assertThat((String) row.get("evaluation_reason")).contains("- 희소성 점수 없음, 가중치 0이라 제외");
    }

    // ---- 거부 ----

    @Test
    @DisplayName("점검이 확정되지 않은 회차는 409(EVAL_005), 없는 회차는 404, 사서가 아니면 403, 로그인하지 않으면 401 이다")
    void rejections() throws Exception {
        for (String status : List.of("IN_PROGRESS", "REVIEW")) {
            Long sid = session(status);
            observation(sid, 1, book(status), "NORMAL");
            assertError(evaluate(sid, librarian()), 409, "EVAL_005");
            assertThat(count("select count(*) from book_evaluation where scan_session_id = ?", sid)).isZero();
        }
        assertError(evaluate(999_999_999L, librarian()), 404, "SCAN_001");
        assertError(evaluate(sessionId, auth("ROLE_STUDENT_WORKER")), 403, "COMMON_005");
        assertThat(mockMvc.perform(post("/api/scan-sessions/" + sessionId + "/evaluations"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    // ---- helpers ----

    private ResultActions evaluate(Long sid, RequestPostProcessor auth) throws Exception {
        return mockMvc.perform(post("/api/scan-sessions/" + sid + "/evaluations").with(auth));
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

    private Map<String, Object> backend(Long bookId) {
        return jdbc.queryForMap("select * from book_evaluation where evaluation_type = 'BACKEND' "
                + "and scan_session_id = ? and book_id = ?", sessionId, bookId);
    }

    /** 평가축 순서: 수요성, 희소성, 중복성, 내용 유효성, 물리적 상태. 새로 활성화된 설정 버전을 돌려준다. */
    private String saveSettings(String threshold, String demand, String rarity, String duplication, String content,
                                String physical) {
        List<CriterionWeight> weights = new ArrayList<>();
        weights.add(new CriterionWeight(CriterionType.DEMAND, new BigDecimal(demand)));
        weights.add(new CriterionWeight(CriterionType.RARITY, new BigDecimal(rarity)));
        weights.add(new CriterionWeight(CriterionType.DUPLICATION, new BigDecimal(duplication)));
        weights.add(new CriterionWeight(CriterionType.CONTENT_VALIDITY, new BigDecimal(content)));
        weights.add(new CriterionWeight(CriterionType.PHYSICAL_CONDITION, new BigDecimal(physical)));
        return settingsService.update(new EvaluationSettingsUpdateRequest(new BigDecimal(threshold), weights), userId)
                .profileVersion();
    }

    private Long session(String status) {
        return jdbc.queryForObject("insert into scan_session (session_name, target_area, status, created_by, "
                + "created_at, updated_at) values ('종합 평가 테스트', ?, ?, ?, now(), now()) "
                + "returning scan_session_id", Long.class, prefix + "-Z", status, userId);
    }

    /** 대출·오손 기록이 없고 청구기호가 유일한 도서. */
    private Long book(String suffix) {
        return bookRepository.saveAndFlush(Book.builder()
                .registrationNo(prefix + "-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 6))
                .title(prefix + "-" + suffix)
                .callNumberRaw("x")
                .classificationNo("c" + UUID.randomUUID().toString().substring(0, 8))
                .bookSymbol("s" + UUID.randomUUID().toString().substring(0, 8))
                .lifecycleStatus(LifecycleStatus.NORMAL)
                .loanStatus(LoanStatus.AVAILABLE)
                .build()).getId();
    }

    private Long confirmedBook(String suffix) {
        Long bookId = book(suffix);
        observation(sessionId, (int) (Math.abs(bookId) % 100000), bookId, "NORMAL");
        return bookId;
    }

    private Long observation(Long sid, int order, Long bookId, String analysisStatus) {
        return jdbc.queryForObject("insert into book_observation (scan_session_id, book_id, detected_shelf_id, "
                + "detected_label_raw, detected_order, analysis_status, received_at) "
                + "values (?, ?, ?, ?, ?, ?, now()) returning observation_id", Long.class,
                sid, bookId, shelfId, "라벨-" + order, order, analysisStatus);
    }

    /** JEV 원본 결과(희소성·내용 유효성 정규화 점수). 평가 ID 를 돌려준다. */
    private Long jev(Long sid, Long bookId, String rarity, String content) {
        Long evaluationId = jdbc.queryForObject("insert into book_evaluation (book_id, scan_session_id, "
                + "evaluation_type, model_version, candidate_status, evaluated_at) "
                + "values (?, ?, 'JEV', 'jev-1.14.0', 'UNDECIDED', now()) returning evaluation_id", Long.class,
                bookId, sid);
        criterion(evaluationId, "RARITY", rarity);
        criterion(evaluationId, "CONTENT_VALIDITY", content);
        return evaluationId;
    }

    private void criterion(Long evaluationId, String type, String normalized) {
        jdbc.update("insert into evaluation_criterion_result (evaluation_id, criterion_type, evaluator_type, "
                + "model_version, raw_score, normalized_score, confidence, selected_grade, probability_distribution) "
                + "values (?, ?, 'JEV', 'jev-1.14.0', 2.00, ?, 0.7800, 3, '{\"3\":0.45}'::jsonb)",
                evaluationId, type, new BigDecimal(normalized));
    }
}
