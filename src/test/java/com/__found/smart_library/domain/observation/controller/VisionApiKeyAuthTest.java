package com.__found.smart_library.domain.observation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Vision API Key 인증 테스트. 실제 보안 필터 체인을 거치며, 인증에 실패하면 어떤 데이터도 저장되지 않아야 한다.
 */
@Import(VisionTestParserConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class VisionApiKeyAuthTest extends VisionReceiveTestSupport {

    @Test
    @DisplayName("API Key 헤더가 없으면 401 이고 아무것도 저장되지 않는다")
    void receive_missingKey_returns401AndSavesNothing() throws Exception {
        Fixture fx = fixture(1);
        MvcResult result = mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(emptyBody(fx)))).andReturn();

        assertUnauthorized(result, fx);
    }

    @Test
    @DisplayName("API Key 가 다르면 401 이다 (빈 값, 대소문자·공백 차이, 일부만 일치 포함)")
    void receive_wrongKey_returns401() throws Exception {
        Fixture fx = fixture(1);
        for (String wrong : new String[] {"", " ", "wrong-key", API_KEY + " ", " " + API_KEY, API_KEY.toUpperCase(),
                API_KEY.substring(0, API_KEY.length() - 1), API_KEY + "x"}) {
            MvcResult result = mockMvc.perform(post(URL).header(HEADER, wrong).contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(emptyBody(fx)))).andReturn();
            assertUnauthorized(result, fx);
        }
    }

    @Test
    @DisplayName("올바른 키라도 다른 헤더 이름으로 보내면 401 이다")
    void receive_keyInOtherHeader_returns401() throws Exception {
        Fixture fx = fixture(1);
        MvcResult result = mockMvc.perform(post(URL).header("X-Api-Key", API_KEY)
                .header("Authorization", "Bearer " + API_KEY).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(emptyBody(fx)))).andReturn();

        assertUnauthorized(result, fx);
    }

    @Test
    @DisplayName("올바른 키로 보내면 인증을 통과해 입력 검증 단계까지 도달한다 (빈 본문은 400)")
    void receive_validKey_passesAuthentication() throws Exception {
        MvcResult result = sendRaw("{}");

        assertThat(status(result)).isEqualTo(400);
        assertThat(json(result).get("code").asString()).isEqualTo("COMMON_001");
    }

    @Test
    @DisplayName("사서 JWT 는 Vision 경로에서 인증으로 쓰이지 않는다 (Bearer 토큰은 무시되어 401)")
    void receive_librarianBearerToken_returns401() throws Exception {
        Fixture fx = fixture(1);
        MvcResult result = mockMvc.perform(post(URL).header("Authorization", "Bearer some.jwt.token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(emptyBody(fx)))).andReturn();

        assertUnauthorized(result, fx);
    }

    @Test
    @DisplayName("이미 사서로 인증된 주체도 VISION 권한이 없으면 403 이다")
    void receive_authenticatedLibrarianWithoutVisionRole_returns403() throws Exception {
        Fixture fx = fixture(1);
        MvcResult result = mockMvc.perform(post(URL)
                .with(jwt().jwt(j -> j.subject(String.valueOf(userId)))
                        .authorities(new SimpleGrantedAuthority("ROLE_LIBRARIAN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(emptyBody(fx)))).andReturn();

        assertThat(status(result)).isEqualTo(403);
        assertThat(sessionStatus(fx.sessionId())).isEqualTo("IN_PROGRESS");
        assertThat(observationCount(fx.sessionId())).isZero();
    }

    @Test
    @DisplayName("Vision 키는 허용된 POST /api/vision/observations 외의 /api/vision 요청에서는 403 이다")
    void visionKey_otherVisionPathsAndMethods_returns403() throws Exception {
        assertThat(mockMvc.perform(get(URL).header(HEADER, API_KEY)).andReturn().getResponse().getStatus())
                .isEqualTo(403);
        assertThat(mockMvc.perform(post("/api/vision/other").header(HEADER, API_KEY)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus())
                .isEqualTo(403);
        // 키가 없으면 어떤 경로든 401
        assertThat(mockMvc.perform(get(URL)).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("Vision 키로는 다른 API(JWT 체인)에 접근할 수 없다")
    void visionKey_otherApi_returns401() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/scan-sessions/rooms").header(HEADER, API_KEY)).andReturn();

        assertThat(status(result)).isEqualTo(401);
    }

    @Test
    @DisplayName("기존 JWT 체인은 그대로 동작한다 (jwt() 인증 주체는 기존 API 에 접근 가능)")
    void jwtChain_unaffectedByVisionChain() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/scan-sessions/rooms").with(jwt())).andReturn();

        assertThat(status(result)).isEqualTo(200);
    }

    @Test
    @DisplayName("키 값은 응답과 로그 어디에도 노출되지 않는다 (성공·실패 모두)")
    void receive_keyIsNeverExposed(CapturedOutput output) throws Exception {
        Fixture fx = fixture(1);
        String wrong = "wrong-" + API_KEY.substring(6);

        MvcResult failure = mockMvc.perform(post(URL).header(HEADER, wrong).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(emptyBody(fx)))).andReturn();
        MvcResult success = send(emptyBody(fx));

        assertThat(status(failure)).isEqualTo(401);
        assertThat(status(success)).isEqualTo(201);
        assertThat(failure.getResponse().getContentAsString()).doesNotContain(API_KEY).doesNotContain(wrong);
        assertThat(success.getResponse().getContentAsString()).doesNotContain(API_KEY);
        assertThat(output.getAll()).doesNotContain(API_KEY).doesNotContain(wrong);
    }

    private void assertUnauthorized(MvcResult result, Fixture fx) throws Exception {
        assertThat(status(result)).isEqualTo(401);
        assertThat(json(result).get("code").asString()).isEqualTo("COMMON_004");
        assertThat(result.getResponse().getContentAsString()).doesNotContain(API_KEY);
        assertThat(sessionStatus(fx.sessionId())).isEqualTo("IN_PROGRESS");
        assertThat(observationCount(fx.sessionId())).isZero();
        assertThat(count("select count(*) from scan_session_shelf where scan_session_id = ? "
                + "and scan_status <> 'WAITING'", fx.sessionId())).isZero();
    }
}
