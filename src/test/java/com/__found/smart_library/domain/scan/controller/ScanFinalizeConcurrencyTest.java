package com.__found.smart_library.domain.scan.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.__found.smart_library.domain.review.service.ReviewService;
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
import java.util.concurrent.TimeoutException;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 같은 회차를 동시에 확정해도 한 번만 처리되는지 검증한다. 실제 커밋이 필요해 롤백 테스트를 쓰지 않으며
 * prefix 로 만든 데이터만 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ScanFinalizeConcurrencyTest {

    private static final int THREADS = 4;   // Hikari 풀(10)보다 작게 유지

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AppUserRepository userRepository;
    @Autowired PlatformTransactionManager transactionManager;

    private final String prefix = "TX" + UUID.randomUUID().toString().substring(0, 8);
    private Long userId;
    private Long sessionId;
    private Long bookId;
    private Long shelfId;
    private boolean requestThreadsStillRunning;

    @BeforeEach
    void setUp() {
        userId = userRepository.saveAndFlush(AppUser.builder()
                .email("finalize-conc-" + UUID.randomUUID() + "@test.com")
                .passwordHash("hash")
                .name("테스터")
                .role(UserRole.LIBRARIAN)
                .build()).getId();
        shelfId = jdbc.queryForObject("insert into shelf (shelf_code, zone, floor_no, is_active, created_at, "
                + "updated_at) values (?, ?, 1, true, now(), now()) returning shelf_id", Long.class,
                prefix + "-S", prefix + "-Z");
        sessionId = jdbc.queryForObject("insert into scan_session (session_name, target_area, status, created_by, "
                + "created_at, updated_at) values ('동시성', ?, 'REVIEW', ?, now(), now()) "
                + "returning scan_session_id", Long.class, prefix + "-Z", userId);
        bookId = jdbc.queryForObject("insert into book (registration_no, title, call_number_raw, classification_no, "
                + "book_symbol, lifecycle_status, loan_status, total_loan_count, version, created_at, updated_at) "
                + "values (?, '동시성 도서', 'x', 'x', 'x', 'MISSING', 'AVAILABLE', 0, 0, now(), now()) "
                + "returning book_id", Long.class, prefix + "-B");
        jdbc.update("insert into book_observation (scan_session_id, book_id, detected_shelf_id, detected_order, "
                + "analysis_status, received_at) values (?, ?, ?, 1, 'NORMAL', now())", sessionId, bookId, shelfId);
    }

    @AfterEach
    void cleanUp() {
        if (requestThreadsStillRunning) {
            throw new IllegalStateException("요청 스레드가 종료되지 않아 DB 정리를 건너뜁니다. prefix=" + prefix);
        }
        jdbc.update("delete from audit_log where entity_type = 'BOOK' and entity_id = ?", bookId);
        jdbc.update("delete from book_observation where scan_session_id = ?", sessionId);
        jdbc.update("delete from scan_session where scan_session_id = ?", sessionId);
        jdbc.update("delete from book where registration_no like ?", prefix + "%");
        jdbc.update("delete from shelf where zone like ?", prefix + "%");
        jdbc.update("delete from app_user where user_id = ?", userId);

        assertThat(count("select count(*) from scan_session where scan_session_id = ?", sessionId)).isZero();
        assertThat(count("select count(*) from book where registration_no like ?", prefix + "%")).isZero();
        assertThat(count("select count(*) from app_user where user_id = ?", userId)).isZero();
    }

    @Test
    @DisplayName("같은 회차를 4개 동시에 확정하면 200은 정확히 1건, 나머지는 409이고 BOOK 은 한 번만 반영된다")
    void finalize_concurrently_appliedOnce() throws Exception {
        List<Integer> statuses = runConcurrently();

        assertThat(statuses.stream().filter(s -> s == 200)).as("statuses=" + statuses).hasSize(1);
        assertThat(statuses.stream().filter(s -> s == 409)).hasSize(THREADS - 1);
        assertThat(jdbc.queryForObject("select status from scan_session where scan_session_id = ?", String.class,
                sessionId)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select current_shelf_id from book where book_id = ?", Long.class, bookId))
                .isEqualTo(shelfId);
        assertThat(jdbc.queryForObject("select lifecycle_status from book where book_id = ?", String.class, bookId))
                .isEqualTo("NORMAL");
        assertThat(count("select count(*) from audit_log where entity_type = 'BOOK' and entity_id = ?", bookId))
                .isEqualTo(1);
    }

    /**
     * 같은 회차의 검토·확정은 하나의 락으로 직렬화된다. 테스트가 그 락을 쥐고 있는 동안(진행 중인 검토 역할) 확정 요청이
     * 처리되지 않고 대기해야 하며, 락이 풀리면 정상 처리된다. 확정이 락을 쓰지 않으면 대기 없이 바로 끝나 이 테스트가 실패한다.
     * (여러 확정 요청끼리의 중복 처리는 BOOK 의 낙관적 락이 한 번 더 막으므로 위 테스트로는 락 유무를 가릴 수 없다.)
     */
    @Test
    @DisplayName("같은 회차의 락을 다른 트랜잭션이 쥐고 있으면 확정 요청은 대기하고, 락이 풀리면 처리된다")
    void finalize_waitsWhileSessionLockHeld() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<Integer> pending = null;
        try {
            pending = new TransactionTemplate(transactionManager).execute(tx -> {
                jdbc.query("select pg_advisory_xact_lock(?, hashtext(?))", rs -> null,
                        ReviewService.REVIEW_LOCK_NAMESPACE, String.valueOf(sessionId));
                Future<Integer> future = pool.submit((Callable<Integer>) this::request);
                try {
                    future.get(1500, TimeUnit.MILLISECONDS);
                    throw new AssertionError("락이 잡혀 있는데 확정 요청이 대기하지 않고 끝났습니다");
                } catch (TimeoutException expected) {
                    // 대기 중이다: 정상
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                assertThat(jdbc.queryForObject("select status from scan_session where scan_session_id = ?",
                        String.class, sessionId)).isEqualTo("REVIEW");
                return future;
            });   // 여기서 커밋되어 락이 풀린다

            assertThat(pending.get(20, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(jdbc.queryForObject("select status from scan_session where scan_session_id = ?",
                    String.class, sessionId)).isEqualTo("COMPLETED");
        } finally {
            if (pending != null) {
                pending.cancel(true);
            }
            requestThreadsStillRunning = !shutdownAndAwait(pool);
        }
    }

    private List<Integer> runConcurrently() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                Callable<Integer> task = () -> {
                    ready.countDown();
                    start.await();
                    return request();
                };
                futures.add(pool.submit(task));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) {
                statuses.add(f.get(30, TimeUnit.SECONDS));
            }
            return statuses;
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

    private int request() throws Exception {
        return mockMvc.perform(post("/api/scan-sessions/" + sessionId + "/finalize")
                        .with(jwt().jwt(j -> j.subject(String.valueOf(userId)))
                                .authorities(new SimpleGrantedAuthority("ROLE_LIBRARIAN"))))
                .andReturn().getResponse().getStatus();
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }
}
