package com.__found.smart_library.domain.evaluation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.__found.smart_library.domain.user.entity.AppUser;
import com.__found.smart_library.domain.user.entity.UserRole;
import com.__found.smart_library.domain.user.repository.AppUserRepository;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 평가 설정 API 테스트(롤백). 실제 커밋이 필요한 동시 저장은 {@link EvaluationSettingsConcurrencyTest} 에서 검증한다.
 * 개발 DB 에 이미 저장된 설정과 섞이지 않도록 각 테스트 안에서 기존 설정을 지운다(테스트 종료 시 롤백된다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EvaluationSettingsTest {

    private static final String URL = "/api/evaluation/settings";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired AppUserRepository userRepository;
    @Autowired EntityManager em;

    private Long userId;

    @BeforeEach
    void setUp() {
        userId = userRepository.saveAndFlush(AppUser.builder()
                .email("eval-settings-" + UUID.randomUUID() + "@test.com")
                .passwordHash("hash")
                .name("테스터")
                .role(UserRole.LIBRARIAN)
                .build()).getId();
        jdbc.update("delete from evaluation_criterion_weight");
        jdbc.update("delete from evaluation_profile");
    }

    // ---- 조회 ----

    @Test
    @DisplayName("저장된 설정이 없으면 기본값(isDefault=true, 동일 가중치, 기준 점수 50)을 돌려준다")
    void get_noSavedSettings_returnsDefaults() throws Exception {
        JsonNode data = data(mockMvc.perform(get(URL).with(auth("ROLE_LIBRARIAN"))).andExpect(status().isOk()));

        assertThat(data.get("isDefault").asBoolean()).isTrue();
        assertThat(data.get("profileVersion").isNull()).isTrue();
        assertThat(data.get("disposalThreshold").asDouble()).isEqualTo(50.0);
        assertThat(data.get("weights")).hasSize(5);
        for (JsonNode w : data.get("weights")) {
            assertThat(w.get("weight").asDouble()).isEqualTo(0.2);
        }
    }

    @Test
    @DisplayName("사서·근로학생은 조회할 수 있고 미인증은 401이다")
    void get_byRole_bothRolesAllowed_anonymous401() throws Exception {
        mockMvc.perform(get(URL).with(auth("ROLE_LIBRARIAN"))).andExpect(status().isOk());
        mockMvc.perform(get(URL).with(auth("ROLE_STUDENT_WORKER"))).andExpect(status().isOk());
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    // ---- 저장 ----

    @Test
    @DisplayName("저장하면 v1이 활성화되고 이후 조회에 저장한 값이 나온다")
    void update_firstSave_createsActiveV1() throws Exception {
        JsonNode saved = data(perform(auth("ROLE_LIBRARIAN"), body("60.5", 0.3, 0.2, 0.2, 0.2, 0.1))
                .andExpect(status().isOk()));

        assertThat(saved.get("profileVersion").asString()).isEqualTo("v1");
        assertThat(saved.get("isDefault").asBoolean()).isFalse();
        assertThat(saved.get("disposalThreshold").asDouble()).isEqualTo(60.5);
        assertThat(saved.get("configuredBy").asLong()).isEqualTo(userId);
        assertThat(saved.get("weights").get(0).get("criterion").asString()).isEqualTo("DEMAND");
        assertThat(saved.get("weights").get(0).get("weight").asDouble()).isEqualTo(0.3);

        JsonNode current = data(mockMvc.perform(get(URL).with(auth("ROLE_STUDENT_WORKER"))).andExpect(status().isOk()));
        assertThat(current.get("profileVersion").asString()).isEqualTo("v1");
        assertThat(current.get("disposalThreshold").asDouble()).isEqualTo(60.5);
        assertThat(current.get("weights")).hasSize(5);
        assertThat(current.get("weights").get(4).get("criterion").asString()).isEqualTo("PHYSICAL_CONDITION");
        assertThat(current.get("weights").get(4).get("weight").asDouble()).isEqualTo(0.1);
    }

    @Test
    @DisplayName("다시 저장하면 새 버전이 활성화되고 이전 버전은 비활성화되어 남는다 (활성은 항상 1개)")
    void update_secondSave_deactivatesPreviousVersionAndKeepsHistory() throws Exception {
        perform(auth("ROLE_LIBRARIAN"), body("50", 0.2, 0.2, 0.2, 0.2, 0.2)).andExpect(status().isOk());
        JsonNode second = data(perform(auth("ROLE_LIBRARIAN"), body("70", 0.4, 0.1, 0.1, 0.2, 0.2))
                .andExpect(status().isOk()));
        em.flush();

        assertThat(second.get("profileVersion").asString()).isEqualTo("v2");
        assertThat(jdbc.queryForObject("select count(*) from evaluation_profile", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "select count(*) from evaluation_profile where is_active", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select profile_version from evaluation_profile where is_active", String.class)).isEqualTo("v2");
        assertThat(jdbc.queryForObject(
                "select count(*) from evaluation_criterion_weight where profile_version = 'v1' and is_active",
                Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from evaluation_criterion_weight where profile_version = 'v1'", Long.class))
                .isEqualTo(5);
        assertThat(jdbc.queryForObject(
                "select count(*) from evaluation_criterion_weight where profile_version = 'v2' and is_active",
                Long.class)).isEqualTo(5);

        JsonNode current = data(mockMvc.perform(get(URL).with(auth("ROLE_LIBRARIAN"))).andExpect(status().isOk()));
        assertThat(current.get("profileVersion").asString()).isEqualTo("v2");
        assertThat(current.get("disposalThreshold").asDouble()).isEqualTo(70.0);
    }

    @Test
    @DisplayName("저장 요청은 사서만 가능하다: 근로학생 403, 미인증 401이고 아무것도 저장되지 않는다")
    void update_byRole_librarianOnly() throws Exception {
        perform(auth("ROLE_STUDENT_WORKER"), body("50", 0.2, 0.2, 0.2, 0.2, 0.2)).andExpect(status().isForbidden());
        mockMvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON)
                .content(body("50", 0.2, 0.2, 0.2, 0.2, 0.2))).andExpect(status().isUnauthorized());
        assertThat(profileCount()).isZero();

        perform(auth("ROLE_LIBRARIAN"), body("50", 0.2, 0.2, 0.2, 0.2, 0.2)).andExpect(status().isOk());
        assertThat(profileCount()).isEqualTo(1);
    }

    // ---- 입력 검증: 거부되면 저장되지 않고 기존 활성 설정도 그대로여야 한다 ----

    @Test
    @DisplayName("가중치 합이 1이 아니면 400(EVAL_001)이고 저장되지 않는다")
    void update_sumNotOne_returns400() throws Exception {
        perform(auth("ROLE_LIBRARIAN"), body("50", 0.2, 0.2, 0.2, 0.2, 0.3))
                .andExpect(status().isBadRequest());
        perform(auth("ROLE_LIBRARIAN"), body("50", 0.2, 0.2, 0.2, 0.2, 0.1999))
                .andExpect(status().isBadRequest());
        assertThat(profileCount()).isZero();
    }

    @Test
    @DisplayName("합이 1이 되는 소수 가중치(0.1+0.2+0.3+0.25+0.15)는 부동소수점 오차 없이 저장된다")
    void update_decimalSumExactlyOne_saved() throws Exception {
        perform(auth("ROLE_LIBRARIAN"), body("50", 0.1, 0.2, 0.3, 0.25, 0.15)).andExpect(status().isOk());
        assertThat(profileCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("평가축이 누락되거나 중복되면 400이다")
    void update_missingOrDuplicatedCriterion_returns400() throws Exception {
        List<Map<String, Object>> missing = new ArrayList<>(weights(0.25, 0.25, 0.25, 0.25, 0.0));
        missing.remove(4);
        perform(auth("ROLE_LIBRARIAN"), raw("50", missing)).andExpect(status().isBadRequest());

        List<Map<String, Object>> duplicated = new ArrayList<>(weights(0.2, 0.2, 0.2, 0.2, 0.2));
        duplicated.set(4, item("DEMAND", 0.2));
        perform(auth("ROLE_LIBRARIAN"), raw("50", duplicated)).andExpect(status().isBadRequest());

        perform(auth("ROLE_LIBRARIAN"), raw("50", List.of())).andExpect(status().isBadRequest());
        assertThat(profileCount()).isZero();
    }

    @Test
    @DisplayName("가중치가 음수이거나 1 초과이거나 소수 5자리 이상이면 400이다")
    void update_weightOutOfRange_returns400() throws Exception {
        perform(auth("ROLE_LIBRARIAN"), body("50", -0.1, 0.4, 0.3, 0.2, 0.2)).andExpect(status().isBadRequest());
        perform(auth("ROLE_LIBRARIAN"), body("50", 1.1, 0.0, 0.0, 0.0, -0.1)).andExpect(status().isBadRequest());
        perform(auth("ROLE_LIBRARIAN"), body("50", 0.20001, 0.19999, 0.2, 0.2, 0.2))
                .andExpect(status().isBadRequest());
        assertThat(profileCount()).isZero();
    }

    @Test
    @DisplayName("기준 점수가 0~100 밖이거나 없으면 400이고, 0과 100은 허용한다")
    void update_thresholdRange() throws Exception {
        perform(auth("ROLE_LIBRARIAN"), body("-0.01", 0.2, 0.2, 0.2, 0.2, 0.2)).andExpect(status().isBadRequest());
        perform(auth("ROLE_LIBRARIAN"), body("100.01", 0.2, 0.2, 0.2, 0.2, 0.2)).andExpect(status().isBadRequest());
        perform(auth("ROLE_LIBRARIAN"), body(null, 0.2, 0.2, 0.2, 0.2, 0.2)).andExpect(status().isBadRequest());
        assertThat(profileCount()).isZero();

        perform(auth("ROLE_LIBRARIAN"), body("0", 0.2, 0.2, 0.2, 0.2, 0.2)).andExpect(status().isOk());
        perform(auth("ROLE_LIBRARIAN"), body("100", 0.2, 0.2, 0.2, 0.2, 0.2)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("알 수 없는 평가축 코드는 400이다")
    void update_unknownCriterion_returns400() throws Exception {
        List<Map<String, Object>> items = new ArrayList<>(weights(0.2, 0.2, 0.2, 0.2, 0.2));
        items.set(0, item("UNKNOWN_AXIS", 0.2));
        perform(auth("ROLE_LIBRARIAN"), raw("50", items)).andExpect(status().isBadRequest());
        assertThat(profileCount()).isZero();
    }

    @Test
    @DisplayName("검증에 실패한 요청은 기존 활성 설정을 바꾸지 않는다")
    void update_invalidRequest_keepsExistingActiveSettings() throws Exception {
        perform(auth("ROLE_LIBRARIAN"), body("65", 0.3, 0.2, 0.2, 0.2, 0.1)).andExpect(status().isOk());

        perform(auth("ROLE_LIBRARIAN"), body("80", 0.5, 0.5, 0.5, 0.5, 0.5)).andExpect(status().isBadRequest());
        em.flush();

        JsonNode current = data(mockMvc.perform(get(URL).with(auth("ROLE_LIBRARIAN"))).andExpect(status().isOk()));
        assertThat(current.get("profileVersion").asString()).isEqualTo("v1");
        assertThat(current.get("disposalThreshold").asDouble()).isEqualTo(65.0);
        assertThat(profileCount()).isEqualTo(1);
    }

    // ---- helpers ----

    private ResultActions perform(RequestPostProcessor auth, String json) throws Exception {
        return mockMvc.perform(put(URL).with(auth).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private RequestPostProcessor auth(String role) {
        return jwt().jwt(j -> j.subject(String.valueOf(userId))).authorities(new SimpleGrantedAuthority(role));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }

    /** 평가축 순서(DEMAND, RARITY, DUPLICATION, CONTENT_VALIDITY, PHYSICAL_CONDITION)대로 가중치를 채운 요청. */
    private String body(String threshold, double demand, double rarity, double duplication, double content,
                        double physical) throws Exception {
        return raw(threshold, weights(demand, rarity, duplication, content, physical));
    }

    private String raw(String threshold, List<Map<String, Object>> weights) throws Exception {
        Map<String, Object> map = new LinkedHashMap<>();
        // 가중치·점수는 JSON 숫자로 보낸다(문자열이 아니라 숫자여야 실제 요청과 같다)
        map.put("disposalThreshold", threshold == null ? null : new java.math.BigDecimal(threshold));
        map.put("weights", weights);
        return objectMapper.writeValueAsString(map);
    }

    private List<Map<String, Object>> weights(double demand, double rarity, double duplication, double content,
                                              double physical) {
        return List.of(item("DEMAND", demand), item("RARITY", rarity), item("DUPLICATION", duplication),
                item("CONTENT_VALIDITY", content), item("PHYSICAL_CONDITION", physical));
    }

    private Map<String, Object> item(String criterion, double weight) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("criterion", criterion);
        m.put("weight", java.math.BigDecimal.valueOf(weight));
        return m;
    }

    private long profileCount() {
        em.flush();
        return jdbc.queryForObject("select count(*) from evaluation_profile", Long.class);
    }
}
