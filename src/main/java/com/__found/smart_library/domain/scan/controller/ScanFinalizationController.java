package com.__found.smart_library.domain.scan.controller;

import com.__found.smart_library.domain.scan.dto.FinalizeResponse;
import com.__found.smart_library.domain.scan.service.ScanFinalizationService;
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

@RestController
@RequestMapping("/api/scan-sessions")
@RequiredArgsConstructor
public class ScanFinalizationController {

    private final ScanFinalizationService finalizationService;

    /**
     * 점검 회차 확정(사서 전용). 검토가 끝난 REVIEW 회차만 가능하며, 점검에서 위치가 확인된 도서를 BOOK 에 반영하고
     * 회차를 COMPLETED 로 바꾼다. 한 번 확정하면 되돌릴 수 없다.
     */
    @PostMapping("/{sessionId}/finalize")
    @PreAuthorize("hasRole('LIBRARIAN')")
    public ApiResponse<FinalizeResponse> finalizeSession(
            @PathVariable Long sessionId, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(finalizationService.finalizeSession(sessionId, AuthClaims.userId(jwt)));
    }
}
