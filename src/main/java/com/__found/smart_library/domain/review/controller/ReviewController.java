package com.__found.smart_library.domain.review.controller;

import com.__found.smart_library.domain.review.dto.ReviewListResponse;
import com.__found.smart_library.domain.review.dto.ReviewRequest;
import com.__found.smart_library.domain.review.dto.ReviewResult;
import com.__found.smart_library.domain.review.service.ReviewService;
import com.__found.smart_library.global.response.ApiResponse;
import com.__found.smart_library.global.security.AuthClaims;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 사서 검토. 목록은 로그인한 사용자라면 볼 수 있고, 검토 저장·취소는 사서만 할 수 있다. */
@RestController
@RequestMapping("/api/scan-sessions/{sessionId}")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    /** 검토 필요 관측 목록과 검토 진행 현황. reviewed: 생략=전체, true=검토 완료, false=미검토. page 는 0부터. */
    @GetMapping("/reviews")
    public ApiResponse<ReviewListResponse> list(
            @PathVariable Long sessionId,
            @RequestParam(required = false) Long shelfId,
            @RequestParam(required = false) Boolean reviewed,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(reviewService.list(sessionId, shelfId, reviewed, page, size));
    }

    /** 검토 저장(사서 전용). 이미 검토했으면 덮어쓴다. 회차가 REVIEW 상태일 때만 가능하다. */
    @PutMapping("/observations/{observationId}/review")
    @PreAuthorize("hasRole('LIBRARIAN')")
    public ApiResponse<ReviewResult> save(
            @PathVariable Long sessionId, @PathVariable Long observationId, @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ReviewRequest request) {
        return ApiResponse.ok(reviewService.save(sessionId, observationId, request, AuthClaims.userId(jwt)));
    }

    /** 검토 취소(사서 전용). 미검토 상태로 되돌린다. */
    @DeleteMapping("/observations/{observationId}/review")
    @PreAuthorize("hasRole('LIBRARIAN')")
    public ApiResponse<Void> delete(
            @PathVariable Long sessionId, @PathVariable Long observationId, @AuthenticationPrincipal Jwt jwt) {
        reviewService.delete(sessionId, observationId, AuthClaims.userId(jwt));
        return ApiResponse.ok();
    }
}
