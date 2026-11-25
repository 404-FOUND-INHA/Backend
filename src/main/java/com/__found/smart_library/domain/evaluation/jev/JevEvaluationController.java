package com.__found.smart_library.domain.evaluation.jev;

import com.__found.smart_library.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** JEV 평가 요청(사서 전용). 도서 수만큼 외부 호출을 하므로 응답에 시간이 걸릴 수 있다. */
@RestController
@RequestMapping("/api/scan-sessions/{sessionId}")
@RequiredArgsConstructor
public class JevEvaluationController {

    private final JevEvaluationService jevEvaluationService;

    /** 점검이 확정(COMPLETED)된 회차의 도서를 JEV 에 평가시킨다. 이미 평가된 도서는 건너뛰므로 다시 호출해도 안전하다. */
    @PostMapping("/jev-evaluations")
    @PreAuthorize("hasRole('LIBRARIAN')")
    public ApiResponse<JevEvaluationResponse> evaluate(@PathVariable Long sessionId) {
        return ApiResponse.ok(jevEvaluationService.evaluateSession(sessionId));
    }
}
