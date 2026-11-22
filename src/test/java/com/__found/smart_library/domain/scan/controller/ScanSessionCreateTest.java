package com.__found.smart_library.domain.scan.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.__found.smart_library.domain.user.entity.AppUser;
import com.__found.smart_library.domain.user.entity.UserRole;
import com.__found.smart_library.domain.user.repository.AppUserRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.List;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 회차 생성 API 테스트(롤백). 예정일 규칙은 고정 Clock 이 필요해 {@link ScanSessionCreatePlannedDateTest} 에서,
 * 실제 커밋이 필요한 원자성·동시성은 {@link ScanSessionCreateConcurrencyTest} 에서 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ScanSessionCreateTest {

    private static final String URL = "/api/scan-sessions";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired AppUserRepository userRepository;
    @Autowired EntityManager em;

    /** shelf.zone 은 VARCHAR(30) 이므로 UUID 일부만 사용한다. */
    private final String prefix = "TEST-" + UUID.randomUUID().toString().substring(0, 8);
    private Long userId;

    @BeforeEach
    void setUp() {
        userId = userRepository.saveAndFlush(AppUser.builder()
                .email("scan-create-" + UUID.randomUUID() + "@test.com")
                .passwordHash("hash")
                .name("테스터")
                .role(UserRole.LIBRARIAN)
                .build()).getId();
    }

    // ---- A. 정상 생성 및 입력 검증 ----

    @Test
    @DisplayName("정상 요청이면 201과 함께 IN_PROGRESS 회차가 저장되고 응답에 단계 정보가 담긴다")
    void create_validRequest_returns201AndSavesInProgressSession() throws Exception {
        String zone = prefix + "-A";
        insertShelf(zone, 2, true);

        MvcResult result = perform(librarian(), body("2026 하반기 점검", zone, null))
                .andExpect(status().isCreated()).andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(data.get("id").asLong()).isPositive();
        assertThat(data.get("sessionName").asString()).isEqualTo("2026 하반기 점검");
        assertThat(data.get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(data.get("processingStage").asString()).isEqualTo("VISION_WAITING");
        assertThat(data.get("zone").asString()).isEqualTo(zone);
        assertThat(data.get("floorNo").asInt()).isEqualTo(2);
        assertThat(data.get("startedAt").isNull()).isFalse();
        assertThat(data.get("finalizedAt").isNull()).isTrue();
        assertThat(sessionCount(zone)).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap(
                "select status, started_at, created_by, target_area, session_name from scan_session where target_area = ?", zone);
        assertThat(row.get("status")).isEqualTo("IN_PROGRESS");
        assertThat(row.get("started_at")).isNotNull();
        assertThat(row.get("created_by")).isEqualTo(userId);
        assertThat(row.get("target_area")).isEqualTo(zone);
    }

    @Test
    @DisplayName("해당 자료실의 사용 중 서가만 WAITING 으로 등록되고 비활성·다른 자료실 서가는 제외된다")
    void create_validRequest_registersOnlyActiveShelvesOfZone() throws Exception {
        String zone = prefix + "-S";
        Long active1 = insertShelf(zone, 1, true);
        Long active2 = insertShelf(zone, 1, true);
        Long inactive = insertShelf(zone, 1, false);
        Long otherZone = insertShelf(prefix + "-O", 1, true);

        perform(librarian(), body("회차", zone, null)).andExpect(status().isCreated());
        em.flush();   // 롤백 테스트라 커밋 flush 가 없으므로, JDBC 로 읽기 전에 영속화 대기 중인 INSERT 를 반영한다

        List<Map<String, Object>> rows = jdbc.queryForList(
                "select shelf_id, scan_status from scan_session_shelf where scan_session_id = "
                        + "(select scan_session_id from scan_session where target_area = ?)", zone);
        assertThat(rows).extracting(r -> r.get("shelf_id")).containsExactlyInAnyOrder(active1, active2);
        assertThat(rows).extracting(r -> r.get("shelf_id")).doesNotContain(inactive, otherZone);
        assertThat(rows).extracting(r -> r.get("scan_status")).containsOnly("WAITING");
    }

    @Test
    @DisplayName("같은 이름의 회차도 (다른 자료실이라면) 생성할 수 있다")
    void create_sameSessionNameInOtherZone_returns201() throws Exception {
        insertShelf(prefix + "-1", 1, true);
        insertShelf(prefix + "-2", 1, true);

        perform(librarian(), body("같은 이름", prefix + "-1", null)).andExpect(status().isCreated());
        perform(librarian(), body("같은 이름", prefix + "-2", null)).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("회차명이 공백이면 400이고 저장되지 않는다")
    void create_blankSessionName_returns400AndNothingSaved() throws Exception {
        String zone = prefix + "-V1";
        insertShelf(zone, 1, true);

        perform(librarian(), body("   ", zone, null)).andExpect(status().isBadRequest());

        assertThat(sessionCount(zone)).isZero();
    }

    @Test
    @DisplayName("회차명이 101자면 400이고 저장되지 않는다 (100자는 허용)")
    void create_sessionNameOver100_returns400AndNothingSaved() throws Exception {
        String zone = prefix + "-V2";
        insertShelf(zone, 1, true);

        perform(librarian(), body("가".repeat(101), zone, null)).andExpect(status().isBadRequest());
        assertThat(sessionCount(zone)).isZero();

        perform(librarian(), body("가".repeat(100), zone, null)).andExpect(status().isCreated());
        assertThat(sessionCount(zone)).isEqualTo(1);
    }

    @Test
    @DisplayName("zone 이 없거나 공백이면 400이고 저장되지 않는다")
    void create_missingZone_returns400AndNothingSaved() throws Exception {
        long before = totalSessionCount();

        perform(librarian(), body("회차", null, null)).andExpect(status().isBadRequest());
        perform(librarian(), body("회차", " ", null)).andExpect(status().isBadRequest());

        assertThat(totalSessionCount()).isEqualTo(before);
    }

    // ---- B. 인증 및 중복 생성 ----

    @Test
    @DisplayName("사서는 201, 근로학생은 403, 미인증은 401 이고 거부된 요청은 저장되지 않는다")
    void create_byRole_librarian201_studentWorker403_anonymous401() throws Exception {
        String zone = prefix + "-R";
        insertShelf(zone, 1, true);

        perform(auth("ROLE_STUDENT_WORKER"), body("회차", zone, null)).andExpect(status().isForbidden());
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body("회차", zone, null)))
                .andExpect(status().isUnauthorized());
        assertThat(sessionCount(zone)).isZero();

        perform(librarian(), body("회차", zone, null)).andExpect(status().isCreated());
        assertThat(sessionCount(zone)).isEqualTo(1);
    }

    @Test
    @DisplayName("IN_PROGRESS 회차가 있는 자료실은 409(SCAN_002)이고 새 회차가 저장되지 않는다")
    void create_inProgressExists_returns409() throws Exception {
        String zone = prefix + "-D1";
        insertShelf(zone, 1, true);
        insertSession(zone, "IN_PROGRESS");

        perform(librarian(), body("회차", zone, null))
                .andExpect(status().isConflict())
                .andExpect(r -> assertThat(r.getResponse().getContentAsString()).contains("SCAN_002"));

        assertThat(sessionCount(zone)).isEqualTo(1);
    }

    @Test
    @DisplayName("REVIEW 회차가 있는 자료실도 409(SCAN_002)이다")
    void create_reviewExists_returns409() throws Exception {
        String zone = prefix + "-D2";
        insertShelf(zone, 1, true);
        insertSession(zone, "REVIEW");

        perform(librarian(), body("회차", zone, null)).andExpect(status().isConflict());

        assertThat(sessionCount(zone)).isEqualTo(1);
    }

    @Test
    @DisplayName("COMPLETED 회차만 있는 자료실은 새 회차를 만들 수 있다")
    void create_onlyCompletedExists_returns201() throws Exception {
        String zone = prefix + "-D3";
        insertShelf(zone, 1, true);
        insertSession(zone, "COMPLETED");

        perform(librarian(), body("회차", zone, null)).andExpect(status().isCreated());

        assertThat(sessionCount(zone)).isEqualTo(2);
    }

    @Test
    @DisplayName("다른 자료실에 미완료 회차가 있어도 이 자료실은 생성할 수 있다")
    void create_openSessionInOtherZone_returns201() throws Exception {
        String busy = prefix + "-B1";
        String idle = prefix + "-B2";
        insertShelf(busy, 1, true);
        insertShelf(idle, 1, true);
        insertSession(busy, "IN_PROGRESS");

        perform(librarian(), body("회차", idle, null)).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("존재하지 않는 자료실은 400(SCAN_003)이고 저장되지 않는다")
    void create_unknownZone_returns400AndNothingSaved() throws Exception {
        String zone = prefix + "-NONE";

        perform(librarian(), body("회차", zone, null))
                .andExpect(status().isBadRequest())
                .andExpect(r -> assertThat(r.getResponse().getContentAsString()).contains("SCAN_003"));

        assertThat(sessionCount(zone)).isZero();
    }

    @Test
    @DisplayName("활성 서가가 0개(비활성 서가만 있는)인 자료실은 400(SCAN_003)이고 저장되지 않는다")
    void create_onlyInactiveShelves_returns400AndNothingSaved() throws Exception {
        String zone = prefix + "-IN";
        insertShelf(zone, 1, false);

        perform(librarian(), body("회차", zone, null)).andExpect(status().isBadRequest());

        assertThat(sessionCount(zone)).isZero();
    }

    @Test
    @DisplayName("생성 직후 자료실 목록에서 해당 자료실의 hasOpenSession 이 true 가 된다")
    void create_thenGetRooms_hasOpenSessionTrue() throws Exception {
        String zone = prefix + "-L";
        insertShelf(zone, 1, true);
        perform(librarian(), body("회차", zone, LocalDate.now().plusDays(30))).andExpect(status().isCreated());

        String json = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get(URL + "/rooms").with(librarian()))
                .andReturn().getResponse().getContentAsString();
        for (JsonNode room : objectMapper.readTree(json).get("data")) {
            if (zone.equals(room.get("zone").asString())) {
                assertThat(room.get("hasOpenSession").asBoolean()).isTrue();
                return;
            }
        }
        throw new AssertionError("자료실 목록에 " + zone + " 이 없습니다");
    }

    // ---- helpers ----

    private org.springframework.test.web.servlet.ResultActions perform(RequestPostProcessor auth, String json)
            throws Exception {
        return mockMvc.perform(post(URL).with(auth).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private RequestPostProcessor librarian() {
        return auth("ROLE_LIBRARIAN");
    }

    private RequestPostProcessor auth(String role) {
        return jwt().jwt(j -> j.subject(String.valueOf(userId))).authorities(new SimpleGrantedAuthority(role));
    }

    private String body(String sessionName, String zone, LocalDate plannedStartDate) throws Exception {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("sessionName", sessionName);
        map.put("zone", zone);
        map.put("plannedStartDate", plannedStartDate == null ? null : plannedStartDate.toString());
        return objectMapper.writeValueAsString(map);
    }

    private long sessionCount(String zone) {
        return jdbc.queryForObject("select count(*) from scan_session where target_area = ?", Long.class, zone);
    }

    private long totalSessionCount() {
        return jdbc.queryForObject("select count(*) from scan_session", Long.class);
    }

    private Long insertShelf(String zone, int floorNo, boolean active) {
        return jdbc.queryForObject("insert into shelf (shelf_code, zone, floor_no, is_active, created_at, updated_at) "
                        + "values (?, ?, ?, ?, now(), now()) returning shelf_id", Long.class,
                prefix + "-" + UUID.randomUUID().toString().substring(0, 8), zone, floorNo, active);
    }

    private void insertSession(String zone, String status) {
        jdbc.update("insert into scan_session (session_name, status, target_area, created_by, created_at, updated_at) "
                + "values (?, ?, ?, ?, now(), now())", "기존 회차", status, zone, userId);
    }
}
