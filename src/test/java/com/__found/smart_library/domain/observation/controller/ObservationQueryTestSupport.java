package com.__found.smart_library.domain.observation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.Map;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;

/** 조회 API 테스트 공통 도구. 데이터는 실제 수신 API 로 만들어 저장 형식과 조회가 어긋나지 않게 한다. */
abstract class ObservationQueryTestSupport extends VisionReceiveTestSupport {

    protected RequestPostProcessor as(String role) {
        return jwt().jwt(j -> j.subject(String.valueOf(userId))).authorities(new SimpleGrantedAuthority(role));
    }

    protected MvcResult fetch(String url) throws Exception {
        return mockMvc.perform(get(url).with(as("ROLE_LIBRARIAN"))).andReturn();
    }

    protected MvcResult fetchAs(String url, String role) throws Exception {
        return mockMvc.perform(get(url).with(as(role))).andReturn();
    }

    protected static String analysisUrl(Long sessionId) {
        return "/api/scan-sessions/" + sessionId + "/analysis";
    }

    protected static String shelfUrl(Long sessionId, Long shelfId) {
        return "/api/scan-sessions/" + sessionId + "/shelves/" + shelfId + "/observations";
    }

    protected static String observationUrl(Long sessionId, Long observationId) {
        return "/api/scan-sessions/" + sessionId + "/observations/" + observationId;
    }

    /** 수신 API 로 결과를 저장한다(201 이어야 한다). */
    protected void receive(Map<String, Object> body) throws Exception {
        MvcResult result = send(body);
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(201);
    }

    /** 성공 응답의 data 부분. */
    protected JsonNode data(MvcResult result) throws Exception {
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return json(result).get("data");
    }

    protected void assertError(MvcResult result, int httpStatus, String code) throws Exception {
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(httpStatus);
        assertThat(json(result).get("code").asString()).isEqualTo(code);
    }

    /** 서가에서 특정 배치 순서의 관측 ID. */
    protected Long observationId(Long sessionId, Long shelfId, int order) {
        return jdbc.queryForObject("select observation_id from book_observation where scan_session_id = ? "
                + "and detected_shelf_id = ? and detected_order = ?", Long.class, sessionId, shelfId, order);
    }
}
