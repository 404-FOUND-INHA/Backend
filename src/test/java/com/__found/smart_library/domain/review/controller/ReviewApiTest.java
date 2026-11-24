package com.__found.smart_library.domain.review.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.__found.smart_library.domain.book.entity.Book;
import com.__found.smart_library.domain.book.entity.LifecycleStatus;
import com.__found.smart_library.domain.book.entity.LoanStatus;
import com.__found.smart_library.domain.book.repository.BookRepository;
import com.__found.smart_library.domain.scan.entity.ScanSession;
import com.__found.smart_library.domain.scan.repository.ScanSessionRepository;
import com.__found.smart_library.domain.shelf.entity.Shelf;
import com.__found.smart_library.domain.shelf.repository.ShelfRepository;
import com.__found.smart_library.domain.user.entity.AppUser;
import com.__found.smart_library.domain.user.entity.UserRole;
import com.__found.smart_library.domain.user.repository.AppUserRepository;
import jakarta.persistence.EntityManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 사서 검토 API 테스트(롤백). 동시 확정은 {@link ReviewConcurrencyTest} 에서 실제 커밋으로 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ReviewApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired AppUserRepository userRepository;
    @Autowired ShelfRepository shelfRepository;
    @Autowired BookRepository bookRepository;
    @Autowired ScanSessionRepository scanSessionRepository;

    private final String prefix = "TR" + UUID.randomUUID().toString().substring(0, 8);
    private Long userId;
    private Long sessionId;
    private Shelf shelfA;
    private Shelf shelfB;

    @BeforeEach
    void setUp() {
        userId = saveUser(UserRole.LIBRARIAN, "사서테스터");
        shelfA = saveShelf("A");
        shelfB = saveShelf("B");
        sessionId = saveSession(true);
    }

    // ---- 저장: 최종 상태 계산 ----

    @Test
    @DisplayName("도서를 확정했고 예상 서가가 관측 서가와 같으면 NORMAL 이고 검토·감사 기록이 남는다")
    void identify_expectedShelfMatches_normal() throws Exception {
        Long bookId = saveBook(shelfA);
        Long obsId = observation(sessionId, shelfA, 1, null, "REVIEW_REQUIRED", "NO_MATCH");

        JsonNode data = data(save(sessionId, obsId, librarian(), identify(bookId, "  직접 확인함  "))
                .andExpect(status().isOk()));

        assertThat(data.get("reviewId").asLong()).isPositive();
        assertThat(data.get("action").asString()).isEqualTo("IDENTIFY_BOOK");
        assertThat(data.get("finalStatus").asString()).isEqualTo("NORMAL");
        assertThat(data.get("finalBook").get("bookId").asLong()).isEqualTo(bookId);
        assertThat(data.get("comment").asString()).isEqualTo("직접 확인함");
        assertThat(data.get("reviewerId").asLong()).isEqualTo(userId);
        assertThat(data.get("reviewerName").asString()).isEqualTo("사서테스터");
        assertThat(data.get("reviewedAt").isNull()).isFalse();

        em.flush();
        Map<String, Object> row = jdbc.queryForMap("select * from review where observation_id = ?", obsId);
        assertThat(row.get("final_book_id")).isEqualTo(bookId);
        assertThat(row.get("final_status")).isEqualTo("NORMAL");
        assertThat(row.get("review_action")).isEqualTo("IDENTIFY_BOOK");
        assertThat(row.get("reviewer_id")).isEqualTo(userId);
        assertThat(jdbc.queryForObject("select action from audit_log where entity_type = 'REVIEW' and entity_id = ?",
                String.class, data.get("reviewId").asLong())).isEqualTo("CREATE");
        // 자동 분석 결과는 바뀌지 않는다
        assertThat(jdbc.queryForObject("select analysis_status from book_observation where observation_id = ?",
                String.class, obsId)).isEqualTo("REVIEW_REQUIRED");
    }

    @Test
    @DisplayName("예상 서가가 다르거나 지정되어 있지 않으면 MISPLACED 이다")
    void identify_expectedShelfDiffersOrMissing_misplaced() throws Exception {
        Long otherShelfBook = saveBook(shelfB);
        Long noShelfBook = saveBook(null);
        Long obs1 = observation(sessionId, shelfA, 1, null, "REVIEW_REQUIRED", "NO_MATCH");
        Long obs2 = observation(sessionId, shelfA, 2, null, "REVIEW_REQUIRED", "NO_MATCH");

        assertThat(data(save(sessionId, obs1, librarian(), identify(otherShelfBook, null))
                .andExpect(status().isOk())).get("finalStatus").asString()).isEqualTo("MISPLACED");
        assertThat(data(save(sessionId, obs2, librarian(), identify(noShelfBook, null))
                .andExpect(status().isOk())).get("finalStatus").asString()).isEqualTo("MISPLACED");
    }

    @Test
    @DisplayName("식별 불가·제외 조치는 도서 없이 저장되고 빈 의견은 null 로 저장된다")
    void unidentifiedAndExclude_noBook() throws Exception {
        Long obs1 = observation(sessionId, shelfA, 1, null, "REVIEW_REQUIRED", "OCR_FAILED");
        Long obs2 = observation(sessionId, shelfA, 2, null, "REVIEW_REQUIRED", "OCR_FAILED");

        JsonNode unidentified = data(save(sessionId, obs1, librarian(), body("MARK_UNIDENTIFIED", null, "   "))
                .andExpect(status().isOk()));
        JsonNode excluded = data(save(sessionId, obs2, librarian(), body("EXCLUDE", null, "칸막이"))
                .andExpect(status().isOk()));

        assertThat(unidentified.get("finalStatus").asString()).isEqualTo("UNIDENTIFIED");
        assertThat(unidentified.get("finalBook").isNull()).isTrue();
        assertThat(unidentified.get("comment").isNull()).isTrue();
        assertThat(excluded.get("finalStatus").asString()).isEqualTo("EXCLUDED");
        assertThat(excluded.get("comment").asString()).isEqualTo("칸막이");
    }

    @Test
    @DisplayName("다시 검토하면 같은 행을 덮어쓰고 감사 로그에 이전 값이 남는다")
    void reReview_overwritesSingleRow() throws Exception {
        Long bookId = saveBook(shelfA);
        Long obsId = observation(sessionId, shelfA, 1, null, "REVIEW_REQUIRED", "NO_MATCH");

        JsonNode first = data(save(sessionId, obsId, librarian(), body("MARK_UNIDENTIFIED", null, null))
                .andExpect(status().isOk()));
        JsonNode second = data(save(sessionId, obsId, librarian(), identify(bookId, "다시 보니 이 책"))
                .andExpect(status().isOk()));
        em.flush();

        assertThat(second.get("reviewId").asLong()).isEqualTo(first.get("reviewId").asLong());
        assertThat(second.get("finalStatus").asString()).isEqualTo("NORMAL");
        assertThat(count("select count(*) from review where observation_id = ?", obsId)).isEqualTo(1);
        List<Map<String, Object>> audits = jdbc.queryForList(
                "select action, before_data::text as b, after_data::text as a from audit_log "
                        + "where entity_type = 'REVIEW' and entity_id = ? order by audit_log_id",
                first.get("reviewId").asLong());
        assertThat(audits).extracting(m -> m.get("action")).containsExactly("CREATE", "UPDATE");
        assertThat((String) audits.get(1).get("b")).contains("MARK_UNIDENTIFIED");
        assertThat((String) audits.get(1).get("a")).contains("IDENTIFY_BOOK");
    }

    // ---- 저장: 검증과 거부 ----

    @Test
    @DisplayName("조치와 도서 지정이 맞지 않거나 값이 잘못되면 400이고 저장되지 않는다")
    void save_invalidRequest_returns400() throws Exception {
        Long bookId = saveBook(shelfA);
        Long obsId = observation(sessionId, shelfA, 1, null, "REVIEW_REQUIRED", "NO_MATCH");

        save(sessionId, obsId, librarian(), body("IDENTIFY_BOOK", null, null)).andExpect(status().isBadRequest());
        save(sessionId, obsId, librarian(), body("EXCLUDE", bookId, null)).andExpect(status().isBadRequest());
        save(sessionId, obsId, librarian(), body("MARK_UNIDENTIFIED", bookId, null)).andExpect(status().isBadRequest());
        save(sessionId, obsId, librarian(), body(null, null, null)).andExpect(status().isBadRequest());
        save(sessionId, obsId, librarian(), body("DESTROY", null, null)).andExpect(status().isBadRequest());
        save(sessionId, obsId, librarian(), body("EXCLUDE", null, "가".repeat(1001))).andExpect(status().isBadRequest());

        assertThat(reviewCount()).isZero();
        save(sessionId, obsId, librarian(), body("EXCLUDE", null, "가".repeat(1000))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("없는 도서는 404(BOOK_001), 없는 관측·다른 회차의 관측은 404(SCAN_008), 없는 회차는 404(SCAN_001)")
    void save_notFound_returns404() throws Exception {
        Long obsId = observation(sessionId, shelfA, 1, null, "REVIEW_REQUIRED", "NO_MATCH");
        Long otherSession = saveSession(true);
        Long otherObs = observation(otherSession, shelfA, 1, null, "REVIEW_REQUIRED", "NO_MATCH");

        assertError(save(sessionId, obsId, librarian(), identify(Long.MAX_VALUE, null)), 404, "BOOK_001");
        assertError(save(sessionId, Long.MAX_VALUE, librarian(), body("EXCLUDE", null, null)), 404, "SCAN_008");
        assertError(save(sessionId, otherObs, librarian(), body("EXCLUDE", null, null)), 404, "SCAN_008");
        assertError(save(Long.MAX_VALUE, obsId, librarian(), body("EXCLUDE", null, null)), 404, "SCAN_001");
        assertThat(reviewCount()).isZero();
    }

    @Test
    @DisplayName("자동 분석이 이미 정상·오배치로 판정한 관측은 검토할 수 없다 (409 REVIEW_003)")
    void save_notReviewRequired_returns409() throws Exception {
        Long normalObs = observation(sessionId, shelfA, 1, saveBook(shelfA), "NORMAL", null);
        Long misplacedObs = observation(sessionId, shelfA, 2, saveBook(shelfB), "MISPLACED", "WRONG_SHELF");

        assertError(save(sessionId, normalObs, librarian(), body("EXCLUDE", null, null)), 409, "REVIEW_003");
        assertError(save(sessionId, misplacedObs, librarian(), body("EXCLUDE", null, null)), 409, "REVIEW_003");
        assertThat(reviewCount()).isZero();
    }

    @Test
    @DisplayName("회차가 REVIEW 상태가 아니면(수신 전·확정 후) 저장·취소할 수 없다 (409 REVIEW_002)")
    void save_sessionNotInReview_returns409() throws Exception {
        Long waiting = saveSession(false);
        Long waitingObs = observation(waiting, shelfA, 1, null, "REVIEW_REQUIRED", "NO_MATCH");
        Long completed = saveSession(true);
        Long completedObs = observation(completed, shelfA, 1, null, "REVIEW_REQUIRED", "NO_MATCH");
        em.flush();
        jdbc.update("update scan_session set status = 'COMPLETED' where scan_session_id = ?", completed);
        em.clear();

        assertError(save(waiting, waitingObs, librarian(), body("EXCLUDE", null, null)), 409, "REVIEW_002");
        assertError(save(completed, completedObs, librarian(), body("EXCLUDE", null, null)), 409, "REVIEW_002");
        assertError(remove(completed, completedObs, librarian()), 409, "REVIEW_002");
        assertThat(reviewCount()).isZero();
    }

    @Test
    @DisplayName("같은 회차에서 이미 다른 관측에 확정된 도서는 확정할 수 없다 (검토·자동 판정 모두), 취소하면 다시 가능하다")
    void identify_bookAlreadyAssigned_returns409() throws Exception {
        Long bookId = saveBook(shelfA);
        Long resolvedBookId = saveBook(shelfA);
        Long obs1 = observation(sessionId, shelfA, 1, null, "REVIEW_REQUIRED", "NO_MATCH");
        Long obs2 = observation(sessionId, shelfA, 2, null, "REVIEW_REQUIRED", "NO_MATCH");
        observation(sessionId, shelfA, 3, resolvedBookId, "NORMAL", null);

        save(sessionId, obs1, librarian(), identify(bookId, null)).andExpect(status().isOk());
        assertError(save(sessionId, obs2, librarian(), identify(bookId, null)), 409, "REVIEW_004");
        assertError(save(sessionId, obs2, librarian(), identify(resolvedBookId, null)), 409, "REVIEW_004");
        // 같은 관측을 같은 도서로 다시 저장하는 것은 충돌이 아니다
        save(sessionId, obs1, librarian(), identify(bookId, "재확인")).andExpect(status().isOk());

        remove(sessionId, obs1, librarian()).andExpect(status().isOk());
        save(sessionId, obs2, librarian(), identify(bookId, null)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("다른 회차에서 확정한 도서는 이 회차에서 다시 확정할 수 있다")
    void identify_sameBookInOtherSession_allowed() throws Exception {
        Long bookId = saveBook(shelfA);
        Long otherSession = saveSession(true);
        Long otherObs = observation(otherSession, shelfA, 1, null, "REVIEW_REQUIRED", "NO_MATCH");
        Long obs = observation(sessionId, shelfA, 1, null, "REVIEW_REQUIRED", "NO_MATCH");

        save(otherSession, otherObs, librarian(), identify(bookId, null)).andExpect(status().isOk());
        save(sessionId, obs, librarian(), identify(bookId, null)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("저장·취소는 사서만 가능하다: 근로학생 403, 미인증 401이고 아무것도 바뀌지 않는다")
    void write_byRole_librarianOnly() throws Exception {
        Long obsId = observation(sessionId, shelfA, 1, null, "REVIEW_REQUIRED", "NO_MATCH");

        save(sessionId, obsId, auth("ROLE_STUDENT_WORKER"), body("EXCLUDE", null, null))
                .andExpect(status().isForbidden());
        mockMvc.perform(put(reviewUrl(sessionId, obsId)).contentType(MediaType.APPLICATION_JSON)
                .content(body("EXCLUDE", null, null))).andExpect(status().isUnauthorized());
        assertThat(reviewCount()).isZero();

        save(sessionId, obsId, librarian(), body("EXCLUDE", null, null)).andExpect(status().isOk());
        remove(sessionId, obsId, auth("ROLE_STUDENT_WORKER")).andExpect(status().isForbidden());
        mockMvc.perform(delete(reviewUrl(sessionId, obsId))).andExpect(status().isUnauthorized());
        assertThat(reviewCount()).isEqualTo(1);
    }

    // ---- 취소 ----

    @Test
    @DisplayName("검토를 취소하면 행이 지워지고 감사 로그(DELETE)가 남으며, 없는 검토를 취소하면 404(REVIEW_001)")
    void delete_removesReviewAndAudits() throws Exception {
        Long obsId = observation(sessionId, shelfA, 1, null, "REVIEW_REQUIRED", "NO_MATCH");
        Long reviewId = data(save(sessionId, obsId, librarian(), body("EXCLUDE", null, "오검출"))
                .andExpect(status().isOk())).get("reviewId").asLong();

        remove(sessionId, obsId, librarian()).andExpect(status().isOk());
        em.flush();

        assertThat(reviewCount()).isZero();
        assertThat(jdbc.queryForObject("select before_data::text from audit_log where entity_type = 'REVIEW' "
                + "and entity_id = ? and action = 'DELETE'", String.class, reviewId)).contains("EXCLUDE");
        assertError(remove(sessionId, obsId, librarian()), 404, "REVIEW_001");
        assertError(remove(sessionId, Long.MAX_VALUE, librarian()), 404, "SCAN_008");
    }

    // ---- 목록 ----

    @Test
    @DisplayName("검토 필요 관측만 서가·순서대로 나오고, 요약은 필터와 무관하며 검토 결과가 함께 담긴다")
    void list_returnsOnlyReviewRequiredWithSummaryAndReview() throws Exception {
        Long matched = saveBook(shelfA);
        Long chosen = saveBook(shelfA);
        Long obs1 = observation(sessionId, shelfA, 1, matched, "REVIEW_REQUIRED", "NO_EXPECTED_SHELF");
        Long obs2 = observation(sessionId, shelfA, 2, null, "REVIEW_REQUIRED", "OCR_FAILED");
        observation(sessionId, shelfA, 3, saveBook(shelfA), "NORMAL", null);
        Long obs4 = observation(sessionId, shelfB, 1, null, "REVIEW_REQUIRED", "AMBIGUOUS_MATCH");
        Long otherSession = saveSession(true);
        observation(otherSession, shelfA, 1, null, "REVIEW_REQUIRED", "NO_MATCH");
        jdbc.update("insert into observation_match_candidate (observation_id, candidate_rank, book_id, match_reason) "
                + "values (?, 1, ?, 'EXACT_MATCH'), (?, 2, ?, 'COPY_MISMATCH')", obs4, chosen, obs4, matched);
        save(sessionId, obs2, librarian(), identify(chosen, "확인")).andExpect(status().isOk());
        em.flush();

        JsonNode all = data(list(sessionId, "", auth("ROLE_STUDENT_WORKER")).andExpect(status().isOk()));

        assertThat(all.get("summary").get("totalRequired").asLong()).isEqualTo(3);
        assertThat(all.get("summary").get("reviewed").asLong()).isEqualTo(1);
        assertThat(all.get("summary").get("remaining").asLong()).isEqualTo(2);
        JsonNode items = all.get("items").get("content");
        assertThat(all.get("items").get("totalElements").asLong()).isEqualTo(3);
        assertThat(ids(items)).containsExactly(obs1, obs2, obs4);   // 서가 A(순서 1, 2) → 서가 B
        assertThat(items.get(0).get("autoMatchedBook").get("bookId").asLong()).isEqualTo(matched);
        assertThat(items.get(0).get("review").isNull()).isTrue();
        assertThat(items.get(0).get("statusReason").asString()).isEqualTo("NO_EXPECTED_SHELF");
        assertThat(items.get(1).get("autoMatchedBook").isNull()).isTrue();
        assertThat(items.get(1).get("review").get("finalStatus").asString()).isEqualTo("NORMAL");
        assertThat(items.get(1).get("review").get("finalBook").get("bookId").asLong()).isEqualTo(chosen);
        assertThat(items.get(1).get("review").get("reviewerName").asString()).isEqualTo("사서테스터");
        assertThat(items.get(2).get("candidateCount").asLong()).isEqualTo(2);
        assertThat(items.get(2).get("shelfCode").asString()).isEqualTo(shelfB.getShelfCode());
    }

    @Test
    @DisplayName("서가·검토 여부 필터와 페이징이 적용되고 요약은 그대로이다")
    void list_filtersAndPaging() throws Exception {
        Long obs1 = observation(sessionId, shelfA, 1, null, "REVIEW_REQUIRED", "NO_MATCH");
        Long obs2 = observation(sessionId, shelfA, 2, null, "REVIEW_REQUIRED", "NO_MATCH");
        Long obs3 = observation(sessionId, shelfB, 1, null, "REVIEW_REQUIRED", "NO_MATCH");
        save(sessionId, obs1, librarian(), body("EXCLUDE", null, null)).andExpect(status().isOk());
        em.flush();

        JsonNode reviewed = data(list(sessionId, "?reviewed=true", librarian()).andExpect(status().isOk()));
        JsonNode pending = data(list(sessionId, "?reviewed=false", librarian()).andExpect(status().isOk()));
        JsonNode shelfOnly = data(list(sessionId, "?shelfId=" + shelfB.getId(), librarian())
                .andExpect(status().isOk()));
        JsonNode pagePending = data(list(sessionId, "?reviewed=false&size=1&page=1", librarian())
                .andExpect(status().isOk()));

        assertThat(ids(reviewed.get("items").get("content"))).containsExactly(obs1);
        assertThat(ids(pending.get("items").get("content"))).containsExactly(obs2, obs3);
        assertThat(ids(shelfOnly.get("items").get("content"))).containsExactly(obs3);
        assertThat(ids(pagePending.get("items").get("content"))).containsExactly(obs3);
        assertThat(pagePending.get("items").get("totalElements").asLong()).isEqualTo(2);
        assertThat(pagePending.get("items").get("totalPages").asInt()).isEqualTo(2);
        assertThat(shelfOnly.get("summary").get("totalRequired").asLong()).isEqualTo(3);
        assertThat(shelfOnly.get("summary").get("reviewed").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("관측이 없으면 빈 목록, 없는 회차는 404, 미인증은 401")
    void list_emptyUnknownSessionAnonymous() throws Exception {
        JsonNode empty = data(list(sessionId, "", librarian()).andExpect(status().isOk()));
        assertThat(empty.get("items").get("content")).isEmpty();
        assertThat(empty.get("summary").get("totalRequired").asLong()).isZero();

        assertError(list(Long.MAX_VALUE, "", librarian()), 404, "SCAN_001");
        mockMvc.perform(get("/api/scan-sessions/" + sessionId + "/reviews")).andExpect(status().isUnauthorized());
    }

    // ---- helpers ----

    private ResultActions save(Long sid, Long oid, RequestPostProcessor auth, String json) throws Exception {
        return mockMvc.perform(put(reviewUrl(sid, oid)).with(auth).contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private ResultActions remove(Long sid, Long oid, RequestPostProcessor auth) throws Exception {
        return mockMvc.perform(delete(reviewUrl(sid, oid)).with(auth));
    }

    private ResultActions list(Long sid, String query, RequestPostProcessor auth) throws Exception {
        return mockMvc.perform(get("/api/scan-sessions/" + sid + "/reviews" + query).with(auth));
    }

    private static String reviewUrl(Long sid, Long oid) {
        return "/api/scan-sessions/" + sid + "/observations/" + oid + "/review";
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

    private static List<Long> ids(JsonNode items) {
        List<Long> out = new java.util.ArrayList<>();
        items.forEach(n -> out.add(n.get("observationId").asLong()));
        return out;
    }

    private String identify(Long bookId, String comment) throws Exception {
        return body("IDENTIFY_BOOK", bookId, comment);
    }

    private String body(String action, Long bookId, String comment) throws Exception {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("action", action);
        map.put("finalBookId", bookId);
        map.put("comment", comment);
        return objectMapper.writeValueAsString(map);
    }

    private long reviewCount() {
        em.flush();
        return count("select count(*) from review r join book_observation o on o.observation_id = r.observation_id "
                + "where o.scan_session_id in (select scan_session_id from scan_session where target_area like ?)",
                prefix + "%");
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private Long saveUser(UserRole role, String name) {
        return userRepository.saveAndFlush(AppUser.builder()
                .email("review-" + UUID.randomUUID() + "@test.com")
                .passwordHash("hash")
                .name(name)
                .role(role)
                .build()).getId();
    }

    private Shelf saveShelf(String suffix) {
        Long id = jdbc.queryForObject("insert into shelf (shelf_code, zone, floor_no, is_active, created_at, "
                + "updated_at) values (?, ?, 1, true, now(), now()) returning shelf_id", Long.class,
                prefix + "-" + suffix, prefix + "-Z");
        return shelfRepository.findById(id).orElseThrow();
    }

    /** reviewReady 가 true 면 REVIEW 상태(검토 가능), false 면 IN_PROGRESS(수신 전). */
    private Long saveSession(boolean reviewReady) {
        ScanSession session = ScanSession.create("검토 테스트", prefix + "-Z", null, userId);
        if (reviewReady) {
            session.markReviewReady();
        }
        return scanSessionRepository.saveAndFlush(session).getId();
    }

    private Long saveBook(Shelf expectedShelf) {
        return bookRepository.saveAndFlush(Book.builder()
                .registrationNo(prefix + "-" + UUID.randomUUID().toString().substring(0, 8))
                .title("테스트 도서")
                .callNumberRaw("005.74 김12ㄷ")
                .classificationNo("005.74")
                .bookSymbol("김12ㄷ")
                .expectedShelf(expectedShelf)
                .lifecycleStatus(LifecycleStatus.NORMAL)
                .loanStatus(LoanStatus.AVAILABLE)
                .build()).getId();
    }

    private Long observation(Long sid, Shelf shelf, int order, Long bookId, String analysisStatus, String reason) {
        return jdbc.queryForObject("insert into book_observation (scan_session_id, book_id, detected_shelf_id, "
                        + "detected_label_raw, detected_order, analysis_status, status_reason, received_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, now()) returning observation_id", Long.class,
                sid, bookId, shelf.getId(), "라벨-" + order, order, analysisStatus, reason);
    }
}
