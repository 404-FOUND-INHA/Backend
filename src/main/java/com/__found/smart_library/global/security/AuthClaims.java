package com.__found.smart_library.global.security;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Access Token 클레임 이름과 꺼내 쓰는 헬퍼. 컨트롤러에서는 @AuthenticationPrincipal Jwt 로 받아 사용한다.
 */
public final class AuthClaims {

    public static final String SESSION_ID = "sid";
    public static final String ROLE = "role";

    private AuthClaims() {
    }

    public static Long userId(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }

    public static Long sessionId(Jwt jwt) {
        return ((Number) jwt.getClaim(SESSION_ID)).longValue();
    }
}
