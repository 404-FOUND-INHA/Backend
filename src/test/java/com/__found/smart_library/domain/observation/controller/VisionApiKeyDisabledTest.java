package com.__found.smart_library.domain.observation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * API Key 가 설정되지 않은 환경(운영 기본값)에서는 어떤 헤더 값으로도 인증되지 않아야 한다(fail-closed).
 * 설정이 비어 있고 헤더도 비어 있을 때 "일치"로 판정되는 실수를 막는다.
 */
@SpringBootTest(properties = "app.vision.api-key=")
@AutoConfigureMockMvc
class VisionApiKeyDisabledTest {

    @Autowired MockMvc mockMvc;

    @Test
    @DisplayName("키가 설정되지 않으면 헤더가 없거나 비어 있거나 임의의 값이어도 모두 401 이다")
    void receive_keyNotConfigured_rejectsEverything() throws Exception {
        for (String header : new String[] {null, "", " ", "any-value", "x".repeat(40)}) {
            var request = post("/api/vision/observations").contentType(MediaType.APPLICATION_JSON).content("{}");
            if (header != null) {
                request = request.header("X-Vision-Api-Key", header);
            }

            int status = mockMvc.perform(request).andReturn().getResponse().getStatus();

            assertThat(status).as("header=[%s]", header).isEqualTo(401);
        }
    }
}
