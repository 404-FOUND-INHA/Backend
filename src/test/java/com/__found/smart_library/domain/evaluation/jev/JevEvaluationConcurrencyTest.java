package com.__found.smart_library.domain.evaluation.jev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.__found.smart_library.domain.user.entity.AppUser;
import com.__found.smart_library.domain.user.entity.UserRole;
import com.__found.smart_library.domain.user.repository.AppUserRepository;
import java.util.List;
import java.util.UUID;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 같은 회차의 동시 평가가 JEV 를 중복 호출하지 않는지, 같은 도서의 결과가 두 번 저장되지 않는지 검증한다.
 * 실제 커밋이 필요해 롤백 테스트를 쓰지 않으며, prefix 로 만든 데이터만 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"app.jev.api-key=test-key", "spring.datasource.hikari.maximum-pool-size=4"})
class JevEvaluationConcurrencyTest {

    private static final int BOOKS = 2;

    @MockitoBean JevClient jevClient;
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired AppUserRepository userRepository;
    @Autowired JevEvaluationWriter writer;
    @Autowired JevResultMapper mapper;

    private final String prefix = "TK" + UUID.randomUUID().toString().substring(0, 8);
    private Long userId;
    private Long sessionId;
    private final List<Long> bookIds = new java.util.ArrayList<>();
    private ExecutorService pool;
    private boolean requestThreadsStillRunning;

    @BeforeEach
    void setUp() {
        userId = userRepository.saveAndFlush(AppUser.builder()
                .email("jev-conc-" + UUID.randomUUID() + "@test.com")
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
                    + "created_at, updated_at) values (?, '동시성 도서', 'x', 'x', 'x', 'NORMAL', 'AVAILABLE', 0, 0, "
                    + "now(), now()) returning book_id", Long.class, prefix + "-B" + i);
            bookIds.add(bookId);
            jdbc.update("insert into book_observation (scan_session_id, book_id, detected_shelf_id, detected_order, "
                    + "analysis_status, received_at) values (?, ?, ?, ?, 'NORMAL', now())",
                    sessionId, bookId, shelfId, i);
        }
        pool = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void cleanUp() {
        requestThreadsStillRunning = !shutdownAndAwait(pool);
        if (requestThreadsStillRunning) {
            throw new IllegalStateException("요청 스레드가 종료되지 않아 DB 정리를 건너뜁니다. prefix=" + prefix);
        }
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
    @DisplayName("평가가 진행 중인 회차에 다시 요청하면 JEV 를 호출하지 않고 409(EVAL_004)이며, 끝나면 다시 요청할 수 있다")
    void secondRequestWhileRunningIsRejectedWithoutDuplicateCalls() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(jevClient.evaluate(any())).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("테스트가 평가를 풀어 주지 않았습니다.");
            }
            return JevEvaluationApiTest.validResponse();
        });
        Future<MockHttpServletResponse> first = pool.submit(this::request);
        try {
            assertThat(entered.await(10, TimeUnit.SECONDS)).as("첫 요청이 JEV 호출까지 도달").isTrue();

            MockHttpServletResponse second = request();

            assertThat(second.getStatus()).as(second.getContentAsString()).isEqualTo(409);
            assertThat(objectMapper.readTree(second.getContentAsString()).get("code").asString())
                    .isEqualTo("EVAL_004");
            verify(jevClient, times(1)).evaluate(any());   // 첫 요청이 호출 중인 1건뿐, 중복 호출 없음
        } finally {
            release.countDown();
        }

        JsonNode firstData = data(first.get(30, TimeUnit.SECONDS));
        assertThat(firstData.get("succeeded").asInt()).isEqualTo(BOOKS);
        assertThat(count("select count(*) from book_evaluation where evaluation_type = 'JEV' "
                + "and scan_session_id = ?", sessionId)).isEqualTo(BOOKS);

        JsonNode third = data(request());   // 끝난 뒤에는 진행 중 표시가 풀려 있어야 한다
        assertThat(third.get("succeeded").asInt()).isZero();
        assertThat(third.get("skipped").asInt()).isEqualTo(BOOKS);
        verify(jevClient, times(BOOKS)).evaluate(any());
    }

    @Test
    @DisplayName("같은 회차·도서의 JEV 결과를 두 번 저장하면 두 번째는 거부되고 아무것도 남지 않는다(DB 유니크 인덱스)")
    void duplicateSaveIsRejectedByDatabase() {
        List<JevCriterionResult> results = mapper.map(JevEvaluationApiTest.validResponse());
        Long bookId = bookIds.get(0);

        writer.save(sessionId, bookId, "jev-1.14.0", results);

        assertThatThrownBy(() -> writer.save(sessionId, bookId, "jev-1.14.0", results))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("select count(*) from book_evaluation where evaluation_type = 'JEV' "
                + "and scan_session_id = ? and book_id = ?", sessionId, bookId)).isEqualTo(1);
        assertThat(count("select count(*) from evaluation_criterion_result r join book_evaluation e "
                + "on e.evaluation_id = r.evaluation_id where e.scan_session_id = ?", sessionId)).isEqualTo(2);
    }

    private MockHttpServletResponse request() throws Exception {
        return mockMvc.perform(post("/api/scan-sessions/" + sessionId + "/jev-evaluations")
                        .with(jwt().jwt(j -> j.subject(String.valueOf(userId)))
                                .authorities(new SimpleGrantedAuthority("ROLE_LIBRARIAN"))))
                .andReturn().getResponse();
    }

    private JsonNode data(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return objectMapper.readTree(response.getContentAsString()).get("data");
    }

    private static boolean shutdownAndAwait(ExecutorService pool) {
        pool.shutdownNow();
        try {
            return pool.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }
}
