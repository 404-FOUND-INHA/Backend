package com.__found.smart_library.domain.scan.controller;

import com.__found.smart_library.domain.scan.dto.ScanRoomResponse;
import com.__found.smart_library.domain.scan.dto.ScanSessionCreateRequest;
import com.__found.smart_library.domain.scan.dto.ScanSessionResponse;
import com.__found.smart_library.domain.scan.entity.ScanSessionStatus;
import com.__found.smart_library.domain.scan.service.ScanSessionService;
import com.__found.smart_library.global.response.ApiResponse;
import com.__found.smart_library.global.response.PageResponse;
import com.__found.smart_library.global.security.AuthClaims;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/scan-sessions")
@RequiredArgsConstructor
public class ScanSessionController {

    private final ScanSessionService scanSessionService;

    /** 회차 생성 화면의 자료실 선택 목록. */
    @GetMapping("/rooms")
    public ApiResponse<List<ScanRoomResponse>> getRooms() {
        return ApiResponse.ok(scanSessionService.getRooms());
    }

    /** 회차 목록(최신순). status 는 반복 지정으로 여러 개 가능, 없으면 전체. page 는 0부터. */
    @GetMapping
    public ApiResponse<PageResponse<ScanSessionResponse>> getSessions(
            @RequestParam(required = false) List<ScanSessionStatus> status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(scanSessionService.getSessions(status, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<ScanSessionResponse> getSession(@PathVariable Long id) {
        return ApiResponse.ok(scanSessionService.getSession(id));
    }

    /** 장서점검 회차 생성(사서 전용). 해당 자료실의 사용 중 서가가 점검 대상으로 함께 등록된다. */
    @PostMapping
    @PreAuthorize("hasRole('LIBRARIAN')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ScanSessionResponse> create(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ScanSessionCreateRequest request) {
        return ApiResponse.ok(scanSessionService.create(request, AuthClaims.userId(jwt)));
    }
}
