package com.__found.smart_library.domain.evaluation.composite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.__found.smart_library.domain.user.entity.AppUser;
import com.__found.smart_library.domain.user.entity.UserRole;
import com.__found.smart_library.domain.user.repository.AppUserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 같은 회차의 종합 평가를 동시에 실행해도 도서마다 평가가 1건이고, 새로 저장한 건수의 합이 도서 수와 같은지
 * (한 요청이 저장하는 동안 다른 요청은 기다렸다가 그대로 둔다) 검증한다. 실제 커밋이 필요해 롤백 테스트를 쓰지 않으며,
 * prefix 로 만든 데이터만 지운다. 평가 설정은 바꾸지 않고 지금 활성인 설정을 쓴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CompositeEvaluationConcurrencyTest {

    private static final int THREADS = 4;   // Hikari 풀(10)보다 작게 유지
    private static final int BOOKS = 3;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired AppUserRepository userRepository;

    private final String prefix = "TW" + UUID.randomUUID().toString().substring(0, 8);
    private Long userId;
    private Long sessionId;
    private boolean requestThreadsStillRunning;

    @BeforeEach
    void setUp() {
        userId = userRepository.saveAndFlush(AppUser.builder()
                .email("composite-conc-" + UUID.randomUUID() + "@test.com")
                .passwordHash("hash")
                .name("테스터")
                .role(UserRole.LIBRARIAN)
                .build()).getId();
        Long shelfId = jdbc.queryForObject("insert into shelf (shelf_code, zone, floor_no, is_active, created_at, "
                + "updated_at) values (?, ?, 1, true, now(), now()) returning shelf_id", Long.class,
                prefix + "-S", prefix + "-Z");
        sessionId = jdbc.queryForObject("insert into scan_session (session_name, target_area, status, created_by, "
                + "created_at, updated_at) values ('동시성', ?, 'COMPLETED', ?, now(), now()) "
                + "returning scan_session_id", Long.class, prefix + "-Z", userId);
        for (int i = 1; i <= BOOKS; i++) {
            Long bookId = jdbc.queryForObject("insert into book (registration_no, title, call_number_raw, "
                    + "classification_no, book_symbol, lifecycle_status, loan_status, total_loan_count, version, "
                    + "created_at, updated_at) values (?, '동시성 도서', 'x', ?, ?, 'NORMAL', 'AVAILABLE', 0, 0, "
                    + "now(), now()) returning book_id", Long.class, prefix + "-B" + i, prefix + "c" + i,
                    prefix + "s" + i);
            jdbc.update("insert into book_observation (scan_session_id, book_id, detected_shelf_id, detected_order, "
                    + "analysis_status, received_at) values (?, ?, ?, ?, 'NORMAL', now())",
                    sessionId, bookId, shelfId, i);
            Long jevId = jdbc.queryForObject("insert into book_evaluation (book_id, scan_session_id, "
                    + "evaluation_type, model_version, candidate_status, evaluated_at) "
                    + "values (?, ?, 'JEV', 'jev-1.14.0', 'UNDECIDED', now()) returning evaluation_id", Long.class,
                    bookId, sessionId);
            for (String type : List.of("RARITY", "CONTENT_VALIDITY")) {
                jdbc.update("insert into evaluation_criterion_result (evaluation_id, criterion_type, evaluator_type, "
                        + "model_version, raw_score, normalized_score) values (?, ?, 'JEV', 'jev-1.14.0', 2, 50)",
                        jevId, type);
            }
        }
    }

    @AfterEach
    void cleanUp() {
        if (requestThreadsStillRunning) {
            throw new IllegalStateException("요청 스레드가 종료되지 않아 DB 정리를 건너뜁니다. prefix=" + prefix);
        }
        jdbc.update("delete from audit_log where entity_type = 'BOOK_EVALUATION' and entity_id in "
                + "(select evaluation_id from book_evaluation where scan_session_id = ?)", sessionId);
        jdbc.update("delete from evaluation_criterion_result where evaluation_id in "
                + "(select evaluation_id from book_evaluation where scan_session_id = ?)", sessionId);
        jdbc.update("delete from book_evaluation where scan_session_id = ?", sessionId);
        jdbc.update("delete from book_observation where scan_session_id = ?", sessionId);
        jdbc.update("delete from scan_session where scan_session_id = ?", sessionId);
        jdbc.update("delete from book where registration_no like ?", prefix + "%");
        jdbc.update("delete from shelf where zone like ?", prefix + "%");
        jdbc.update("delete from app_user where user_id = ?", userId);

        assertThat(count("select count(*) from book_evaluation where scan_session_id = ?", sessionId)).isZero();
        assertThat(count("select count(*) from scan_session where scan_session_id = ?", sessionId)).isZero();
        assertThat(count("select count(*) from app_user where user_id = ?", userId)).isZero();
    }

    @Test
    @DisplayName("같은 회차를 동시에 4번 실행하면 도서마다 종합 평가는 1건이고, 새로 저장한 건수는 도서 수와 같다")
    void sameSessionConcurrently_oneEvaluationPerBook() throws Exception {
        List<JsonNode> results = runConcurrently();

        assertThat(count("select count(*) from book_evaluation where evaluation_type = 'BACKEND' "
                + "and scan_session_id = ?", sessionId)).isEqualTo(BOOKS);
        assertThat(count("select count(*) from evaluation_criterion_result r join book_evaluation e "
                + "on e.evaluation_id = r.evaluation_id where e.evaluation_type = 'BACKEND' "
                + "and e.scan_session_id = ?", sessionId)).isEqualTo(BOOKS * 3L);
        assertThat(count("select count(*) from audit_log where entity_type = 'BOOK_EVALUATION' "
                + "and action = 'CREATE' and entity_id in (select evaluation_id from book_evaluation "
                + "where scan_session_id = ?)", sessionId)).isEqualTo(BOOKS);
        int created = 0;
        int unchanged = 0;
        for (JsonNode data : results) {
            assertThat(data.get("total").asInt()).isEqualTo(BOOKS);
            created += data.get("created").asInt();
            unchanged += data.get("unchanged").asInt();
            assertThat(data.get("updated").asInt()).isZero();
        }
        assertThat(created).isEqualTo(BOOKS);
        assertThat(unchanged).isEqualTo(BOOKS * (THREADS - 1));
    }

    private List<JsonNode> runConcurrently() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<JsonNode>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                Callable<JsonNode> task = () -> {
                    ready.countDown();
                    start.await();
                    return request();
                };
                futures.add(pool.submit(task));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<JsonNode> results = new ArrayList<>();
            for (Future<JsonNode> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            requestThreadsStillRunning = !shutdownAndAwait(pool);
        }
    }

    private boolean shutdownAndAwait(ExecutorService pool) {
        pool.shutdownNow();
        try {
            return pool.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private JsonNode request() throws Exception {
        var response = mockMvc.perform(post("/api/scan-sessions/" + sessionId + "/evaluations")
                        .with(jwt().jwt(j -> j.subject(String.valueOf(userId)))
                                .authorities(new SimpleGrantedAuthority("ROLE_LIBRARIAN"))))
                .andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return objectMapper.readTree(response.getContentAsString()).get("data");
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }
}
