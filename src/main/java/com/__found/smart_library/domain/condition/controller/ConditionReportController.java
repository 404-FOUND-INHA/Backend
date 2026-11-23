package com.__found.smart_library.domain.condition.controller;

import com.__found.smart_library.domain.condition.dto.ConditionReportCreateRequest;
import com.__found.smart_library.domain.condition.dto.ConditionReportResponse;
import com.__found.smart_library.domain.condition.service.ConditionReportService;
import com.__found.smart_library.global.response.ApiResponse;
import com.__found.smart_library.global.security.AuthClaims;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 도서 오손 기록. 현장 입력이라 사서·근로생 모두 등록할 수 있다(로그인만 필요). */
@RestController
@RequestMapping("/api/books/{bookId}/condition-reports")
@RequiredArgsConstructor
public class ConditionReportController {

    private final ConditionReportService conditionReportService;

    @GetMapping
    public ApiResponse<List<ConditionReportResponse>> list(@PathVariable Long bookId) {
        return ApiResponse.ok(conditionReportService.list(bookId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ConditionReportResponse> create(
            @PathVariable Long bookId, @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ConditionReportCreateRequest request) {
        return ApiResponse.ok(conditionReportService.create(bookId, request, AuthClaims.userId(jwt)));
    }
}
