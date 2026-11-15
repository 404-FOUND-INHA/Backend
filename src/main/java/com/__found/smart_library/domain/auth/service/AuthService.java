package com.__found.smart_library.domain.auth.service;

import com.__found.smart_library.domain.auth.dto.LoginRequest;
import com.__found.smart_library.domain.auth.dto.SignupRequest;
import com.__found.smart_library.domain.auth.dto.TokenResponse;
import com.__found.smart_library.domain.auth.store.AuthSessionStore;
import com.__found.smart_library.domain.user.dto.UserResponse;
import com.__found.smart_library.domain.user.entity.AppUser;
import com.__found.smart_library.domain.user.entity.UserRole;
import com.__found.smart_library.domain.user.repository.AppUserRepository;
import com.__found.smart_library.global.exception.BusinessException;
import com.__found.smart_library.global.exception.ErrorCode;
import com.__found.smart_library.global.security.JwtTokenProvider;
import com.__found.smart_library.global.security.TokenHasher;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private final AppUserRepository userRepository;
    private final AuthSessionStore sessionStore;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;

    /** 회원가입. 권한은 항상 STUDENT_WORKER 이며, 사서 권한은 DB에서 직접 부여한다. */
    @Transactional
    public UserResponse signup(SignupRequest request) {
        String email = normalize(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.EMAIL_DUPLICATED);
        }
        AppUser user = AppUser.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(request.password()))
                .name(request.name().trim())
                .role(UserRole.STUDENT_WORKER)
                .build();
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.EMAIL_DUPLICATED);
        }
        return UserResponse.from(user);
    }

    @Transactional
    public TokenResponse login(LoginRequest request) {
        AppUser user = userRepository.findByEmail(normalize(request.email()))
                .filter(AppUser::isActive)
                .filter(u -> passwordEncoder.matches(request.password(), u.getPasswordHash()))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));
        user.recordLogin();
        return issueTokens(user);
    }

    /**
     * Refresh Token 을 새 토큰으로 교체(rotation)한다. 한 번 쓴 Refresh Token 은 다시 쓸 수 없다.
     * 토큰 소모가 Redis 의 GETDEL 로 원자적이라, 같은 토큰으로 동시에 요청해도 한 요청만 성공한다.
     */
    public TokenResponse refresh(String refreshToken) {
        String sid = sessionStore.consumeRefreshToken(TokenHasher.hash(refreshToken))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));
        Long userId = sessionStore.findUserId(sid)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));
        AppUser user = userRepository.findById(userId).filter(AppUser::isActive).orElse(null);
        if (user == null) {
            sessionStore.revoke(sid);
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        String newRefreshToken = TokenHasher.newRefreshToken();
        sessionStore.rotate(sid, userId, TokenHasher.hash(newRefreshToken), tokenProvider.refreshTokenTtl());
        return tokenResponse(user, sid, newRefreshToken);
    }

    public void logout(String sessionId) {
        sessionStore.revoke(sessionId);
    }

    private TokenResponse issueTokens(AppUser user) {
        String refreshToken = TokenHasher.newRefreshToken();
        String sid = sessionStore.create(user.getId(), TokenHasher.hash(refreshToken), tokenProvider.refreshTokenTtl());
        return tokenResponse(user, sid, refreshToken);
    }

    private TokenResponse tokenResponse(AppUser user, String sid, String refreshToken) {
        String accessToken = tokenProvider.createAccessToken(user.getId(), sid, user.getRole());
        return TokenResponse.bearer(accessToken, refreshToken, tokenProvider.accessTokenTtlSeconds());
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
