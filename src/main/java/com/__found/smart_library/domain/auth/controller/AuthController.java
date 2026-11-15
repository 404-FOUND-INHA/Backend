package com.__found.smart_library.domain.auth.controller;

import com.__found.smart_library.domain.auth.dto.LoginRequest;
import com.__found.smart_library.domain.auth.dto.RefreshRequest;
import com.__found.smart_library.domain.auth.dto.SignupRequest;
import com.__found.smart_library.domain.auth.dto.TokenResponse;
import com.__found.smart_library.domain.auth.service.AuthService;
import com.__found.smart_library.domain.user.dto.UserResponse;
import com.__found.smart_library.global.response.ApiResponse;
import com.__found.smart_library.global.security.AuthClaims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<UserResponse>> signup(@Valid @RequestBody SignupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(authService.signup(request)));
    }

    @PostMapping("/login")
    public ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return ApiResponse.ok(authService.login(request, http.getRemoteAddr(), http.getHeader(HttpHeaders.USER_AGENT)));
    }

    @PostMapping("/refresh")
    public ApiResponse<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request, HttpServletRequest http) {
        return ApiResponse.ok(authService.refresh(
                request.refreshToken(), http.getRemoteAddr(), http.getHeader(HttpHeaders.USER_AGENT)));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@AuthenticationPrincipal Jwt jwt) {
        authService.logout(AuthClaims.sessionId(jwt));
        return ApiResponse.ok();
    }
}
