package com.__found.smart_library.domain.condition.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.__found.smart_library.domain.book.entity.Book;
import com.__found.smart_library.domain.book.entity.LifecycleStatus;
import com.__found.smart_library.domain.book.entity.LoanStatus;
import com.__found.smart_library.domain.book.repository.BookRepository;
import com.__found.smart_library.domain.scan.entity.ScanSession;
import com.__found.smart_library.domain.scan.repository.ScanSessionRepository;
import com.__found.smart_library.domain.user.entity.AppUser;
import com.__found.smart_library.domain.user.entity.UserRole;
import com.__found.smart_library.domain.user.repository.AppUserRepository;
import jakarta.persistence.EntityManager;
import java.util.LinkedHashMap;
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

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ConditionReportTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired AppUserRepository userRepository;
    @Autowired BookRepository bookRepository;
    @Autowired ScanSessionRepository scanSessionRepository;
    @Autowired EntityManager em;

    private Long userId;
    private Long bookId;

    @BeforeEach
    void setUp() {
        userId = saveUser(UserRole.LIBRARIAN, "사서테스터");
        bookId = saveBook();
    }

    // ---- 등록 ----

    @Test
    @DisplayName("정상 요청이면 201과 함께 기록이 저장되고 기록자·시각이 채워진다")
    void create_validRequest_returns201AndSaves() throws Exception {
        JsonNode data = data(perform(bookId, auth(userId, "ROLE_LIBRARIAN"),
                body("TORN", "HIGH", null, "표지가 찢어짐", "https://img.example.com/a.jpg"))
                .andExpect(status().isCreated()));

        assertThat(data.get("id").asLong()).isPositive();
        assertThat(data.get("bookId").asLong()).isEqualTo(bookId);
        assertThat(data.get("reporterId").asLong()).isEqualTo(userId);
        assertThat(data.get("reporterName").asString()).isEqualTo("사서테스터");
        assertThat(data.get("conditionType").asString()).isEqualTo("TORN");
        assertThat(data.get("severity").asString()).isEqualTo("HIGH");
        assertThat(data.get("note").asString()).isEqualTo("표지가 찢어짐");
        assertThat(data.get("imageUrl").asString()).isEqualTo("https://img.example.com/a.jpg");
        assertThat(data.get("scanSessionId").isNull()).isTrue();
        assertThat(data.get("reportedAt").isNull()).isFalse();
        em.flush();
        Map<String, Object> row = jdbc.queryForMap(
                "select * from book_condition_report where condition_report_id = ?", data.get("id").asLong());
        assertThat(row.get("book_id")).isEqualTo(bookId);
        assertThat(row.get("reporter_id")).isEqualTo(userId);
        assertThat(row.get("condition_type")).isEqualTo("TORN");
        assertThat(row.get("severity")).isEqualTo("HIGH");
        assertThat(row.get("reported_at")).isNotNull();
    }

    @Test
    @DisplayName("메모·이미지·회차는 선택이라 유형과 심각도만으로 등록할 수 있다")
    void create_onlyRequiredFields_returns201() throws Exception {
        JsonNode data = data(perform(bookId, auth(userId, "ROLE_LIBRARIAN"), body("STAIN", "LOW", null, null, null))
                .andExpect(status().isCreated()));

        assertThat(data.get("note").isNull()).isTrue();
        assertThat(data.get("imageUrl").isNull()).isTrue();
    }

    @Test
    @DisplayName("존재하는 점검 회차를 연결할 수 있다")
    void create_withExistingSession_linksSession() throws Exception {
        Long sessionId = scanSessionRepository.saveAndFlush(
                ScanSession.create("테스트 회차", "TEST-ZONE", null, userId)).getId();

        JsonNode data = data(perform(bookId, auth(userId, "ROLE_LIBRARIAN"),
                body("WATER_DAMAGE", "MEDIUM", sessionId, null, null)).andExpect(status().isCreated()));

        assertThat(data.get("scanSessionId").asLong()).isEqualTo(sessionId);
    }

    @Test
    @DisplayName("근로학생도 등록할 수 있고 미인증은 401이다 (거부된 요청은 저장되지 않음)")
    void create_byRole_bothRolesAllowed_anonymous401() throws Exception {
        Long workerId = saveUser(UserRole.STUDENT_WORKER, "근로생");

        perform(bookId, auth(workerId, "ROLE_STUDENT_WORKER"), body("STAIN", "LOW", null, null, null))
                .andExpect(status().isCreated());
        mockMvc.perform(post(url(bookId)).contentType(MediaType.APPLICATION_JSON)
                .content(body("STAIN", "LOW", null, null, null))).andExpect(status().isUnauthorized());

        assertThat(reportCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("없는 도서는 404(BOOK_001)이고 저장되지 않는다")
    void create_unknownBook_returns404() throws Exception {
        perform(Long.MAX_VALUE, auth(userId, "ROLE_LIBRARIAN"), body("STAIN", "LOW", null, null, null))
                .andExpect(status().isNotFound());
        assertThat(reportCount()).isZero();
    }

    @Test
    @DisplayName("없는 점검 회차를 연결하면 404(SCAN_001)이고 저장되지 않는다")
    void create_unknownSession_returns404() throws Exception {
        perform(bookId, auth(userId, "ROLE_LIBRARIAN"), body("STAIN", "LOW", Long.MAX_VALUE, null, null))
                .andExpect(status().isNotFound());
        assertThat(reportCount()).isZero();
    }

    @Test
    @DisplayName("유형·심각도가 없거나 알 수 없는 값이면 400이다")
    void create_invalidTypeOrSeverity_returns400() throws Exception {
        perform(bookId, auth(userId, "ROLE_LIBRARIAN"), body(null, "LOW", null, null, null))
                .andExpect(status().isBadRequest());
        perform(bookId, auth(userId, "ROLE_LIBRARIAN"), body("STAIN", null, null, null, null))
                .andExpect(status().isBadRequest());
        perform(bookId, auth(userId, "ROLE_LIBRARIAN"), body("BURNED", "LOW", null, null, null))
                .andExpect(status().isBadRequest());
        perform(bookId, auth(userId, "ROLE_LIBRARIAN"), body("STAIN", "EXTREME", null, null, null))
                .andExpect(status().isBadRequest());
        assertThat(reportCount()).isZero();
    }

    @Test
    @DisplayName("이미지 주소는 http(s)만 허용하고 500자를 넘으면 400이다 (javascript: 등 거부)")
    void create_invalidImageUrl_returns400() throws Exception {
        for (String bad : new String[]{"javascript:alert(1)", "ftp://a.com/x.jpg", "not a url", "data:image/png;base64,AAAA"}) {
            perform(bookId, auth(userId, "ROLE_LIBRARIAN"), body("STAIN", "LOW", null, null, bad))
                    .andExpect(status().isBadRequest());
        }
        String tooLong = "https://a.com/" + "x".repeat(487);   // 501자
        assertThat(tooLong).hasSize(501);
        perform(bookId, auth(userId, "ROLE_LIBRARIAN"), body("STAIN", "LOW", null, null, tooLong))
                .andExpect(status().isBadRequest());
        assertThat(reportCount()).isZero();

        String exactly500 = "https://a.com/" + "x".repeat(486);
        perform(bookId, auth(userId, "ROLE_LIBRARIAN"), body("STAIN", "LOW", null, null, exactly500))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("메모가 2001자면 400이고 2000자는 허용한다")
    void create_noteLength() throws Exception {
        perform(bookId, auth(userId, "ROLE_LIBRARIAN"), body("STAIN", "LOW", null, "가".repeat(2001), null))
                .andExpect(status().isBadRequest());
        assertThat(reportCount()).isZero();
        perform(bookId, auth(userId, "ROLE_LIBRARIAN"), body("STAIN", "LOW", null, "가".repeat(2000), null))
                .andExpect(status().isCreated());
    }

    // ---- 조회 ----

    @Test
    @DisplayName("도서의 기록을 최신순으로 돌려주고 다른 도서의 기록은 섞이지 않는다")
    void list_returnsOnlyThisBookNewestFirst() throws Exception {
        Long otherBook = saveBook();
        perform(bookId, auth(userId, "ROLE_LIBRARIAN"), body("STAIN", "LOW", null, "첫번째", null));
        perform(bookId, auth(userId, "ROLE_LIBRARIAN"), body("TORN", "HIGH", null, "두번째", null));
        perform(otherBook, auth(userId, "ROLE_LIBRARIAN"), body("STAIN", "LOW", null, "다른 책", null));

        JsonNode data = data(mockMvc.perform(get(url(bookId)).with(auth(userId, "ROLE_STUDENT_WORKER")))
                .andExpect(status().isOk()));

        assertThat(data).hasSize(2);
        assertThat(data.get(0).get("note").asString()).isEqualTo("두번째");
        assertThat(data.get(1).get("note").asString()).isEqualTo("첫번째");
        assertThat(data.get(0).get("reporterName").asString()).isEqualTo("사서테스터");
    }

    @Test
    @DisplayName("기록이 없으면 빈 목록, 없는 도서는 404, 미인증은 401이다")
    void list_emptyUnknownBookAnonymous() throws Exception {
        assertThat(data(mockMvc.perform(get(url(bookId)).with(auth(userId, "ROLE_LIBRARIAN")))
                .andExpect(status().isOk()))).isEmpty();
        mockMvc.perform(get(url(Long.MAX_VALUE)).with(auth(userId, "ROLE_LIBRARIAN")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(url(bookId))).andExpect(status().isUnauthorized());
    }

    // ---- helpers ----

    private String url(Long id) {
        return "/api/books/" + id + "/condition-reports";
    }

    private ResultActions perform(Long id, RequestPostProcessor auth, String json) throws Exception {
        return mockMvc.perform(post(url(id)).with(auth).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private RequestPostProcessor auth(Long uid, String role) {
        return jwt().jwt(j -> j.subject(String.valueOf(uid))).authorities(new SimpleGrantedAuthority(role));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }

    private String body(String type, String severity, Long sessionId, String note, String imageUrl) throws Exception {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("conditionType", type);
        map.put("severity", severity);
        map.put("scanSessionId", sessionId);
        map.put("note", note);
        map.put("imageUrl", imageUrl);
        return objectMapper.writeValueAsString(map);
    }

    private long reportCount() {
        em.flush();
        return jdbc.queryForObject("select count(*) from book_condition_report where book_id = ?", Long.class, bookId);
    }

    private Long saveUser(UserRole role, String name) {
        return userRepository.saveAndFlush(AppUser.builder()
                .email("cond-" + UUID.randomUUID() + "@test.com")
                .passwordHash("hash")
                .name(name)
                .role(role)
                .build()).getId();
    }

    private Long saveBook() {
        return bookRepository.saveAndFlush(Book.builder()
                .registrationNo("TEST-" + UUID.randomUUID().toString().substring(0, 8))
                .title("테스트 도서")
                .callNumberRaw("005.74 김12ㄷ")
                .classificationNo("005.74")
                .bookSymbol("김12ㄷ")
                .lifecycleStatus(LifecycleStatus.NORMAL)
                .loanStatus(LoanStatus.AVAILABLE)
                .build()).getId();
    }
}
