package com.__found.smart_library.domain.missing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.__found.smart_library.domain.book.entity.Book;
import com.__found.smart_library.domain.book.entity.LifecycleStatus;
import com.__found.smart_library.domain.book.entity.LoanStatus;
import com.__found.smart_library.domain.book.repository.BookRepository;
import com.__found.smart_library.domain.scan.entity.ScanSession;
import com.__found.smart_library.domain.scan.entity.ScanSessionShelf;
import com.__found.smart_library.domain.scan.repository.ScanSessionRepository;
import com.__found.smart_library.domain.scan.repository.ScanSessionShelfRepository;
import com.__found.smart_library.domain.shelf.entity.Shelf;
import com.__found.smart_library.domain.shelf.repository.ShelfRepository;
import com.__found.smart_library.domain.user.entity.AppUser;
import com.__found.smart_library.domain.user.entity.UserRole;
import com.__found.smart_library.domain.user.repository.AppUserRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
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

/** 소재불명 후보 생성(회차 확정 시)·목록·현장 확인 기록 테스트(롤백). */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MissingCandidateTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired AppUserRepository userRepository;
    @Autowired ShelfRepository shelfRepository;
    @Autowired BookRepository bookRepository;
    @Autowired ScanSessionRepository scanSessionRepository;
    @Autowired ScanSessionShelfRepository sessionShelfRepository;

    private final String prefix = "TM" + UUID.randomUUID().toString().substring(0, 8);
    private Long userId;
    private Long sessionId;
    private Shelf shelfA;
    private Shelf shelfB;

    @BeforeEach
    void setUp() {
        userId = userRepository.saveAndFlush(AppUser.builder()
                .email("missing-" + UUID.randomUUID() + "@test.com")
                .passwordHash("hash")
                .name("테스터")
                .role(UserRole.LIBRARIAN)
                .build()).getId();
        shelfA = saveShelf("A");
        shelfB = saveShelf("B");
        sessionId = saveSession(LocalDateTime.now().minusDays(1), true);
    }

    // ---- 후보 생성 ----

    @Test
    @DisplayName("확정하면 점검된 서가에서 발견되지 않은 도서만 소재불명 후보가 된다")
    void finalize_createsCandidatesForUnfoundBooks() throws Exception {
        Shelf waitingShelf = saveShelf("W");
        Shelf otherShelf = saveShelf("O");   // 이 회차의 점검 대상이 아님
        addShelf(sessionId, shelfA, true);
        addShelf(sessionId, shelfB, true);
        addShelf(sessionId, waitingShelf, false);   // 결과가 수신되지 않은 서가

        Book found = saveBook(shelfA, LifecycleStatus.NORMAL, LoanStatus.AVAILABLE);
        Book misplacedFound = saveBook(shelfA, LifecycleStatus.NORMAL, LoanStatus.AVAILABLE);   // 다른 서가에서 발견
        Book missing = saveBook(shelfA, LifecycleStatus.NORMAL, LoanStatus.AVAILABLE);
        Book alreadyMissing = saveBook(shelfB, LifecycleStatus.MISSING, LoanStatus.AVAILABLE);
        Book damaged = saveBook(shelfB, LifecycleStatus.DAMAGED, LoanStatus.RESERVED);
        Book unidentifiedOnly = saveBook(shelfA, LifecycleStatus.NORMAL, LoanStatus.AVAILABLE);
        Book disposed = saveBook(shelfA, LifecycleStatus.DISPOSED, LoanStatus.AVAILABLE);
        Book onLoan = saveBook(shelfA, LifecycleStatus.NORMAL, LoanStatus.ON_LOAN);
        Book lost = saveBook(shelfA, LifecycleStatus.NORMAL, LoanStatus.LOST);
        Book onWaitingShelf = saveBook(waitingShelf, LifecycleStatus.NORMAL, LoanStatus.AVAILABLE);
        Book onOtherShelf = saveBook(otherShelf, LifecycleStatus.NORMAL, LoanStatus.AVAILABLE);
        Book noExpectedShelf = saveBook(null, LifecycleStatus.NORMAL, LoanStatus.AVAILABLE);

        observation(shelfA, 1, found.getId(), "NORMAL", null);
        observation(shelfB, 2, misplacedFound.getId(), "MISPLACED", "WRONG_SHELF");
        Long unidentified = observation(shelfA, 3, null, "REVIEW_REQUIRED", "OCR_FAILED");
        review(unidentified, "MARK_UNIDENTIFIED", null, "UNIDENTIFIED");
        em.flush();

        JsonNode data = data(finalizeSession().andExpect(status().isOk()));
        em.flush();
        em.clear();

        assertThat(data.get("summary").get("missingCandidates").asLong()).isEqualTo(4);
        assertThat(candidateBookIds()).containsExactlyInAnyOrder(
                missing.getId(), alreadyMissing.getId(), damaged.getId(), unidentifiedOnly.getId());
        Map<String, Object> row = jdbc.queryForMap("select * from missing_candidate where book_id = ?",
                missing.getId());
        assertThat(row.get("scan_session_id")).isEqualTo(sessionId);
        assertThat(row.get("expected_shelf_id")).isEqualTo(shelfA.getId());
        assertThat(row.get("consecutive_missing_count")).isEqualTo(1);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("created_at")).isNotNull();
        // 후보를 만들어도 BOOK 상태는 바꾸지 않는다
        assertThat(jdbc.queryForObject("select lifecycle_status from book where book_id = ?", String.class,
                missing.getId())).isEqualTo("NORMAL");
        assertThat(List.of(disposed, onLoan, lost, onWaitingShelf, onOtherShelf, noExpectedShelf))
                .allSatisfy(b -> assertThat(candidateBookIds()).doesNotContain(b.getId()));
    }

    @Test
    @DisplayName("점검한 서가에 모든 도서가 발견되면 후보는 0건이다")
    void finalize_allFound_noCandidates() throws Exception {
        addShelf(sessionId, shelfA, true);
        Book book = saveBook(shelfA, LifecycleStatus.NORMAL, LoanStatus.AVAILABLE);
        observation(shelfA, 1, book.getId(), "NORMAL", null);
        em.flush();

        JsonNode data = data(finalizeSession().andExpect(status().isOk()));

        assertThat(data.get("summary").get("missingCandidates").asLong()).isZero();
        assertThat(candidateBookIds()).isEmpty();
    }

    @Test
    @DisplayName("연속 미발견 횟수: 직전 점검에서도 후보였으면 이어가고, 찾았거나 종결했거나 다시 발견된 적이 있으면 1부터 다시 센다")
    void finalize_consecutiveMissingCount() throws Exception {
        addShelf(sessionId, shelfA, true);
        Book streak = saveBook(shelfA, LifecycleStatus.NORMAL, LoanStatus.AVAILABLE);       // 직전 2회 -> 3
        Book lostCandidate = saveBook(shelfA, LifecycleStatus.NORMAL, LoanStatus.AVAILABLE); // 분실 후보도 이어감 -> 2
        Book foundBefore = saveBook(shelfA, LifecycleStatus.NORMAL, LoanStatus.AVAILABLE);   // 직전에 찾음 -> 1
        Book resolvedBefore = saveBook(shelfA, LifecycleStatus.NORMAL, LoanStatus.AVAILABLE); // 직전에 해결 -> 1
        Book foundInBetween = saveBook(shelfA, LifecycleStatus.NORMAL, LoanStatus.AVAILABLE); // 중간 점검에서 발견 -> 1
        Book neverChecked = saveBook(shelfA, LifecycleStatus.NORMAL, LoanStatus.AVAILABLE);   // 이력 없음 -> 1
        Book onShelfB = saveBook(shelfB, LifecycleStatus.NORMAL, LoanStatus.AVAILABLE);       // 서가별 직전 회차가 다르다 -> 2
        // 정상 배치 서가가 B -> A 로 바뀐 도서. B 를 점검한 회차의 후보는 서가 A 의 직전 점검이 아니므로 이어가지 않는다 -> 1
        Book movedExpected = saveBook(shelfA, LifecycleStatus.NORMAL, LoanStatus.AVAILABLE);
        addShelf(sessionId, shelfB, true);

        // 오래된 회차: foundInBetween 이 후보였다
        Long old = saveSession(LocalDateTime.now().minusDays(20), true);
        finalizeAt(old, LocalDateTime.now().minusDays(20));
        addShelf(old, shelfA, true);
        candidate(old, foundInBetween, shelfA, 5, "NOT_FOUND");
        // 직전 회차: 서가 A 를 점검했고 foundInBetween 은 발견되어 후보가 없다
        Long previous = saveSession(LocalDateTime.now().minusDays(10), true);
        finalizeAt(previous, LocalDateTime.now().minusDays(10));
        addShelf(previous, shelfA, true);
        candidate(previous, streak, shelfA, 2, "NOT_FOUND");
        candidate(previous, lostCandidate, shelfA, 1, "LOST_CANDIDATE");
        candidate(previous, foundBefore, shelfA, 4, "FOUND");
        candidate(previous, resolvedBefore, shelfA, 4, "RESOLVED");
        // 서가 B 는 다른 회차에서 직전에 점검했다
        Long previousB = saveSession(LocalDateTime.now().minusDays(8), true);
        finalizeAt(previousB, LocalDateTime.now().minusDays(8));
        addShelf(previousB, shelfB, true);
        candidate(previousB, onShelfB, shelfB, 1, "NOT_FOUND");
        candidate(previousB, movedExpected, shelfB, 4, "NOT_FOUND");
        em.flush();

        finalizeSession().andExpect(status().isOk());
        em.flush();

        assertThat(countOf(streak)).isEqualTo(3);
        assertThat(countOf(lostCandidate)).isEqualTo(2);
        assertThat(countOf(foundBefore)).isEqualTo(1);
        assertThat(countOf(resolvedBefore)).isEqualTo(1);
        assertThat(countOf(foundInBetween)).isEqualTo(1);
        assertThat(countOf(neverChecked)).isEqualTo(1);
        assertThat(countOf(onShelfB)).isEqualTo(2);
        assertThat(countOf(movedExpected)).isEqualTo(1);
    }

    @Test
    @DisplayName("확정되지 않은 회차의 후보는 연속 횟수에 반영되지 않는다")
    void finalize_ignoresUnfinishedPreviousSessions() throws Exception {
        addShelf(sessionId, shelfA, true);
        Book book = saveBook(shelfA, LifecycleStatus.NORMAL, LoanStatus.AVAILABLE);
        Long unfinished = saveSession(LocalDateTime.now().minusDays(5), true);   // REVIEW 상태 그대로
        addShelf(unfinished, shelfA, true);
        candidate(unfinished, book, shelfA, 7, "PENDING");
        em.flush();

        finalizeSession().andExpect(status().isOk());
        em.flush();

        assertThat(jdbc.queryForObject("select consecutive_missing_count from missing_candidate "
                + "where scan_session_id = ? and book_id = ?", Integer.class, sessionId, book.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("확정에 실패하면(검토 미완료) 후보도 만들어지지 않는다")
    void finalize_rejected_noCandidates() throws Exception {
        addShelf(sessionId, shelfA, true);
        saveBook(shelfA, LifecycleStatus.NORMAL, LoanStatus.AVAILABLE);
        observation(shelfA, 1, null, "REVIEW_REQUIRED", "OCR_FAILED");   // 미검토
        em.flush();

        finalizeSession().andExpect(status().isConflict());

        assertThat(candidateBookIds()).isEmpty();
    }

    // ---- 목록 ----

    @Test
    @DisplayName("목록은 연속 미발견 횟수가 많은 순이고 상태로 거를 수 있다")
    void list_ordersByCountAndFiltersByStatus() throws Exception {
        Book b1 = saveBook(shelfA, LifecycleStatus.NORMAL, LoanStatus.AVAILABLE);
        Book b2 = saveBook(shelfA, LifecycleStatus.NORMAL, LoanStatus.AVAILABLE);
        Book b3 = saveBook(shelfA, LifecycleStatus.NORMAL, LoanStatus.AVAILABLE);
        candidate(sessionId, b1, shelfA, 1, "PENDING");
        candidate(sessionId, b2, shelfA, 3, "FOUND");
        candidate(sessionId, b3, shelfA, 2, "PENDING");
        em.flush();

        JsonNode all = data(mockMvc.perform(get(listUrl()).with(auth("ROLE_STUDENT_WORKER")))
                .andExpect(status().isOk()));
        assertThat(all.get("totalElements").asLong()).isEqualTo(3);
        assertThat(ids(all)).containsExactly(b2.getId(), b3.getId(), b1.getId());
        JsonNode first = all.get("content").get(0);
        assertThat(first.get("book").get("registrationNo").asString()).isEqualTo(b2.getRegistrationNo());
        assertThat(first.get("expectedShelfCode").asString()).isEqualTo(shelfA.getShelfCode());
        assertThat(first.get("consecutiveMissingCount").asInt()).isEqualTo(3);
        assertThat(first.get("status").asString()).isEqualTo("FOUND");

        JsonNode pending = data(mockMvc.perform(get(listUrl() + "?status=PENDING").with(librarian()))
                .andExpect(status().isOk()));
        assertThat(ids(pending)).containsExactly(b3.getId(), b1.getId());

        JsonNode page2 = data(mockMvc.perform(get(listUrl() + "?page=1&size=2").with(librarian()))
                .andExpect(status().isOk()));
        assertThat(ids(page2)).containsExactly(b1.getId());
        assertThat(page2.get("totalPages").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("목록: 없는 회차 404, 미인증 401, 잘못된 status 400")
    void list_errors() throws Exception {
        assertError(mockMvc.perform(get("/api/scan-sessions/99999999/missing-candidates").with(librarian())),
                404, "SCAN_001");
        mockMvc.perform(get(listUrl())).andExpect(status().isUnauthorized());
        mockMvc.perform(get(listUrl() + "?status=NOPE").with(librarian())).andExpect(status().isBadRequest());
    }

    // ---- 현장 확인 기록 ----

    @Test
    @DisplayName("현장 확인 결과를 기록하면 상태·메모·확인자·확인 시각이 저장되고 이력이 남는다")
    void record_savesResultAndAudit() throws Exception {
        Long candidateId = candidate(sessionId, saveBook(shelfA, LifecycleStatus.NORMAL, LoanStatus.AVAILABLE),
                shelfA, 2, "PENDING");
        em.flush();

        JsonNode data = data(record(candidateId, "FOUND", "  3층 열람실 책상에 있음  ", auth("ROLE_STUDENT_WORKER"))
                .andExpect(status().isOk()));
        em.flush();

        assertThat(data.get("status").asString()).isEqualTo("FOUND");
        assertThat(data.get("note").asString()).isEqualTo("3층 열람실 책상에 있음");
        assertThat(data.get("reviewedBy").asLong()).isEqualTo(userId);
        assertThat(data.get("reviewedAt").isNull()).isFalse();
        assertThat(data.get("consecutiveMissingCount").asInt()).isEqualTo(2);
        Map<String, Object> row = jdbc.queryForMap("select * from missing_candidate where missing_id = ?",
                candidateId);
        assertThat(row.get("status")).isEqualTo("FOUND");
        assertThat(row.get("reviewed_by")).isEqualTo(userId);
        Map<String, Object> audit = jdbc.queryForMap("select * from audit_log where entity_type = "
                + "'MISSING_CANDIDATE' and entity_id = ?", candidateId);
        assertThat(audit.get("action")).isEqualTo("STATUS_CHANGE");
        assertThat(audit.get("actor_id")).isEqualTo(userId);
        assertThat(audit.get("before_data").toString()).contains("PENDING");
        assertThat(audit.get("after_data").toString()).contains("FOUND").contains("3층 열람실 책상에 있음");
    }

    @Test
    @DisplayName("다시 기록하면 덮어쓰고, 상태·메모가 그대로면 이력을 추가하지 않으며 메모만 바뀌면 UPDATE 이력이다")
    void record_overwrite_auditOnlyOnChange() throws Exception {
        Long candidateId = candidate(sessionId, saveBook(shelfA, LifecycleStatus.NORMAL, LoanStatus.AVAILABLE),
                shelfA, 1, "PENDING");
        em.flush();

        record(candidateId, "NOT_FOUND", "서가 확인", librarian()).andExpect(status().isOk());
        record(candidateId, "NOT_FOUND", "서가 확인", librarian()).andExpect(status().isOk());   // 변화 없음
        record(candidateId, "NOT_FOUND", "서가와 뒷줄 확인", librarian()).andExpect(status().isOk());
        record(candidateId, "LOST_CANDIDATE", null, librarian()).andExpect(status().isOk());
        em.flush();

        List<Map<String, Object>> audits = jdbc.queryForList("select action from audit_log where entity_type = "
                + "'MISSING_CANDIDATE' and entity_id = ? order by audit_log_id", candidateId);
        assertThat(audits).extracting(a -> a.get("action"))
                .containsExactly("STATUS_CHANGE", "UPDATE", "STATUS_CHANGE");
        assertThat(jdbc.queryForObject("select note from missing_candidate where missing_id = ?", String.class,
                candidateId)).isNull();
    }

    @Test
    @DisplayName("기록 오류: PENDING 400, 상태 누락·잘못된 값 400, 없는 후보 404, 긴 메모 400, 미인증 401")
    void record_errors() throws Exception {
        Long candidateId = candidate(sessionId, saveBook(shelfA, LifecycleStatus.NORMAL, LoanStatus.AVAILABLE),
                shelfA, 1, "PENDING");
        em.flush();

        assertError(record(candidateId, "PENDING", null, librarian()), 400, "COMMON_001");
        mockMvc.perform(put(recordUrl(candidateId)).with(librarian()).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isBadRequest());
        mockMvc.perform(put(recordUrl(candidateId)).with(librarian()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"NOPE\"}")).andExpect(status().isBadRequest());
        record(candidateId, "FOUND", "가".repeat(1001), librarian()).andExpect(status().isBadRequest());
        assertError(record(99999999L, "FOUND", null, librarian()), 404, "MISSING_001");
        mockMvc.perform(put(recordUrl(candidateId)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"FOUND\"}")).andExpect(status().isUnauthorized());
        em.flush();

        assertThat(jdbc.queryForObject("select status from missing_candidate where missing_id = ?", String.class,
                candidateId)).isEqualTo("PENDING");
    }

    // ---- helpers ----

    private String listUrl() {
        return "/api/scan-sessions/" + sessionId + "/missing-candidates";
    }

    private String recordUrl(Long candidateId) {
        return "/api/missing-candidates/" + candidateId + "/review";
    }

    private ResultActions finalizeSession() throws Exception {
        return mockMvc.perform(post("/api/scan-sessions/" + sessionId + "/finalize").with(librarian()));
    }

    private ResultActions record(Long candidateId, String status, String note, RequestPostProcessor auth)
            throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("status", status);
        body.put("note", note);
        return mockMvc.perform(put(recordUrl(candidateId)).with(auth).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
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

    private List<Long> ids(JsonNode page) {
        List<Long> ids = new java.util.ArrayList<>();
        page.get("content").forEach(n -> ids.add(n.get("book").get("bookId").asLong()));
        return ids;
    }

    private List<Long> candidateBookIds() {
        return jdbc.queryForList("select book_id from missing_candidate where scan_session_id = ?", Long.class,
                sessionId);
    }

    private int countOf(Book book) {
        return jdbc.queryForObject("select consecutive_missing_count from missing_candidate "
                + "where scan_session_id = ? and book_id = ?", Integer.class, sessionId, book.getId());
    }

    private Shelf saveShelf(String suffix) {
        Long id = jdbc.queryForObject("insert into shelf (shelf_code, zone, floor_no, is_active, created_at, "
                + "updated_at) values (?, ?, 1, true, now(), now()) returning shelf_id", Long.class,
                prefix + "-" + suffix, prefix + "-Z");
        return shelfRepository.findById(id).orElseThrow();
    }

    private Long saveSession(LocalDateTime createdHint, boolean reviewReady) {
        ScanSession session = ScanSession.create("후보 테스트", prefix + "-Z" + UUID.randomUUID().toString()
                .substring(0, 4), null, userId);
        if (reviewReady) {
            session.markReviewReady();
        }
        return scanSessionRepository.saveAndFlush(session).getId();
    }

    /** 이전 회차를 확정된 상태로 만든다. */
    private void finalizeAt(Long sid, LocalDateTime at) {
        ScanSession session = scanSessionRepository.findById(sid).orElseThrow();
        session.finalizeBy(userId, at);
        scanSessionRepository.saveAndFlush(session);
    }

    private void addShelf(Long sid, Shelf shelf, boolean resultReceived) {
        ScanSessionShelf ss = ScanSessionShelf.waiting(sid, shelf.getId());
        if (resultReceived) {
            ss.receiveResult(0, null);
        }
        sessionShelfRepository.saveAndFlush(ss);
    }

    private Book saveBook(Shelf expected, LifecycleStatus lifecycle, LoanStatus loan) {
        return bookRepository.saveAndFlush(Book.builder()
                .registrationNo(prefix + "-" + UUID.randomUUID().toString().substring(0, 8))
                .title("테스트 도서")
                .callNumberRaw("005.74 김12ㄷ")
                .classificationNo("005.74")
                .bookSymbol("김12ㄷ")
                .expectedShelf(expected)
                .lifecycleStatus(lifecycle)
                .loanStatus(loan)
                .build());
    }

    private Long candidate(Long sid, Book book, Shelf shelf, int count, String status) {
        return jdbc.queryForObject("insert into missing_candidate (scan_session_id, book_id, expected_shelf_id, "
                + "consecutive_missing_count, status, created_at) values (?, ?, ?, ?, ?, now()) "
                + "returning missing_id", Long.class, sid, book.getId(), shelf.getId(), count, status);
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
