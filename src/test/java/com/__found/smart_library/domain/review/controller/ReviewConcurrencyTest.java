package com.__found.smart_library.domain.review.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 서로 다른 관측에 같은 도서를 동시에 확정해도 한쪽만 성공하는지 검증한다. 실제 커밋이 필요해 롤백 테스트를 쓰지 않으며,
 * prefix 로 만든 데이터만 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReviewConcurrencyTest {

    private static final int THREADS = 4;   // Hikari 풀(10)보다 작게 유지

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AppUserRepository userRepository;

    private final String prefix = "TC" + UUID.randomUUID().toString().substring(0, 8);
    private Long userId;
    private Long sessionId;
    private Long bookId;
    private final List<Long> observationIds = new ArrayList<>();
    private boolean requestThreadsStillRunning;

    @BeforeEach
    void setUp() {
        userId = userRepository.saveAndFlush(AppUser.builder()
                .email("review-conc-" + UUID.randomUUID() + "@test.com")
                .passwordHash("hash")
                .name("테스터")
                .role(UserRole.LIBRARIAN)
                .build()).getId();
        Long shelfId = jdbc.queryForObject("insert into shelf (shelf_code, zone, floor_no, is_active, created_at, "
                + "updated_at) values (?, ?, 1, true, now(), now()) returning shelf_id", Long.class,
                prefix + "-S", prefix + "-Z");
        sessionId = jdbc.queryForObject("insert into scan_session (session_name, target_area, status, created_by, "
                + "created_at, updated_at) values ('동시성', ?, 'REVIEW', ?, now(), now()) "
                + "returning scan_session_id", Long.class, prefix + "-Z", userId);
        bookId = jdbc.queryForObject("insert into book (registration_no, title, call_number_raw, classification_no, "
                + "book_symbol, lifecycle_status, loan_status, total_loan_count, version, created_at, updated_at) "
                + "values (?, '동시성 도서', 'x', 'x', 'x', 'NORMAL', 'AVAILABLE', 0, 0, now(), now()) "
                + "returning book_id", Long.class, prefix + "-B");
        for (int i = 1; i <= THREADS; i++) {
            observationIds.add(jdbc.queryForObject("insert into book_observation (scan_session_id, detected_shelf_id, "
                    + "detected_order, analysis_status, status_reason, received_at) "
                    + "values (?, ?, ?, 'REVIEW_REQUIRED', 'NO_MATCH', now()) returning observation_id",
                    Long.class, sessionId, shelfId, i));
        }
    }

    @AfterEach
    void cleanUp() {
        if (requestThreadsStillRunning) {
            throw new IllegalStateException("요청 스레드가 종료되지 않아 DB 정리를 건너뜁니다. prefix=" + prefix);
        }
        jdbc.update("delete from audit_log where entity_type = 'REVIEW' and entity_id in "
                + "(select review_id from review where observation_id in "
                + "(select observation_id from book_observation where scan_session_id = ?))", sessionId);
        jdbc.update("delete from review where observation_id in "
                + "(select observation_id from book_observation where scan_session_id = ?)", sessionId);
        jdbc.update("delete from book_observation where scan_session_id = ?", sessionId);
        jdbc.update("delete from scan_session where scan_session_id = ?", sessionId);
        jdbc.update("delete from book where registration_no like ?", prefix + "%");
        jdbc.update("delete from shelf where zone like ?", prefix + "%");
        jdbc.update("delete from app_user where user_id = ?", userId);

        assertThat(count("select count(*) from scan_session where scan_session_id = ?", sessionId)).isZero();
        assertThat(count("select count(*) from review r join book_observation o on o.observation_id = r.observation_id "
                + "where o.scan_session_id = ?", sessionId)).isZero();
        assertThat(count("select count(*) from app_user where user_id = ?", userId)).isZero();
    }

    @Test
    @DisplayName("같은 도서를 서로 다른 관측 4건에 동시에 확정하면 200은 정확히 1건, 나머지는 409이고 검토는 1건만 남는다")
    void identify_sameBookConcurrently_exactlyOneSucceeds() throws Exception {
        List<Integer> statuses = runConcurrently();

        assertThat(statuses.stream().filter(s -> s == 200)).hasSize(1);
        assertThat(statuses.stream().filter(s -> s == 409)).hasSize(THREADS - 1);
        assertThat(count("select count(*) from review where final_book_id = ?", bookId)).isEqualTo(1);
    }

    private List<Integer> runConcurrently() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (Long observationId : observationIds) {
                Callable<Integer> task = () -> {
                    ready.countDown();
                    start.await();
                    return request(observationId);
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

    private int request(Long observationId) throws Exception {
        return mockMvc.perform(put("/api/scan-sessions/" + sessionId + "/observations/" + observationId + "/review")
                        .with(jwt().jwt(j -> j.subject(String.valueOf(userId)))
                                .authorities(new SimpleGrantedAuthority("ROLE_LIBRARIAN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"IDENTIFY_BOOK\",\"finalBookId\":" + bookId + "}"))
                .andReturn().getResponse().getStatus();
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }
}
