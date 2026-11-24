package com.__found.smart_library.domain.scan.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

/** 회차 확정 API 테스트(롤백). 동시 확정은 {@link ScanFinalizeConcurrencyTest} 에서 실제 커밋으로 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ScanFinalizeTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired AppUserRepository userRepository;
    @Autowired ShelfRepository shelfRepository;
    @Autowired BookRepository bookRepository;
    @Autowired ScanSessionRepository scanSessionRepository;

    private final String prefix = "TF" + UUID.randomUUID().toString().substring(0, 8);
    private Long userId;
    private Long sessionId;
    private Shelf shelfA;
    private Shelf shelfB;

    @BeforeEach
    void setUp() {
        userId = userRepository.saveAndFlush(AppUser.builder()
                .email("finalize-" + UUID.randomUUID() + "@test.com")
                .passwordHash("hash")
                .name("사서테스터")
                .role(UserRole.LIBRARIAN)
                .build()).getId();
        shelfA = saveShelf("A");
        shelfB = saveShelf("B");
        sessionId = saveSession(true);
    }

    // ---- 확정과 BOOK 반영 ----

    @Test
    @DisplayName("확정하면 위치가 확인된 도서만 BOOK 에 반영되고 회차는 COMPLETED 가 된다")
    void finalize_reflectsFoundBooksOnly() throws Exception {
        // 자동 정상: 현재 서가가 비어 있던 도서 -> 관측 서가로 기록
        Book autoNormal = saveBook(shelfA, null, LifecycleStatus.NORMAL);
        // 자동 오배치: 정상 배치 서가는 그대로 두고 현재 서가만 관측 서가로
        Book autoMisplaced = saveBook(shelfA, shelfA, LifecycleStatus.NORMAL);
        // 이미 그 서가에 있던 도서: 바뀐 것이 없으므로 이력도 남지 않는다
        Book unchanged = saveBook(shelfA, shelfA, LifecycleStatus.NORMAL);
        // 소재불명이었다가 찾은 도서 -> NORMAL 로 복구
        Book wasMissing = saveBook(shelfA, shelfB, LifecycleStatus.MISSING);
        // 훼손 도서는 위치만 갱신하고 상태는 유지
        Book damaged = saveBook(shelfA, shelfB, LifecycleStatus.DAMAGED);
        // 사서가 도서를 확정한 관측(검토 필요였던 것). 제적 도서도 위치만 갱신하고 제적 상태는 유지
        Book reviewedBook = saveBook(shelfA, null, LifecycleStatus.NORMAL);
        Book disposed = saveBook(shelfA, null, LifecycleStatus.DISPOSED);
        // 점검에서 보지 못한 도서는 건드리지 않는다
        Book untouched = saveBook(shelfA, shelfB, LifecycleStatus.NORMAL);

        observation(shelfA, 1, autoNormal.getId(), "NORMAL", null);
        observation(shelfB, 1, autoMisplaced.getId(), "MISPLACED", "WRONG_SHELF");
        observation(shelfA, 2, unchanged.getId(), "NORMAL", null);
        observation(shelfA, 3, wasMissing.getId(), "NORMAL", null);
        observation(shelfA, 4, damaged.getId(), "NORMAL", null);
        Long obsReviewed = observation(shelfA, 5, null, "REVIEW_REQUIRED", "NO_MATCH");
        Long obsDisposed = observation(shelfA, 6, disposed.getId(), "REVIEW_REQUIRED", "DISPOSED_BOOK");
        Long obsUnidentified = observation(shelfA, 7, null, "REVIEW_REQUIRED", "OCR_FAILED");
        Long obsExcluded = observation(shelfA, 8, null, "REVIEW_REQUIRED", "OCR_FAILED");
        review(obsReviewed, "IDENTIFY_BOOK", reviewedBook.getId(), "NORMAL");
        review(obsDisposed, "IDENTIFY_BOOK", disposed.getId(), "NORMAL");
        review(obsUnidentified, "MARK_UNIDENTIFIED", null, "UNIDENTIFIED");
        review(obsExcluded, "EXCLUDE", null, "EXCLUDED");
        em.flush();

        JsonNode data = data(finalizeSession(sessionId, librarian()).andExpect(status().isOk()));
        em.flush();
        em.clear();

        assertThat(data.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(data.get("finalizedBy").asLong()).isEqualTo(userId);
        assertThat(data.get("finalizedAt").isNull()).isFalse();
        JsonNode summary = data.get("summary");
        assertThat(summary.get("confirmedBooks").asLong()).isEqualTo(7);
        assertThat(summary.get("locationChanged").asLong()).isEqualTo(6);   // unchanged 만 제자리
        assertThat(summary.get("restoredFromMissing").asLong()).isEqualTo(1);
        assertThat(summary.get("unidentified").asLong()).isEqualTo(1);
        assertThat(summary.get("excluded").asLong()).isEqualTo(1);

        assertThat(currentShelf(autoNormal)).isEqualTo(shelfA.getId());
        assertThat(currentShelf(autoMisplaced)).isEqualTo(shelfB.getId());
        assertThat(expectedShelf(autoMisplaced)).isEqualTo(shelfA.getId());   // 정상 배치 서가는 그대로
        assertThat(currentShelf(unchanged)).isEqualTo(shelfA.getId());
        assertThat(currentShelf(wasMissing)).isEqualTo(shelfA.getId());
        assertThat(lifecycle(wasMissing)).isEqualTo("NORMAL");
        assertThat(currentShelf(damaged)).isEqualTo(shelfA.getId());
        assertThat(lifecycle(damaged)).isEqualTo("DAMAGED");
        assertThat(currentShelf(reviewedBook)).isEqualTo(shelfA.getId());
        assertThat(currentShelf(disposed)).isEqualTo(shelfA.getId());
        assertThat(lifecycle(disposed)).isEqualTo("DISPOSED");
        assertThat(currentShelf(untouched)).isEqualTo(shelfB.getId());

        Map<String, Object> session = jdbc.queryForMap(
                "select status, finalized_by, finalized_at from scan_session where scan_session_id = ?", sessionId);
        assertThat(session.get("status")).isEqualTo("COMPLETED");
        assertThat(session.get("finalized_by")).isEqualTo(userId);
        assertThat(session.get("finalized_at")).isNotNull();
        // 자동 분석 결과와 검토 결과는 그대로
        assertThat(jdbc.queryForObject("select count(*) from review r join book_observation o "
                + "on o.observation_id = r.observation_id where o.scan_session_id = ?", Long.class, sessionId))
                .isEqualTo(4);
    }

    @Test
    @DisplayName("바뀐 도서만 변경 필드와 함께 audit_log 에 남는다 (위치만 바뀌면 UPDATE, 상태가 바뀌면 STATUS_CHANGE)")
    void finalize_writesBookAuditOnlyForChanges() throws Exception {
        Book moved = saveBook(shelfA, shelfB, LifecycleStatus.NORMAL);
        Book restored = saveBook(shelfA, shelfB, LifecycleStatus.MISSING);
        Book same = saveBook(shelfA, shelfA, LifecycleStatus.NORMAL);
        observation(shelfA, 1, moved.getId(), "NORMAL", null);
        observation(shelfA, 2, restored.getId(), "NORMAL", null);
        observation(shelfA, 3, same.getId(), "NORMAL", null);
        em.flush();

        finalizeSession(sessionId, librarian()).andExpect(status().isOk());
        em.flush();

        Map<String, Object> movedLog = jdbc.queryForMap("select action, actor_id, before_data::text as b, "
                + "after_data::text as a from audit_log where entity_type = 'BOOK' and entity_id = ?", moved.getId());
        assertThat(movedLog.get("action")).isEqualTo("UPDATE");
        assertThat(movedLog.get("actor_id")).isEqualTo(userId);
        assertThat((String) movedLog.get("b")).contains("\"currentShelfId\"").contains(shelfB.getShelfCode())
                .doesNotContain("lifecycleStatus");
        assertThat((String) movedLog.get("a")).contains(shelfA.getShelfCode());

        Map<String, Object> restoredLog = jdbc.queryForMap("select action, before_data::text as b, "
                + "after_data::text as a from audit_log where entity_type = 'BOOK' and entity_id = ?",
                restored.getId());
        assertThat(restoredLog.get("action")).isEqualTo("STATUS_CHANGE");
        assertThat((String) restoredLog.get("b")).contains("MISSING");
        assertThat((String) restoredLog.get("a")).contains("NORMAL");

        assertThat(jdbc.queryForObject("select count(*) from audit_log where entity_type = 'BOOK' "
                + "and entity_id = ?", Long.class, same.getId())).isZero();
    }

    @Test
    @DisplayName("관측이 하나도 없는 회차도 확정할 수 있다")
    void finalize_noObservations_succeeds() throws Exception {
        JsonNode data = data(finalizeSession(sessionId, librarian()).andExpect(status().isOk()));

        assertThat(data.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(data.get("summary").get("confirmedBooks").asLong()).isZero();
    }

    // ---- 거부 ----

    @Test
    @DisplayName("검토가 끝나지 않은 관측이 있으면 409(SCAN_010)이고 BOOK 도 회차도 바뀌지 않는다")
    void finalize_reviewIncomplete_returns409AndChangesNothing() throws Exception {
        Book book = saveBook(shelfA, shelfB, LifecycleStatus.NORMAL);
        observation(shelfA, 1, book.getId(), "NORMAL", null);
        Long reviewed = observation(shelfA, 2, null, "REVIEW_REQUIRED", "NO_MATCH");
        observation(shelfA, 3, null, "REVIEW_REQUIRED", "OCR_FAILED");
        observation(shelfA, 4, null, "REVIEW_REQUIRED", "OCR_FAILED");
        review(reviewed, "EXCLUDE", null, "EXCLUDED");
        em.flush();

        ResultActions result = finalizeSession(sessionId, librarian());

        assertError(result, 409, "SCAN_010", "2건");
        em.flush();
        em.clear();
        assertThat(currentShelf(book)).isEqualTo(shelfB.getId());
        assertThat(jdbc.queryForObject("select status from scan_session where scan_session_id = ?", String.class,
                sessionId)).isEqualTo("REVIEW");
        assertThat(jdbc.queryForObject("select count(*) from audit_log where entity_type = 'BOOK' and entity_id = ?",
                Long.class, book.getId())).isZero();
    }

    @Test
    @DisplayName("검토를 모두 마치면(취소했다가 다시 검토 포함) 확정할 수 있다")
    void finalize_afterCompletingReview_succeeds() throws Exception {
        Long obs = observation(shelfA, 1, null, "REVIEW_REQUIRED", "OCR_FAILED");
        em.flush();
        assertError(finalizeSession(sessionId, librarian()), 409, "SCAN_010", "1건");

        mockMvc.perform(put("/api/scan-sessions/" + sessionId + "/observations/" + obs + "/review")
                        .with(librarian()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"EXCLUDE\"}"))
                .andExpect(status().isOk());

        finalizeSession(sessionId, librarian()).andExpect(status().isOk());
    }

    @Test
    @DisplayName("수신 전(IN_PROGRESS)·이미 확정된 회차는 409(SCAN_009), 없는 회차는 404(SCAN_001)")
    void finalize_wrongState_returns409() throws Exception {
        Long waiting = saveSession(false);

        assertError(finalizeSession(waiting, librarian()), 409, "SCAN_009", null);
        finalizeSession(sessionId, librarian()).andExpect(status().isOk());
        assertError(finalizeSession(sessionId, librarian()), 409, "SCAN_009", null);
        assertError(finalizeSession(Long.MAX_VALUE, librarian()), 404, "SCAN_001", null);
    }

    @Test
    @DisplayName("확정한 뒤에는 검토를 바꿀 수 없다 (409 REVIEW_002)")
    void finalize_thenReviewBlocked() throws Exception {
        Long obs = observation(shelfA, 1, null, "REVIEW_REQUIRED", "OCR_FAILED");
        em.flush();
        mockMvc.perform(put("/api/scan-sessions/" + sessionId + "/observations/" + obs + "/review")
                .with(librarian()).contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"EXCLUDE\"}"))
                .andExpect(status().isOk());
        finalizeSession(sessionId, librarian()).andExpect(status().isOk());

        assertError(mockMvc.perform(put("/api/scan-sessions/" + sessionId + "/observations/" + obs + "/review")
                .with(librarian()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"MARK_UNIDENTIFIED\"}")), 409, "REVIEW_002", null);
        assertError(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/scan-sessions/" + sessionId + "/observations/" + obs + "/review").with(librarian())),
                409, "REVIEW_002", null);
    }

    @Test
    @DisplayName("확정은 사서만 가능하다: 근로학생 403, 미인증 401이고 아무것도 바뀌지 않는다")
    void finalize_byRole_librarianOnly() throws Exception {
        finalizeSession(sessionId, auth("ROLE_STUDENT_WORKER")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/scan-sessions/" + sessionId + "/finalize")).andExpect(status().isUnauthorized());
        em.flush();
        assertThat(jdbc.queryForObject("select status from scan_session where scan_session_id = ?", String.class,
                sessionId)).isEqualTo("REVIEW");

        finalizeSession(sessionId, librarian()).andExpect(status().isOk());
    }

    // ---- helpers ----

    private ResultActions finalizeSession(Long sid, RequestPostProcessor auth) throws Exception {
        return mockMvc.perform(post("/api/scan-sessions/" + sid + "/finalize").with(auth));
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

    private void assertError(ResultActions result, int httpStatus, String code, String messagePart) throws Exception {
        var response = result.andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(httpStatus);
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.get("code").asString()).isEqualTo(code);
        if (messagePart != null) {
            assertThat(body.get("message").asString()).contains(messagePart);
        }
    }

    private Long currentShelf(Book b) {
        return jdbc.queryForObject("select current_shelf_id from book where book_id = ?", Long.class, b.getId());
    }

    private Long expectedShelf(Book b) {
        return jdbc.queryForObject("select expected_shelf_id from book where book_id = ?", Long.class, b.getId());
    }

    private String lifecycle(Book b) {
        return jdbc.queryForObject("select lifecycle_status from book where book_id = ?", String.class, b.getId());
    }

    private Shelf saveShelf(String suffix) {
        Long id = jdbc.queryForObject("insert into shelf (shelf_code, zone, floor_no, is_active, created_at, "
                + "updated_at) values (?, ?, 1, true, now(), now()) returning shelf_id", Long.class,
                prefix + "-" + suffix, prefix + "-Z");
        return shelfRepository.findById(id).orElseThrow();
    }

    private Long saveSession(boolean reviewReady) {
        ScanSession session = ScanSession.create("확정 테스트", prefix + "-Z", null, userId);
        if (reviewReady) {
            session.markReviewReady();
        }
        return scanSessionRepository.saveAndFlush(session).getId();
    }

    private Book saveBook(Shelf expected, Shelf current, LifecycleStatus lifecycle) {
        return bookRepository.saveAndFlush(Book.builder()
                .registrationNo(prefix + "-" + UUID.randomUUID().toString().substring(0, 8))
                .title("테스트 도서")
                .callNumberRaw("005.74 김12ㄷ")
                .classificationNo("005.74")
                .bookSymbol("김12ㄷ")
                .expectedShelf(expected)
                .currentShelf(current)
                .lifecycleStatus(lifecycle)
                .loanStatus(LoanStatus.AVAILABLE)
                .build());
    }

    private Long observation(Shelf shelf, int order, Long bookId, String analysisStatus, String reason) {
        return jdbc.queryForObject("insert into book_observation (scan_session_id, book_id, detected_shelf_id, "
                        + "detected_label_raw, detected_order, analysis_status, status_reason, received_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, now()) returning observation_id", Long.class,
                sessionId, bookId, shelf.getId(), "라벨-" + order, order, analysisStatus, reason);
    }

    private void review(Long observationId, String action, Long finalBookId, String finalStatus) {
        jdbc.update("insert into review (observation_id, final_book_id, final_status, review_action, reviewer_id, "
                + "reviewed_at) values (?, ?, ?, ?, ?, now())", observationId, finalBookId, finalStatus, action,
                userId);
    }
}
