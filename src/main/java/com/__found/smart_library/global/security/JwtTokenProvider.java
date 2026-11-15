package com.__found.smart_library.global.security;

import com.__found.smart_library.domain.user.entity.UserRole;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Access Token 발급. subject=사용자 ID, sid=세션 ID(Redis), role=권한.
 */
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;

    public String createAccessToken(Long userId, String sessionId, UserRole role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiresAt(now.plus(properties.accessTokenTtl()))
                .claim(AuthClaims.SESSION_ID, sessionId)
                .claim(AuthClaims.ROLE, role.name())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public long accessTokenTtlSeconds() {
        return properties.accessTokenTtl().toSeconds();
    }

    public java.time.Duration refreshTokenTtl() {
        return properties.refreshTokenTtl();
    }
}
