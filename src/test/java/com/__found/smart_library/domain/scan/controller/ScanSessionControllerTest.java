package com.__found.smart_library.domain.scan.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.__found.smart_library.domain.user.entity.AppUser;
import com.__found.smart_library.domain.user.entity.UserRole;
import com.__found.smart_library.domain.user.repository.AppUserRepository;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 장서점검 API 테스트. 인증은 jwt() 로 주입하므로 토큰 서명·Redis 세션 검증은 거치지 않고, 인가 규칙과 응답만 검증한다.
 * 개발 시드나 다른 데이터에 의존하지 않도록 테스트마다 고유한 zone 을 만들고, 응답에서 그 zone 항목만 골라 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ScanSessionControllerTest {

    private static final String ROOMS_URL = "/api/scan-sessions/rooms";

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
                .email("scan-api-" + UUID.randomUUID() + "@test.com")
                .passwordHash("hash")
                .name("테스터")
                .role(UserRole.LIBRARIAN)
                .build()).getId();
    }

    @Test
    @DisplayName("사용 중 서가가 있는 자료실이 서가 수와 층 정보와 함께 조회된다")
    void getRooms_activeShelves_returnsZoneWithShelfCountAndFloor() throws Exception {
        String zone = prefix + "-A";
        insertShelf(zone, 2, true);
        insertShelf(zone, 2, true);

        JsonNode room = findRoom(zone);

        assertThat(room).isNotNull();
        assertThat(room.get("shelfCount").asLong()).isEqualTo(2);
        assertThat(room.get("floorNo").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("비활성 서가는 서가 수에서 제외되고, 비활성 서가만 있는 자료실은 목록에 없다")
    void getRooms_inactiveShelves_excludedFromCountAndList() throws Exception {
        String mixed = prefix + "-M";
        String onlyInactive = prefix + "-I";
        insertShelf(mixed, 1, true);
        insertShelf(mixed, 1, false);
        insertShelf(onlyInactive, 1, false);

        assertThat(findRoom(mixed).get("shelfCount").asLong()).isEqualTo(1);
        assertThat(findRoom(onlyInactive)).isNull();
    }

    @Test
    @DisplayName("zone이 없는 서가는 어떤 자료실에도 포함되지 않는다")
    void getRooms_shelfWithoutZone_notIncluded() throws Exception {
        long beforeCount = countRooms();
        jdbc.update("insert into shelf (shelf_code, zone, floor_no, is_active, created_at, updated_at) "
                + "values (?, null, 1, true, now(), now())", prefix + "-NZ");

        assertThat(countRooms()).isEqualTo(beforeCount);
        assertThat(roomZones()).noneMatch(z -> z == null || z.isBlank());
    }

    @Test
    @DisplayName("zone이 빈 문자열이거나 공백뿐인 서가는 자료실 목록에서 제외된다")
    void getRooms_blankZone_notIncluded() throws Exception {
        long beforeCount = countRooms();
        for (String blank : List.of("", " ", "   ")) {
            jdbc.update("insert into shelf (shelf_code, zone, floor_no, is_active, created_at, updated_at) "
                    + "values (?, ?, 1, true, now(), now())", prefix + "-BL" + blank.length(), blank);
        }

        assertThat(countRooms()).isEqualTo(beforeCount);
        assertThat(roomZones()).noneMatch(z -> z == null || z.isBlank());
    }

    @Test
    @DisplayName("한 자료실에 층이 섞여 있으면 가장 낮은 층을 반환한다")
    void getRooms_mixedFloors_returnsLowestFloor() throws Exception {
        String zone = prefix + "-F";
        insertShelf(zone, 3, true);
        insertShelf(zone, 1, true);
        insertShelf(zone, 2, true);

        assertThat(findRoom(zone).get("floorNo").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("자료실 목록은 zone 오름차순으로 정렬된다")
    void getRooms_multipleZones_sortedByZoneAscending() throws Exception {
        String zoneB = prefix + "-B";
        String zoneA = prefix + "-A";
        insertShelf(zoneB, 1, true);
        insertShelf(zoneA, 1, true);

        List<String> zones = roomZones();

        assertThat(zones).contains(zoneA, zoneB);
        assertThat(zones.indexOf(zoneA)).isLessThan(zones.indexOf(zoneB));
    }

    @Test
    @DisplayName("회차가 없는 자료실은 hasOpenSession이 false다")
    void getRooms_noSession_hasOpenSessionFalse() throws Exception {
        String zone = prefix + "-N";
        insertShelf(zone, 1, true);

        assertThat(findRoom(zone).get("hasOpenSession").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("IN_PROGRESS 회차가 있는 자료실은 hasOpenSession이 true다")
    void getRooms_inProgressSession_hasOpenSessionTrue() throws Exception {
        String zone = prefix + "-P";
        insertShelf(zone, 1, true);
        insertSession(zone, "IN_PROGRESS");

        assertThat(findRoom(zone).get("hasOpenSession").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("REVIEW 회차가 있는 자료실은 hasOpenSession이 true다")
    void getRooms_reviewSession_hasOpenSessionTrue() throws Exception {
        String zone = prefix + "-R";
        insertShelf(zone, 1, true);
        insertSession(zone, "REVIEW");

        assertThat(findRoom(zone).get("hasOpenSession").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("COMPLETED 회차만 있는 자료실은 hasOpenSession이 false다")
    void getRooms_onlyCompletedSession_hasOpenSessionFalse() throws Exception {
        String zone = prefix + "-C";
        insertShelf(zone, 1, true);
        insertSession(zone, "COMPLETED");
        insertSession(zone, "COMPLETED");

        assertThat(findRoom(zone).get("hasOpenSession").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("COMPLETED와 IN_PROGRESS 회차가 함께 있으면 hasOpenSession이 true다")
    void getRooms_completedAndInProgressSessions_hasOpenSessionTrue() throws Exception {
        String zone = prefix + "-X";
        insertShelf(zone, 1, true);
        insertSession(zone, "COMPLETED");
        insertSession(zone, "IN_PROGRESS");

        assertThat(findRoom(zone).get("hasOpenSession").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("한 자료실의 미완료 회차는 다른 자료실의 hasOpenSession에 영향을 주지 않는다")
    void getRooms_openSessionInOtherZone_doesNotAffectThisZone() throws Exception {
        String busyZone = prefix + "-1";
        String idleZone = prefix + "-2";
        insertShelf(busyZone, 1, true);
        insertShelf(idleZone, 1, true);
        insertSession(busyZone, "IN_PROGRESS");

        assertThat(findRoom(busyZone).get("hasOpenSession").asBoolean()).isTrue();
        assertThat(findRoom(idleZone).get("hasOpenSession").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("자료실이 여러 개여도 목록 조회는 쿼리를 2개만 실행한다 (자료실별 개별 쿼리 없음)")
    void getRooms_manyZones_executesOnlyTwoQueries() throws Exception {
        for (String suffix : List.of("-Q1", "-Q2", "-Q3")) {
            insertShelf(prefix + suffix, 1, true);
            insertSession(prefix + suffix, "IN_PROGRESS");
        }
        em.flush();
        Statistics stats = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        mockMvc.perform(get(ROOMS_URL).with(auth("ROLE_LIBRARIAN"))).andExpect(status().isOk());

        assertThat(stats.getQueryExecutionCount()).isEqualTo(2);
        stats.setStatisticsEnabled(false);
    }

    @Test
    @DisplayName("사서와 근로학생 모두 자료실 목록을 조회할 수 있다")
    void getRooms_librarianAndStudentWorker_returnsOk() throws Exception {
        mockMvc.perform(get(ROOMS_URL).with(auth("ROLE_LIBRARIAN"))).andExpect(status().isOk());
        mockMvc.perform(get(ROOMS_URL).with(auth("ROLE_STUDENT_WORKER"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("인증 없이 요청하면 401을 반환한다")
    void getRooms_noAuthentication_returnsUnauthorized() throws Exception {
        mockMvc.perform(get(ROOMS_URL)).andExpect(status().isUnauthorized());
    }

    // ---- helpers ----

    private RequestPostProcessor auth(String role) {
        return jwt().jwt(j -> j.subject(String.valueOf(userId))).authorities(new SimpleGrantedAuthority(role));
    }

    private JsonNode rooms() throws Exception {
        String body = mockMvc.perform(get(ROOMS_URL).with(auth("ROLE_LIBRARIAN")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data");
    }

    private JsonNode findRoom(String zone) throws Exception {
        for (JsonNode room : rooms()) {
            if (zone.equals(room.get("zone").asString())) {
                return room;
            }
        }
        return null;
    }

    private List<String> roomZones() throws Exception {
        List<String> zones = new ArrayList<>();
        for (JsonNode room : rooms()) {
            zones.add(room.get("zone").asString());
        }
        return zones;
    }

    private long countRooms() throws Exception {
        return rooms().size();
    }

    private void insertShelf(String zone, int floorNo, boolean active) {
        jdbc.update("insert into shelf (shelf_code, zone, floor_no, is_active, created_at, updated_at) "
                        + "values (?, ?, ?, ?, now(), now())",
                prefix + "-" + UUID.randomUUID().toString().substring(0, 8), zone, floorNo, active);
    }

    private void insertSession(String zone, String status) {
        jdbc.update("insert into scan_session (session_name, status, target_area, created_by, created_at, updated_at) "
                + "values (?, ?, ?, ?, now(), now())", "테스트 회차", status, zone, userId);
    }
}
