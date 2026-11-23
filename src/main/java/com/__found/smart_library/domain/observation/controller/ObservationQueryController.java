package com.__found.smart_library.domain.observation.controller;

import com.__found.smart_library.domain.observation.dto.ObservationDetailResponse;
import com.__found.smart_library.domain.observation.dto.ScanAnalysisResponse;
import com.__found.smart_library.domain.observation.dto.ShelfObservationsResponse;
import com.__found.smart_library.domain.observation.service.ObservationQueryService;
import com.__found.smart_library.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 자동 장서점검 분석 결과 조회(읽기 전용). 로그인한 사용자라면 누구나 조회할 수 있다(A1 조회 API 와 동일).
 * 결과는 Backend 의 자동 분석 결과이며 사서의 최종 검토 결과를 포함하지 않는다.
 */
@RestController
@RequestMapping("/api/scan-sessions")
@RequiredArgsConstructor
public class ObservationQueryController {

    private final ObservationQueryService observationQueryService;

    /** 회차 분석 요약과 서가별 수신·분석 현황. */
    @GetMapping("/{sessionId}/analysis")
    public ApiResponse<ScanAnalysisResponse> getAnalysis(@PathVariable Long sessionId) {
        return ApiResponse.ok(observationQueryService.getAnalysis(sessionId));
    }

    /** 서가의 관측 도서 목록(배치 순서). */
    @GetMapping("/{sessionId}/shelves/{shelfId}/observations")
    public ApiResponse<ShelfObservationsResponse> getShelfObservations(
            @PathVariable Long sessionId, @PathVariable Long shelfId) {
        return ApiResponse.ok(observationQueryService.getShelfObservations(sessionId, shelfId));
    }

    /** 관측 도서 상세(원문·파싱 결과·매칭 도서·후보·중복 관측). */
    @GetMapping("/{sessionId}/observations/{observationId}")
    public ApiResponse<ObservationDetailResponse> getObservation(
            @PathVariable Long sessionId, @PathVariable Long observationId) {
        return ApiResponse.ok(observationQueryService.getObservation(sessionId, observationId));
    }
}
