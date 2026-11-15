package com.__found.smart_library.global.security;

import com.__found.smart_library.domain.auth.repository.AuthSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * 로그아웃·탈퇴로 폐기된 세션의 Access Token 은 만료 전이라도 거부한다.
 */
@Component
@RequiredArgsConstructor
public class SessionTokenValidator implements OAuth2TokenValidator<Jwt> {

    private final AuthSessionRepository authSessionRepository;

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        Object sid = jwt.getClaim(AuthClaims.SESSION_ID);
        if (sid instanceof Number number && authSessionRepository.existsByIdAndRevokedAtIsNull(number.longValue())) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "폐기되었거나 존재하지 않는 세션입니다.", null));
    }
}
