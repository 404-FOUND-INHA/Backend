package com.__found.smart_library.domain.scan.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.__found.smart_library.domain.scan.repository.ScanSessionShelfRepository;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * 실제 커밋이 일어나야 의미가 있는 원자성·동시성 테스트. 테스트 롤백(@Transactional)을 쓰지 않으므로
 * 데이터를 직접 만들고 @AfterEach 에서 이 테스트의 prefix 데이터만 지운 뒤 잔여 데이터가 없는지 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ScanSessionCreateConcurrencyTest {

    private static final String URL = "/api/scan-sessions";
    private static final int THREADS = 5;   // Hikari 풀(10)보다 작게 유지

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired AppUserRepository userRepository;
    @MockitoSpyBean ScanSessionShelfRepository scanSessionShelfRepository;

    private final String prefix = "TEST-" + UUID.randomUUID().toString().substring(0, 8);
    private Long userId;
    /** 요청 스레드가 종료 대기 시간 안에 끝나지 않았는지. true 이면 정리(DELETE)가 진행 중인 요청과 경쟁하므로 정리를 건너뛴다. */
    private boolean requestThreadsStillRunning;

    @BeforeEach
    void setUp() {
        userId = userRepository.saveAndFlush(AppUser.builder()
                .email("scan-conc-" + UUID.randomUUID() + "@test.com")
                .passwordHash("hash")
                .name("테스터")
                .role(UserRole.LIBRARIAN)
                .build()).getId();
    }

    @AfterEach
    void cleanUp() {
        // 진행 중인 요청이 남아 있으면 그 요청이 데이터를 다시 만들 수 있으므로 정리하지 않고 실패시킨다(prefix 로 수동 정리 가능).
        if (requestThreadsStillRunning) {
            throw new IllegalStateException("요청 스레드가 종료되지 않아 DB 정리를 건너뜁니다. 잔여 데이터 prefix=" + prefix);
        }
        jdbc.update("delete from scan_session_shelf where scan_session_id in "
                + "(select scan_session_id from scan_session where target_area like ?)", prefix + "%");
        jdbc.update("delete from scan_session where target_area like ?", prefix + "%");
        jdbc.update("delete from shelf where zone like ?", prefix + "%");
        jdbc.update("delete from app_user where user_id = ?", userId);

        // 정리 확인: 이 테스트가 만든 데이터가 남아 있으면 실패
        assertThat(count("select count(*) from scan_session where target_area like ?", prefix + "%")).isZero();
        assertThat(count("select count(*) from shelf where zone like ?", prefix + "%")).isZero();
        assertThat(count("select count(*) from app_user where user_id = ?", userId)).isZero();
    }

    @Test
    @DisplayName("서가 등록에 실패하면 이미 저장된 회차까지 함께 롤백된다 (원자성)")
    void create_shelfRegistrationFails_sessionRolledBack() throws Exception {
        String zone = prefix + "-T";
        insertShelf(zone);
        doThrow(new IllegalStateException("서가 등록 실패(테스트)")).when(scanSessionShelfRepository).saveAll(any());

        int status = requestCreate(zone);

        assertThat(status).isEqualTo(500);
        assertThat(count("select count(*) from scan_session where target_area = ?", zone)).isZero();
        assertThat(count("select count(*) from scan_session_shelf where shelf_id in "
                + "(select shelf_id from shelf where zone = ?)", zone)).isZero();
    }

    @Test
    @DisplayName("같은 자료실에 5개 동시 생성 요청을 보내면 201은 정확히 1개, 나머지는 409이고 미완료 회차는 1건이다")
    void create_fiveConcurrentRequestsSameZone_exactlyOneCreated() throws Exception {
        String zone = prefix + "-C";
        insertShelf(zone);

        List<Integer> statuses = runConcurrently(List.of(zone, zone, zone, zone, zone));

        assertThat(statuses.stream().filter(s -> s == 201)).hasSize(1);
        assertThat(statuses.stream().filter(s -> s == 409)).hasSize(THREADS - 1);
        assertThat(count("select count(*) from scan_session where target_area = ? "
                + "and status in ('IN_PROGRESS','REVIEW')", zone)).isEqualTo(1);
        assertThat(count("select count(*) from scan_session_shelf where scan_session_id = "
                + "(select scan_session_id from scan_session where target_area = ?)", zone)).isEqualTo(1);
    }

    @Test
    @DisplayName("서로 다른 자료실에 동시에 생성 요청하면 각각 모두 201이다 (락이 전역이 아님)")
    void create_concurrentRequestsDifferentZones_allCreated() throws Exception {
        List<String> zones = List.of(prefix + "-X1", prefix + "-X2", prefix + "-X3");
        zones.forEach(this::insertShelf);

        List<Integer> statuses = runConcurrently(zones);

        assertThat(statuses).containsOnly(201);
        for (String zone : zones) {
            assertThat(count("select count(*) from scan_session where target_area = ? "
                    + "and status = 'IN_PROGRESS'", zone)).isEqualTo(1);
        }
    }

    // ---- helpers ----

    /** 모든 스레드가 준비된 뒤 동시에 출발시켜 각 요청의 HTTP 상태를 모은다. */
    private List<Integer> runConcurrently(List<String> zones) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(zones.size());
        CountDownLatch ready = new CountDownLatch(zones.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (String zone : zones) {
                Callable<Integer> task = () -> {
                    ready.countDown();
                    start.await();
                    return requestCreate(zone);
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

    /** 스레드풀을 중단하고 모든 스레드가 끝날 때까지 기다린다. 제한 시간 안에 끝나지 않으면 false. */
    private boolean shutdownAndAwait(ExecutorService pool) {
        pool.shutdownNow();
        try {
            return pool.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private int requestCreate(String zone) throws Exception {
        String json = objectMapper.writeValueAsString(java.util.Map.of("sessionName", "동시성 테스트", "zone", zone));
        return mockMvc.perform(post(URL)
                        .with(jwt().jwt(j -> j.subject(String.valueOf(userId)))
                                .authorities(new SimpleGrantedAuthority("ROLE_LIBRARIAN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn().getResponse().getStatus();
    }

    private void insertShelf(String zone) {
        jdbc.update("insert into shelf (shelf_code, zone, floor_no, is_active, created_at, updated_at) "
                        + "values (?, ?, 1, true, now(), now())",
                prefix + "-" + UUID.randomUUID().toString().substring(0, 8), zone);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }
}
