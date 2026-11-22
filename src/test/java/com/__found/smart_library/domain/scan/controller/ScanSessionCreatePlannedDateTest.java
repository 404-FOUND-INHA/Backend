package com.__found.smart_library.domain.scan.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.__found.smart_library.domain.user.entity.AppUser;
import com.__found.smart_library.domain.user.entity.UserRole;
import com.__found.smart_library.domain.user.repository.AppUserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 점검 예정일 규칙 테스트. 서비스가 주입받는 Clock 을 "서울 2026-06-16 00:30"(UTC 로는 06-15 15:30)으로 고정해
 * 실행 시각·서버 시간대와 무관하게 결과가 같도록 한다. UTC 날짜(06-15)로 비교하는 잘못된 구현이면 06-15 가 통과해 버리므로
 * 서울 기준 비교인지도 함께 확인된다.
 * 고정 Clock 은 이 클래스의 중첩 TestConfiguration 으로만 등록되어 다른 테스트의 컨텍스트에는 영향을 주지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ScanSessionCreatePlannedDateTest {

    private static final String URL = "/api/scan-sessions";

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-06-15T15:30:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired AppUserRepository userRepository;

    private final String prefix = "TEST-" + UUID.randomUUID().toString().substring(0, 8);
    private Long userId;
    private String zone;

    @BeforeEach
    void setUp() {
        userId = userRepository.saveAndFlush(AppUser.builder()
                .email("scan-date-" + UUID.randomUUID() + "@test.com")
                .passwordHash("hash")
                .name("테스터")
                .role(UserRole.LIBRARIAN)
                .build()).getId();
        zone = prefix + "-D";
        jdbc.update("insert into shelf (shelf_code, zone, floor_no, is_active, created_at, updated_at) "
                + "values (?, ?, 1, true, now(), now())", prefix + "-SH", zone);
    }

    @Test
    @DisplayName("서울 기준 어제 날짜(UTC 로는 오늘)는 과거이므로 400이고 저장되지 않는다")
    void create_plannedDateYesterdayInSeoul_returns400() throws Exception {
        perform("2026-06-15").andExpect(status().isBadRequest());

        assertThat(sessionCount()).isZero();
    }

    @Test
    @DisplayName("한참 지난 과거 날짜는 400이다")
    void create_plannedDateLongPast_returns400() throws Exception {
        perform("2020-01-01").andExpect(status().isBadRequest());

        assertThat(sessionCount()).isZero();
    }

    @Test
    @DisplayName("서울 기준 오늘 날짜는 허용되어 201이고 예정일이 저장된다")
    void create_plannedDateTodayInSeoul_returns201() throws Exception {
        perform("2026-06-16").andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("select planned_start_date::text from scan_session where target_area = ?",
                String.class, zone)).isEqualTo("2026-06-16");
    }

    @Test
    @DisplayName("미래 날짜는 허용되어 201이다")
    void create_plannedDateFuture_returns201() throws Exception {
        perform("2026-06-17").andExpect(status().isCreated());
    }

    @Test
    @DisplayName("예정일이 null 이면 201이고 예정일은 NULL 로 저장된다")
    void create_plannedDateNull_returns201() throws Exception {
        perform(null).andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("select planned_start_date from scan_session where target_area = ?",
                java.sql.Date.class, zone)).isNull();
    }

    private ResultActions perform(String plannedStartDate) throws Exception {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sessionName", "예정일 테스트");
        map.put("zone", zone);
        map.put("plannedStartDate", plannedStartDate);
        return mockMvc.perform(post(URL)
                .with(jwt().jwt(j -> j.subject(String.valueOf(userId)))
                        .authorities(new SimpleGrantedAuthority("ROLE_LIBRARIAN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(map)));
    }

    private long sessionCount() {
        return jdbc.queryForObject("select count(*) from scan_session where target_area = ?", Long.class, zone);
    }
}
