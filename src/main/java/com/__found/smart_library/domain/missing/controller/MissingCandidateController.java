package com.__found.smart_library.domain.missing.controller;

import com.__found.smart_library.domain.missing.dto.MissingCandidateResponse;
import com.__found.smart_library.domain.missing.dto.MissingCandidateReviewRequest;
import com.__found.smart_library.domain.missing.entity.MissingCandidateStatus;
import com.__found.smart_library.domain.missing.service.MissingCandidateService;
import com.__found.smart_library.global.response.ApiResponse;
import com.__found.smart_library.global.response.PageResponse;
import com.__found.smart_library.global.security.AuthClaims;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 소재불명 후보. 현장 확인은 사서와 근로생이 모두 하므로 로그인한 사용자라면 조회·기록할 수 있다. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MissingCandidateController {

    private final MissingCandidateService service;

    /** 회차의 소재불명 후보 목록. status 생략=전체. page 는 0부터. */
    @GetMapping("/scan-sessions/{sessionId}/missing-candidates")
    public ApiResponse<PageResponse<MissingCandidateResponse>> list(
            @PathVariable Long sessionId,
            @RequestParam(required = false) MissingCandidateStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(sessionId, status, page, size));
    }

    /** 현장 확인 결과 기록(찾음 / 못 찾음 / 분실 후보 / 해결). 다시 기록하면 덮어쓴다. */
    @PutMapping("/missing-candidates/{candidateId}/review")
    public ApiResponse<MissingCandidateResponse> record(
            @PathVariable Long candidateId, @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody MissingCandidateReviewRequest request) {
        return ApiResponse.ok(service.record(candidateId, request, AuthClaims.userId(jwt)));
    }
}
