package com.__found.smart_library.domain.evaluation.controller;

import com.__found.smart_library.domain.evaluation.dto.EvaluationSettingsResponse;
import com.__found.smart_library.domain.evaluation.dto.EvaluationSettingsUpdateRequest;
import com.__found.smart_library.domain.evaluation.service.EvaluationSettingsService;
import com.__found.smart_library.global.response.ApiResponse;
import com.__found.smart_library.global.security.AuthClaims;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/evaluation/settings")
@RequiredArgsConstructor
public class EvaluationSettingsController {

    private final EvaluationSettingsService settingsService;

    /** 현재 평가 설정(가중치, 후보 기준 점수). 사서·근로생 모두 조회할 수 있다(근로생은 읽기 전용). */
    @GetMapping
    public ApiResponse<EvaluationSettingsResponse> get() {
        return ApiResponse.ok(settingsService.getActive());
    }

    /** 평가 설정 저장(사서 전용). 저장하면 새 버전이 활성화된다. */
    @PutMapping
    @PreAuthorize("hasRole('LIBRARIAN')")
    public ApiResponse<EvaluationSettingsResponse> update(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody EvaluationSettingsUpdateRequest request) {
        return ApiResponse.ok(settingsService.update(request, AuthClaims.userId(jwt)));
    }
}
