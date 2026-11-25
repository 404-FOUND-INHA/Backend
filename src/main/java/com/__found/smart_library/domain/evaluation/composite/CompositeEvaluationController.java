package com.__found.smart_library.domain.evaluation.composite;

import com.__found.smart_library.global.response.ApiResponse;
import com.__found.smart_library.global.security.AuthClaims;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 회차 종합 평가 실행(사서 전용). */
@RestController
@RequestMapping("/api/scan-sessions/{sessionId}")
@RequiredArgsConstructor
public class CompositeEvaluationController {

    private final CompositeEvaluationService compositeEvaluationService;

    /**
     * 점검이 확정(COMPLETED)된 회차의 도서마다 종합 점수와 폐기 검토 후보 여부를 계산해 저장한다. 다시 실행하면
     * 지금 활성인 가중치로 다시 계산해 달라진 결과만 갱신한다. JEV 평가(`/jev-evaluations`)를 먼저 실행해야 점수가
     * 모두 갖춰진다.
     */
    @PostMapping("/evaluations")
    @PreAuthorize("hasRole('LIBRARIAN')")
    public ApiResponse<CompositeEvaluationResponse> evaluate(@PathVariable Long sessionId,
                                                             @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(compositeEvaluationService.evaluateSession(sessionId, AuthClaims.userId(jwt)));
    }
}
