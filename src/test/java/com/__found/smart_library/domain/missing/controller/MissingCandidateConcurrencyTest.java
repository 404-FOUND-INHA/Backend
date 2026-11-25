package com.__found.smart_library.domain.missing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.__found.smart_library.domain.user.entity.AppUser;
import com.__found.smart_library.domain.user.entity.UserRole;
import com.__found.smart_library.domain.user.repository.AppUserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import tools.jackson.databind.ObjectMapper;

/**
 * 같은 후보에 현장 확인 결과를 동시에 기록해도 변경 이력이 끊기지 않는지 검증한다. 실제 커밋이 필요해
 * 롤백 테스트를 쓰지 않으며 prefix 로 만든 데이터만 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MissingCandidateConcurrencyTest {

    private static final List<String> STATUSES = List.of("FOUND", "NOT_FOUND", "LOST_CANDIDATE", "RESOLVED");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired AppUserRepository userRepository;

    private final String prefix = "TC" + UUID.randomUUID().toString().substring(0, 8);
    private Long userId;
    private Long candidateId;
    private boolean requestThreadsStillRunning;

    @BeforeEach
    void setUp() {
        userId = userRepository.saveAndFlush(AppUser.builder()
                .email("missing-conc-" + UUID.randomUUID() + "@test.com")
                .passwordHash("hash")
                .name("테스터")
                .role(UserRole.LIBRARIAN)
                .build()).getId();
        Long shelfId = jdbc.queryForObject("insert into shelf (shelf_code, zone, floor_no, is_active, created_at, "
                + "updated_at) values (?, ?, 1, true, now(), now()) returning shelf_id", Long.class,
                prefix + "-S", prefix + "-Z");
        Long bookId = jdbc.queryForObject("insert into book (registration_no, title, call_number_raw, "
                + "classification_no, book_symbol, expected_shelf_id, lifecycle_status, loan_status, "
                + "total_loan_count, created_at, updated_at, version) "
                + "values (?, '동시성 도서', '005 가1', '005', '가1', ?, 'NORMAL', 'AVAILABLE', 0, now(), now(), 0) "
                + "returning book_id", Long.class, prefix + "-B", shelfId);
        Long sessionId = jdbc.queryForObject("insert into scan_session (session_name, target_area, status, "
                + "created_by, created_at, updated_at) values ('동시성', ?, 'COMPLETED', ?, now(), now()) "
                + "returning scan_session_id", Long.class, prefix + "-Z", userId);
        candidateId = jdbc.queryForObject("insert into missing_candidate (scan_session_id, book_id, "
                + "expected_shelf_id, consecutive_missing_count, status, created_at) "
                + "values (?, ?, ?, 1, 'PENDING', now()) returning missing_id", Long.class, sessionId, bookId, shelfId);
    }

    @AfterEach
    void cleanUp() {
        if (requestThreadsStillRunning) {
            throw new IllegalStateException("요청 스레드가 종료되지 않아 DB 정리를 건너뜁니다. 잔여 데이터 prefix=" + prefix);
        }
        jdbc.update("delete from audit_log where entity_type = 'MISSING_CANDIDATE' and entity_id = ?", candidateId);
        jdbc.update("delete from missing_candidate where missing_id = ?", candidateId);
        jdbc.update("delete from scan_session where target_area like ?", prefix + "%");
        jdbc.update("delete from book where registration_no like ?", prefix + "%");
        jdbc.update("delete from shelf where zone like ?", prefix + "%");
        jdbc.update("delete from app_user where user_id = ?", userId);

        assertThat(jdbc.queryForObject("select count(*) from missing_candidate where missing_id = ?", Long.class,
                candidateId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from shelf where zone like ?", Long.class,
                prefix + "%")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from app_user where user_id = ?", Long.class,
                userId)).isZero();
    }

    @Test
    @DisplayName("같은 후보에 서로 다른 결과를 동시에 기록해도 변경 이력이 이전 상태에서 이어진다")
    void record_concurrently_auditChainIsConsistent() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(STATUSES.size());
        CountDownLatch ready = new CountDownLatch(STATUSES.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (String status : STATUSES) {
                Callable<Integer> task = () -> {
                    ready.countDown();
                    start.await();
                    return request(status);
                };
                futures.add(pool.submit(task));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<Integer> f : futures) {
                assertThat(f.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            }
        } finally {
            requestThreadsStillRunning = !shutdownAndAwait(pool);
        }

        List<Map<String, Object>> audits = jdbc.queryForList("select before_data::text as b, after_data::text as a "
                + "from audit_log where entity_type = 'MISSING_CANDIDATE' and entity_id = ? order by audit_log_id",
                candidateId);
        assertThat(audits).hasSize(STATUSES.size());
        // 행을 잠그지 않으면 두 요청이 같은 이전 상태(PENDING)를 읽어 이력이 갈라진다
        String expectedBefore = "PENDING";
        for (Map<String, Object> audit : audits) {
            assertThat(audit.get("b").toString()).contains("\"" + expectedBefore + "\"");
            expectedBefore = STATUSES.stream().filter(s -> audit.get("a").toString().contains("\"" + s + "\""))
                    .findFirst().orElseThrow();
        }
        assertThat(jdbc.queryForObject("select status from missing_candidate where missing_id = ?", String.class,
                candidateId)).isEqualTo(expectedBefore);
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

    private int request(String status) throws Exception {
        return mockMvc.perform(put("/api/missing-candidates/" + candidateId + "/review")
                        .with(jwt().jwt(j -> j.subject(String.valueOf(userId)))
                                .authorities(new SimpleGrantedAuthority("ROLE_LIBRARIAN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", status))))
                .andReturn().getResponse().getStatus();
    }
}
