package com.__found.smart_library.domain.observation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.__found.smart_library.domain.user.entity.AppUser;
import com.__found.smart_library.domain.user.entity.UserRole;
import com.__found.smart_library.domain.user.repository.AppUserRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Vision 결과 수신 테스트 공통 도구. 실제로 커밋하는 테스트이므로(락·동시성·재전송을 실제 트랜잭션으로 검증)
 * 테스트마다 고유한 prefix 로 데이터를 만들고 끝나면 전부 지운 뒤 잔여 여부를 확인한다. 시드 데이터에 의존하지 않는다.
 * 키는 테스트 전용 더미 값이며 어떤 환경의 실제 키와도 무관하다.
 */
@SpringBootTest(properties = "app.vision.api-key=" + VisionReceiveTestSupport.API_KEY)
@AutoConfigureMockMvc
abstract class VisionReceiveTestSupport {

    static final String API_KEY = "test-only-vision-api-key-not-a-secret-000001";
    static final String HEADER = "X-Vision-Api-Key";
    static final String URL = "/api/vision/observations";

    @Autowired protected MockMvc mockMvc;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired AppUserRepository userRepository;

    /** shelf.zone 은 VARCHAR(30) 이므로 짧게 쓴다. */
    protected final String prefix = "TV" + UUID.randomUUID().toString().substring(0, 8);
    protected Long userId;
    protected int seq = 0;
    /** 요청 스레드가 종료 대기 시간 안에 끝나지 않았는지. true 이면 정리가 진행 중인 요청과 경쟁하므로 정리하지 않는다. */
    protected boolean requestThreadsStillRunning;

    /** 회차와 점검 대상 서가. */
    protected record Fixture(Long sessionId, String zone, List<Long> shelfIds, List<String> shelfCodes) {
    }

    @BeforeEach
    void createUser() {
        userId = userRepository.saveAndFlush(AppUser.builder()
                .email("vision-" + UUID.randomUUID() + "@test.com")
                .passwordHash("hash")
                .name("테스터")
                .role(UserRole.LIBRARIAN)
                .build()).getId();
    }

    @AfterEach
    void cleanUp() {
        if (requestThreadsStillRunning) {
            throw new IllegalStateException("요청 스레드가 종료되지 않아 DB 정리를 건너뜁니다. 잔여 데이터 prefix=" + prefix);
        }
        String sessions = "select scan_session_id from scan_session where target_area like ?";
        String z = prefix + "%";
        jdbc.update("delete from review where observation_id in (select observation_id from book_observation "
                + "where scan_session_id in (" + sessions + "))", z);
        jdbc.update("delete from observation_match_candidate where observation_id in "
                + "(select observation_id from book_observation where scan_session_id in (" + sessions + "))", z);
        jdbc.update("delete from book_observation where scan_session_id in (" + sessions + ")", z);
        jdbc.update("delete from scan_session_shelf where scan_session_id in (" + sessions + ")", z);
        jdbc.update("delete from scan_session where target_area like ?", z);
        jdbc.update("delete from book where registration_no like ?", z);
        jdbc.update("delete from shelf where shelf_code like ?", z);
        jdbc.update("delete from app_user where user_id = ?", userId);

        assertThat(count("select count(*) from scan_session where target_area like ?", z)).isZero();
        assertThat(count("select count(*) from shelf where shelf_code like ?", z)).isZero();
        assertThat(count("select count(*) from book where registration_no like ?", z)).isZero();
        assertThat(count("select count(*) from app_user where user_id = ?", userId)).isZero();
    }

    // ---- 데이터 ----

    /** IN_PROGRESS 회차와 WAITING 상태의 점검 대상 서가 shelfCount 개를 만든다. */
    protected Fixture fixture(int shelfCount) {
        String zone = prefix + "-Z" + (++seq);
        Long sessionId = jdbc.queryForObject("insert into scan_session (session_name, target_area, status, "
                + "created_by, started_at, created_at, updated_at) values ('테스트 회차', ?, 'IN_PROGRESS', ?, now(), "
                + "now(), now()) returning scan_session_id", Long.class, zone, userId);
        List<Long> ids = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < shelfCount; i++) {
            String code = prefix + "-S" + (++seq);
            Long shelfId = insertShelf(code, zone);
            jdbc.update("insert into scan_session_shelf (scan_session_id, shelf_id, scan_status) "
                    + "values (?, ?, 'WAITING')", sessionId, shelfId);
            ids.add(shelfId);
            codes.add(code);
        }
        return new Fixture(sessionId, zone, ids, codes);
    }

    protected Long insertShelf(String code, String zone) {
        return jdbc.queryForObject("insert into shelf (shelf_code, zone, floor_no, is_active, created_at, updated_at) "
                + "values (?, ?, 1, true, now(), now()) returning shelf_id", Long.class, code, zone);
    }

    protected String cls(String key) {
        return "T" + prefix + "." + key;
    }

    protected String sym(String key) {
        return "S" + prefix + "ㅈ" + key;
    }

    /** 테스트 전용 파서가 해석하는 라벨. */
    protected String label(String key, String copy) {
        return cls(key) + "|" + sym(key) + "|" + copy;
    }

    protected Long book(String key, String copy, Long expectedShelfId, String lifecycleStatus) {
        return jdbc.queryForObject("insert into book (registration_no, title, call_number_raw, classification_no, "
                        + "book_symbol, copy_number, expected_shelf_id, lifecycle_status, loan_status, created_at, "
                        + "updated_at) values (?, '테스트 도서', ?, ?, ?, ?, ?, ?, 'AVAILABLE', now(), now()) "
                        + "returning book_id",
                Long.class, prefix + "-" + String.format("%04d", ++seq), cls(key) + " " + sym(key), cls(key),
                sym(key), copy, expectedShelfId, lifecycleStatus);
    }

    protected Long bookRaw(String classificationNo, String bookSymbol, String copy, Long expectedShelfId) {
        return jdbc.queryForObject("insert into book (registration_no, title, call_number_raw, classification_no, "
                        + "book_symbol, copy_number, expected_shelf_id, lifecycle_status, loan_status, created_at, "
                        + "updated_at) values (?, '테스트 도서', ?, ?, ?, ?, ?, 'NORMAL', 'AVAILABLE', now(), now()) "
                        + "returning book_id",
                Long.class, prefix + "-" + String.format("%04d", ++seq), classificationNo + " " + bookSymbol,
                classificationNo, bookSymbol, copy, expectedShelfId);
    }

    /**
     * 서가 3개 회차용 대표 시나리오. 서가0: 정상(1)·오배치(2)·복본 불일치로 매칭 실패(3)·해석 불가(4),
     * 서가1: 정상(1), 서가2: 도서 0권. 정상 2, 오배치 1, 검토 필요 2(NO_MATCH, OCR_FAILED).
     */
    protected record Scenario(Map<String, Object> body, Long normalBook, Long misplacedBook, Long copyMismatchBook,
                              Long normalBook2) {
    }

    protected Scenario standardScenario(Fixture fx) {
        Long a = fx.shelfIds().get(0);
        Long b = fx.shelfIds().get(1);
        Long normal = book("N", "c.1", a, "NORMAL");
        Long misplaced = book("M", "c.1", b, "NORMAL");       // 예상 서가는 b 인데 a 에서 관측
        Long copyMismatch = book("C", "c.1", a, "NORMAL");    // 라벨은 c.2 → 완전 일치 없음
        Long normal2 = book("P", "c.1", b, "NORMAL");
        Map<String, Object> body = body(fx.sessionId(),
                shelf(fx.shelfCodes().get(0), obs(1, label("N", "c.1")), obs(2, label("M", "c.1")),
                        obs(3, label("C", "c.2")), obs(4, "해석불가")),
                shelf(fx.shelfCodes().get(1), obs(1, label("P", "c.1"))),
                shelf(fx.shelfCodes().get(2)));
        return new Scenario(body, normal, misplaced, copyMismatch, normal2);
    }

    // ---- 요청 ----

    protected static Map<String, Object> obs(int order, String label) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("detectedOrder", order);
        o.put("detectedLabelRaw", label);
        o.put("detectedTitle", "제목 " + order);
        o.put("xPositionCm", 10.5 * order);
        o.put("detectedAt", LocalDateTime.of(2026, 11, 24, 10, 0, order % 60).toString());
        return o;
    }

    @SafeVarargs
    protected static Map<String, Object> shelf(String code, Map<String, Object>... observations) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("shelfCode", code);
        s.put("observations", List.of(observations));
        return s;
    }

    @SafeVarargs
    protected static Map<String, Object> body(Long sessionId, Map<String, Object>... shelves) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("scanSessionId", sessionId);
        b.put("shelves", List.of(shelves));
        return b;
    }

    /** 회차의 모든 서가가 도서 0권인 요청. */
    protected static Map<String, Object> emptyBody(Fixture fx) {
        List<Map<String, Object>> shelves = new ArrayList<>();
        for (String code : fx.shelfCodes()) {
            shelves.add(shelf(code));
        }
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("scanSessionId", fx.sessionId());
        b.put("shelves", shelves);
        return b;
    }

    protected MvcResult send(Object body) throws Exception {
        return mockMvc.perform(post(URL).header(HEADER, API_KEY).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))).andReturn();
    }

    protected MvcResult sendRaw(String json) throws Exception {
        return mockMvc.perform(post(URL).header(HEADER, API_KEY).contentType(MediaType.APPLICATION_JSON)
                .content(json)).andReturn();
    }

    protected int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    protected JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    // ---- 조회 ----

    protected long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    protected String sessionStatus(Long sessionId) {
        return jdbc.queryForObject("select status from scan_session where scan_session_id = ?", String.class,
                sessionId);
    }

    protected long observationCount(Long sessionId) {
        return count("select count(*) from book_observation where scan_session_id = ?", sessionId);
    }

    protected List<Map<String, Object>> observations(Long sessionId) {
        return jdbc.queryForList("select * from book_observation where scan_session_id = ? "
                + "order by detected_shelf_id, detected_order", sessionId);
    }

    /** 수신과 관련된 모든 저장 상태(관측·후보·서가·회차·검토). 재전송이 아무것도 바꾸지 않았음을 비교하는 데 쓴다. */
    protected Map<String, Object> snapshot(Long sessionId) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("session", jdbc.queryForMap("select * from scan_session where scan_session_id = ?", sessionId));
        snap.put("shelves", jdbc.queryForList("select * from scan_session_shelf where scan_session_id = ? "
                + "order by shelf_id", sessionId));
        snap.put("observations", observations(sessionId));
        snap.put("candidates", jdbc.queryForList("select c.* from observation_match_candidate c "
                + "join book_observation o on o.observation_id = c.observation_id where o.scan_session_id = ? "
                + "order by c.observation_id, c.candidate_rank", sessionId));
        snap.put("reviews", jdbc.queryForList("select r.* from review r join book_observation o "
                + "on o.observation_id = r.observation_id where o.scan_session_id = ? order by r.review_id",
                sessionId));
        return snap;
    }

    /** 첫 관측에 사서 검토 기록을 직접 만든다(재전송이 검토 결과를 건드리지 않는지 확인용). */
    protected void insertReview(Long sessionId) {
        Long observationId = jdbc.queryForObject("select min(observation_id) from book_observation "
                + "where scan_session_id = ?", Long.class, sessionId);
        jdbc.update("insert into review (observation_id, final_status, review_action, reviewer_id, reviewed_at) "
                + "values (?, 'NORMAL', 'CONFIRMED', ?, now())", observationId, userId);
    }
}
