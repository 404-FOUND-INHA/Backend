package com.__found.smart_library.domain.evaluation.controller;

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
 * 동시에 설정을 저장해도 활성 버전이 1개이고 버전 번호가 겹치지 않는지 검증한다. 실제 커밋이 필요해 롤백 테스트를 쓰지 않는다.
 * 이 테스트가 만든 데이터만 지우고, 테스트 전에 활성이던 설정은 되살린다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EvaluationSettingsConcurrencyTest {

    private static final String URL = "/api/evaluation/settings";
    private static final int THREADS = 5;   // Hikari 풀(10)보다 작게 유지
    private static final String BODY = """
            {"disposalThreshold": 55,
             "weights": [{"criterion":"DEMAND","weight":0.2},{"criterion":"RARITY","weight":0.2},
                         {"criterion":"DUPLICATION","weight":0.2},{"criterion":"CONTENT_VALIDITY","weight":0.2},
                         {"criterion":"PHYSICAL_CONDITION","weight":0.2}]}
            """;

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AppUserRepository userRepository;

    private Long userId;
    private String previousActiveVersion;
    private boolean requestThreadsStillRunning;

    @BeforeEach
    void setUp() {
        userId = userRepository.saveAndFlush(AppUser.builder()
                .email("eval-conc-" + UUID.randomUUID() + "@test.com")
                .passwordHash("hash")
                .name("테스터")
                .role(UserRole.LIBRARIAN)
                .build()).getId();
        List<String> active = jdbc.queryForList(
                "select profile_version from evaluation_profile where is_active", String.class);
        previousActiveVersion = active.isEmpty() ? null : active.get(0);
    }

    @AfterEach
    void cleanUp() {
        if (requestThreadsStillRunning) {
            throw new IllegalStateException("요청 스레드가 종료되지 않아 DB 정리를 건너뜁니다. userId=" + userId);
        }
        jdbc.update("delete from evaluation_criterion_weight where configured_by = ?", userId);
        jdbc.update("delete from evaluation_profile where configured_by = ?", userId);
        if (previousActiveVersion != null) {
            jdbc.update("update evaluation_profile set is_active = true where profile_version = ?",
                    previousActiveVersion);
            jdbc.update("update evaluation_criterion_weight set is_active = true where profile_version = ?",
                    previousActiveVersion);
        }
        jdbc.update("delete from app_user where user_id = ?", userId);

        assertThat(count("select count(*) from evaluation_profile where configured_by = ?", userId)).isZero();
        assertThat(count("select count(*) from app_user where user_id = ?", userId)).isZero();
    }

    @Test
    @DisplayName("5개 동시 저장 요청이 모두 200이고, 버전은 겹치지 않으며, 활성 설정은 가중치 5개를 가진 1개뿐이다")
    void update_fiveConcurrentSaves_oneActiveVersion() throws Exception {
        List<Integer> statuses = runConcurrently();

        assertThat(statuses).containsOnly(200);
        assertThat(count("select count(*) from evaluation_profile where configured_by = ?", userId))
                .isEqualTo(THREADS);
        assertThat(count("select count(distinct profile_version) from evaluation_profile where configured_by = ?",
                userId)).isEqualTo(THREADS);
        assertThat(count("select count(*) from evaluation_profile where is_active", new Object[0])).isEqualTo(1);
        assertThat(count("select count(*) from evaluation_criterion_weight where is_active", new Object[0]))
                .isEqualTo(5);
        assertThat(count("select count(*) from evaluation_criterion_weight w join evaluation_profile p "
                + "on p.profile_version = w.profile_version where p.is_active and w.is_active", new Object[0]))
                .isEqualTo(5);
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
                    return requestSave();
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

    private int requestSave() throws Exception {
        return mockMvc.perform(put(URL)
                        .with(jwt().jwt(j -> j.subject(String.valueOf(userId)))
                                .authorities(new SimpleGrantedAuthority("ROLE_LIBRARIAN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andReturn().getResponse().getStatus();
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }
}
