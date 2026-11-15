package com.__found.smart_library.domain.auth.service;

import com.__found.smart_library.domain.auth.dto.LoginRequest;
import com.__found.smart_library.domain.auth.dto.SignupRequest;
import com.__found.smart_library.domain.auth.dto.TokenResponse;
import com.__found.smart_library.domain.auth.entity.AuthSession;
import com.__found.smart_library.domain.auth.repository.AuthSessionRepository;
import com.__found.smart_library.domain.user.dto.UserResponse;
import com.__found.smart_library.domain.user.entity.AppUser;
import com.__found.smart_library.domain.user.entity.UserRole;
import com.__found.smart_library.domain.user.repository.AppUserRepository;
import com.__found.smart_library.global.exception.BusinessException;
import com.__found.smart_library.global.exception.ErrorCode;
import com.__found.smart_library.global.security.JwtTokenProvider;
import com.__found.smart_library.global.security.TokenHasher;
import java.time.LocalDateTime;
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

    private static final int MAX_USER_AGENT_LENGTH = 500;

    private final AppUserRepository userRepository;
    private final AuthSessionRepository sessionRepository;
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
    public TokenResponse login(LoginRequest request, String ipAddress, String userAgent) {
        AppUser user = userRepository.findByEmail(normalize(request.email()))
                .filter(AppUser::isActive)
                .filter(u -> passwordEncoder.matches(request.password(), u.getPasswordHash()))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));
        user.recordLogin();
        return issueTokens(user, ipAddress, userAgent);
    }

    /** Refresh Token 을 새 토큰으로 교체(rotation)한다. 한 번 쓴 Refresh Token 은 다시 쓸 수 없다. */
    @Transactional
    public TokenResponse refresh(String refreshToken, String ipAddress, String userAgent) {
        AuthSession session = sessionRepository.findByRefreshTokenHashForUpdate(TokenHasher.hash(refreshToken))
                .filter(s -> s.isUsable(LocalDateTime.now()))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));
        AppUser user = userRepository.findById(session.getUserId())
                .filter(AppUser::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));
        session.revoke();
        return issueTokens(user, ipAddress, userAgent);
    }

    @Transactional
    public void logout(Long sessionId) {
        sessionRepository.findById(sessionId).ifPresent(AuthSession::revoke);
    }

    private TokenResponse issueTokens(AppUser user, String ipAddress, String userAgent) {
        String refreshToken = TokenHasher.newRefreshToken();
        LocalDateTime now = LocalDateTime.now();
        AuthSession session = sessionRepository.save(AuthSession.builder()
                .userId(user.getId())
                .refreshTokenHash(TokenHasher.hash(refreshToken))
                .ipAddress(ipAddress)
                .userAgent(truncate(userAgent))
                .issuedAt(now)
                .expiresAt(now.plus(tokenProvider.refreshTokenTtl()))
                .build());
        String accessToken = tokenProvider.createAccessToken(user.getId(), session.getId(), user.getRole());
        return TokenResponse.bearer(accessToken, refreshToken, tokenProvider.accessTokenTtlSeconds());
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= MAX_USER_AGENT_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_USER_AGENT_LENGTH);
    }
}
