package com.__found.smart_library.domain.scan.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.__found.smart_library.domain.user.entity.AppUser;
import com.__found.smart_library.domain.user.entity.UserRole;
import com.__found.smart_library.domain.user.repository.AppUserRepository;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * 회차 목록·상세 조회 테스트(롤백). 개발 DB 에 다른 회차가 있어도 통과하도록
 * (1) 내 회차는 미래 시각(created_at)으로 넣어 목록 맨 위에 오게 하고, (2) 개수·페이지는 기준값(baseline)에 더해 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ScanSessionQueryTest {

    private static final String URL = "/api/scan-sessions";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired AppUserRepository userRepository;
    @Autowired EntityManager em;

    private final String prefix = "TEST-" + UUID.randomUUID().toString().substring(0, 8);
    private final LocalDateTime base = LocalDateTime.now().plusDays(1);
    private Long userId;

    @BeforeEach
    void setUp() {
        userId = userRepository.saveAndFlush(AppUser.builder()
                .email("scan-query-" + UUID.randomUUID() + "@test.com")
                .passwordHash("hash")
                .name("테스터")
                .role(UserRole.LIBRARIAN)
                .build()).getId();
    }

    // ---- 목록 ----

    @Test
    @DisplayName("목록 항목에 상태·단계·자료실·층·일시 필드가 담긴다")
    void getSessions_session_returnsAllFields() throws Exception {
        Long shelf = insertShelf(prefix + "-A", 2);
        Long id = insertSession(prefix + "-A", "IN_PROGRESS", base);
        register(id, shelf);

        JsonNode item = findById(list(""), id);

        assertThat(item.get("sessionName").asString()).isEqualTo("테스트 회차");
        assertThat(item.get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(item.get("processingStage").asString()).isEqualTo("VISION_WAITING");
        assertThat(item.get("zone").asString()).isEqualTo(prefix + "-A");
        assertThat(item.get("floorNo").asInt()).isEqualTo(2);
        assertThat(item.get("createdAt").isNull()).isFalse();
        assertThat(item.get("finalizedAt").isNull()).isTrue();
    }

    @Test
    @DisplayName("최신순(createdAt DESC, 같으면 id DESC)으로 정렬된다")
    void getSessions_multipleSessions_sortedByCreatedAtThenIdDescending() throws Exception {
        Long older1 = insertSession(prefix + "-S", "COMPLETED", base);
        Long older2 = insertSession(prefix + "-S", "COMPLETED", base);       // older1 과 같은 시각 → id 큰 쪽이 먼저
        Long newest = insertSession(prefix + "-S", "COMPLETED", base.plusHours(1));

        List<Long> ids = ids(list("size=3"));

        assertThat(ids).containsExactly(newest, older2, older1);
    }

    @Test
    @DisplayName("상태 필터를 지정하지 않으면 모든 상태가 조회된다")
    void getSessions_noStatusFilter_returnsAllStatuses() throws Exception {
        Long a = insertSession(prefix + "-F", "IN_PROGRESS", base);
        Long b = insertSession(prefix + "-F", "REVIEW", base);
        Long c = insertSession(prefix + "-F", "COMPLETED", base);

        assertThat(ids(list("size=100"))).contains(a, b, c);
    }

    @Test
    @DisplayName("상태 필터 단일 값은 해당 상태만 조회한다")
    void getSessions_singleStatusFilter_returnsOnlyThatStatus() throws Exception {
        Long a = insertSession(prefix + "-F", "IN_PROGRESS", base);
        Long b = insertSession(prefix + "-F", "REVIEW", base);
        Long c = insertSession(prefix + "-F", "COMPLETED", base);

        JsonNode result = list("status=COMPLETED&size=100");

        assertThat(ids(result)).contains(c).doesNotContain(a, b);
        assertThat(statuses(result)).containsOnly("COMPLETED");
    }

    @Test
    @DisplayName("상태 필터 다중 값(IN_PROGRESS+REVIEW)은 두 상태를 함께 조회한다")
    void getSessions_multipleStatusFilter_returnsBothStatuses() throws Exception {
        Long a = insertSession(prefix + "-F", "IN_PROGRESS", base);
        Long b = insertSession(prefix + "-F", "REVIEW", base);
        Long c = insertSession(prefix + "-F", "COMPLETED", base);

        JsonNode result = list("status=IN_PROGRESS&status=REVIEW&size=100");

        assertThat(ids(result)).contains(a, b).doesNotContain(c);
        assertThat(statuses(result)).doesNotContain("COMPLETED");
    }

    @Test
    @DisplayName("존재하지 않는 상태 값은 400이다")
    void getSessions_invalidStatus_returns400() throws Exception {
        mockMvc.perform(get(URL + "?status=UNKNOWN").with(librarian())).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("상태별 processingStage 는 IN_PROGRESS→VISION_WAITING, REVIEW·COMPLETED→ANALYSIS_DONE 이다")
    void getSessions_eachStatus_mapsProcessingStage() throws Exception {
        Long a = insertSession(prefix + "-P", "IN_PROGRESS", base);
        Long b = insertSession(prefix + "-P", "REVIEW", base);
        Long c = insertSession(prefix + "-P", "COMPLETED", base);

        JsonNode result = list("size=100");

        assertThat(findById(result, a).get("processingStage").asString()).isEqualTo("VISION_WAITING");
        assertThat(findById(result, b).get("processingStage").asString()).isEqualTo("ANALYSIS_DONE");
        assertThat(findById(result, c).get("processingStage").asString()).isEqualTo("ANALYSIS_DONE");
    }

    @Test
    @DisplayName("페이지 메타데이터로 다음 페이지 존재 여부를 정확히 판단할 수 있고, 모든 페이지를 순회하면 중복·누락이 없다")
    void getSessions_paging_metadataTellsWhetherNextPageExists() throws Exception {
        long baseline = list("size=1").get("totalElements").asLong();
        Set<Long> mine = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            mine.add(insertSession(prefix + "-G", "COMPLETED", base.plusMinutes(i)));
        }
        long expectedTotal = baseline + 5;
        int size = 2;
        int expectedPages = (int) Math.ceil(expectedTotal / (double) size);

        List<Long> collected = new ArrayList<>();
        int page = 0;
        while (true) {
            JsonNode result = list("page=" + page + "&size=" + size);
            assertThat(result.get("page").asInt()).isEqualTo(page);
            assertThat(result.get("size").asInt()).isEqualTo(size);
            assertThat(result.get("totalElements").asLong()).isEqualTo(expectedTotal);
            assertThat(result.get("totalPages").asInt()).isEqualTo(expectedPages);
            collected.addAll(ids(result));

            boolean hasNext = result.get("page").asInt() + 1 < result.get("totalPages").asInt();   // 프론트 '더보기' 판단 규칙
            assertThat(hasNext).isEqualTo(page < expectedPages - 1);
            if (!hasNext) {
                break;
            }
            page++;
        }

        assertThat(page).isEqualTo(expectedPages - 1);
        assertThat(collected).hasSize((int) expectedTotal).doesNotHaveDuplicates().containsAll(mine);
    }

    @Test
    @DisplayName("범위를 넘는 page 는 빈 목록이고 더보기 대상이 아니다")
    void getSessions_pageBeyondRange_returnsEmptyContent() throws Exception {
        insertSession(prefix + "-G", "COMPLETED", base);
        long total = list("size=1").get("totalElements").asLong();

        JsonNode result = list("page=" + (total + 10) + "&size=1");

        assertThat(result.get("content")).isEmpty();
        assertThat(result.get("totalElements").asLong()).isEqualTo(total);
    }

    @Test
    @DisplayName("page × size 가 offset 한도(int)를 넘으면 500이 아니라 400(COMMON_001)이다")
    void getSessions_offsetOverflow_returns400() throws Exception {
        for (String query : List.of("page=2147483647&size=100", "page=2147483647&size=2", "page=100000000&size=100")) {
            mockMvc.perform(get(URL + "?" + query).with(librarian()))
                    .andExpect(status().isBadRequest())
                    .andExpect(r -> assertThat(r.getResponse().getContentAsString()).contains("COMMON_001"));
        }
    }

    @Test
    @DisplayName("offset 이 한도 이내이면 큰 page 도 400이 아니라 빈 목록(200)이다")
    void getSessions_largeButValidOffset_returnsEmptyContent() throws Exception {
        assertThat(list("page=1000000&size=20").get("content")).isEmpty();
        assertThat(list("page=2147483647&size=1").get("content")).isEmpty();   // offset == Integer.MAX_VALUE
    }

    @Test
    @DisplayName("기본값은 page 0, size 20 이고 size 는 1~100, 음수 page 는 0으로 보정된다")
    void getSessions_defaultsAndBounds_areApplied() throws Exception {
        JsonNode defaults = list("");
        assertThat(defaults.get("page").asInt()).isZero();
        assertThat(defaults.get("size").asInt()).isEqualTo(20);

        assertThat(list("size=1000").get("size").asInt()).isEqualTo(100);
        assertThat(list("size=0").get("size").asInt()).isEqualTo(1);
        assertThat(list("page=-3").get("page").asInt()).isZero();
    }

    @Test
    @DisplayName("floorNo 는 등록된 서가들의 현재 층 중 최솟값이고, 서가가 없으면 null 이며, 층이 바뀌면 반영된다")
    void getSessions_floorNo_isMinOfRegisteredShelvesCurrentFloor() throws Exception {
        Long s3 = insertShelf(prefix + "-L", 3);
        Long s1 = insertShelf(prefix + "-L", 1);
        Long s2 = insertShelf(prefix + "-L", 2);
        Long withShelves = insertSession(prefix + "-L", "IN_PROGRESS", base);
        Long withoutShelves = insertSession(prefix + "-N", "COMPLETED", base);
        register(withShelves, s3);
        register(withShelves, s1);
        register(withShelves, s2);

        JsonNode result = list("size=100");
        assertThat(findById(result, withShelves).get("floorNo").asInt()).isEqualTo(1);
        assertThat(findById(result, withoutShelves).get("floorNo").isNull()).isTrue();

        jdbc.update("update shelf set floor_no = 5 where shelf_id = ?", s1);   // 현재 층 기준이므로 반영된다
        assertThat(findById(list("size=100"), withShelves).get("floorNo").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("목록은 회차 수와 무관하게 쿼리 3개(페이지·count·층 일괄)만 실행한다")
    void getSessions_manySessions_executesThreeQueries() throws Exception {
        Long shelf = insertShelf(prefix + "-Q", 1);
        for (int i = 0; i < 5; i++) {
            register(insertSession(prefix + "-Q", "COMPLETED", base.plusMinutes(i)), shelf);
        }
        Statistics stats = statistics();

        mockMvc.perform(get(URL + "?size=2").with(librarian())).andExpect(status().isOk());
        assertThat(stats.getQueryExecutionCount()).isEqualTo(3);

        stats.clear();
        mockMvc.perform(get(URL + "?size=4").with(librarian())).andExpect(status().isOk());
        assertThat(stats.getQueryExecutionCount()).isEqualTo(3);
        stats.setStatisticsEnabled(false);
    }

    // ---- 상세 ----

    @Test
    @DisplayName("상세 조회는 200이고 목록과 같은 필드를 반환한다")
    void getSession_existing_returnsDetail() throws Exception {
        Long shelf = insertShelf(prefix + "-D", 4);
        Long id = insertSession(prefix + "-D", "REVIEW", base);
        register(id, shelf);

        String json = mockMvc.perform(get(URL + "/" + id).with(librarian()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(json).get("data");

        assertThat(data.get("id").asLong()).isEqualTo(id);
        assertThat(data.get("status").asString()).isEqualTo("REVIEW");
        assertThat(data.get("processingStage").asString()).isEqualTo("ANALYSIS_DONE");
        assertThat(data.get("zone").asString()).isEqualTo(prefix + "-D");
        assertThat(data.get("floorNo").asInt()).isEqualTo(4);
    }

    @Test
    @DisplayName("상세 조회는 쿼리 2개(회차·층)만 실행한다")
    void getSession_existing_executesTwoQueries() throws Exception {
        Long id = insertSession(prefix + "-D", "COMPLETED", base);
        register(id, insertShelf(prefix + "-D", 1));
        Statistics stats = statistics();

        mockMvc.perform(get(URL + "/" + id).with(librarian())).andExpect(status().isOk());

        assertThat(stats.getQueryExecutionCount()).isLessThanOrEqualTo(2);
        stats.setStatisticsEnabled(false);
    }

    @Test
    @DisplayName("존재하지 않는 회차는 404(SCAN_001), 숫자가 아닌 id 는 400이다")
    void getSession_notFoundOrInvalidId_returns404Or400() throws Exception {
        mockMvc.perform(get(URL + "/999999999").with(librarian()))
                .andExpect(status().isNotFound())
                .andExpect(r -> assertThat(r.getResponse().getContentAsString()).contains("SCAN_001"));
        mockMvc.perform(get(URL + "/abc").with(librarian())).andExpect(status().isBadRequest());
    }

    // ---- 권한·경로 ----

    @Test
    @DisplayName("사서와 근로학생 모두 목록·상세를 조회할 수 있다")
    void getSessions_librarianAndStudentWorker_returnsOk() throws Exception {
        Long id = insertSession(prefix + "-R", "COMPLETED", base);

        for (String role : List.of("ROLE_LIBRARIAN", "ROLE_STUDENT_WORKER")) {
            mockMvc.perform(get(URL).with(auth(role))).andExpect(status().isOk());
            mockMvc.perform(get(URL + "/" + id).with(auth(role))).andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("인증 없이 목록·상세를 요청하면 401이다")
    void getSessions_noAuthentication_returns401() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL + "/1")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("/rooms 는 /{id} 에 가려지지 않고 자료실 목록을 반환한다")
    void getRooms_stillResolvedBeforeIdPath_returnsOk() throws Exception {
        String json = mockMvc.perform(get(URL + "/rooms").with(librarian()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(json).get("data").isArray()).isTrue();
    }

    // ---- helpers ----

    private JsonNode list(String query) throws Exception {
        String json = mockMvc.perform(get(URL + (query.isEmpty() ? "" : "?" + query)).with(librarian()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("data");
    }

    private List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.get("content").forEach(n -> ids.add(n.get("id").asLong()));
        return ids;
    }

    private List<String> statuses(JsonNode page) {
        List<String> statuses = new ArrayList<>();
        page.get("content").forEach(n -> statuses.add(n.get("status").asString()));
        return statuses;
    }

    private JsonNode findById(JsonNode page, Long id) {
        for (JsonNode n : page.get("content")) {
            if (n.get("id").asLong() == id) {
                return n;
            }
        }
        throw new AssertionError("목록에 id=" + id + " 가 없습니다");
    }

    private Statistics statistics() {
        Statistics stats = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        return stats;
    }

    private RequestPostProcessor librarian() {
        return auth("ROLE_LIBRARIAN");
    }

    private RequestPostProcessor auth(String role) {
        return jwt().jwt(j -> j.subject(String.valueOf(userId))).authorities(new SimpleGrantedAuthority(role));
    }

    private Long insertShelf(String zone, int floorNo) {
        return jdbc.queryForObject("insert into shelf (shelf_code, zone, floor_no, is_active, created_at, updated_at) "
                        + "values (?, ?, ?, true, now(), now()) returning shelf_id", Long.class,
                prefix + "-" + UUID.randomUUID().toString().substring(0, 8), zone, floorNo);
    }

    private Long insertSession(String zone, String status, LocalDateTime createdAt) {
        return jdbc.queryForObject("insert into scan_session (session_name, status, target_area, created_by, "
                        + "created_at, updated_at) values (?, ?, ?, ?, ?, ?) returning scan_session_id", Long.class,
                "테스트 회차", status, zone, userId, Timestamp.valueOf(createdAt), Timestamp.valueOf(createdAt));
    }

    private void register(Long sessionId, Long shelfId) {
        jdbc.update("insert into scan_session_shelf (scan_session_id, shelf_id, scan_status) values (?, ?, 'WAITING')",
                sessionId, shelfId);
    }
}
