package com.__found.smart_library.global.security;

import com.__found.smart_library.domain.auth.store.AuthSessionStore;
import com.__found.smart_library.domain.user.entity.UserStatus;
import com.__found.smart_library.domain.user.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * 로그아웃·탈퇴로 폐기된 세션과 탈퇴한 계정의 Access Token 은 만료 전이라도 거부한다.
 */
@Component
@RequiredArgsConstructor
public class SessionTokenValidator implements OAuth2TokenValidator<Jwt> {

    private final AuthSessionStore sessionStore;
    private final AppUserRepository userRepository;

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        String sid = jwt.getClaimAsString(AuthClaims.SESSION_ID);
        if (sid != null && isActiveSession(sid, AuthClaims.userId(jwt))) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "폐기되었거나 존재하지 않는 세션입니다.", null));
    }

    private boolean isActiveSession(String sid, Long userId) {
        return sessionStore.findUserId(sid).filter(userId::equals).isPresent()
                && userRepository.existsByIdAndStatus(userId, UserStatus.ACTIVE);
    }
}
