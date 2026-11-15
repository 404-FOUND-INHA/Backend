package com.__found.smart_library.domain.user.service;

import com.__found.smart_library.domain.auth.store.AuthSessionStore;
import com.__found.smart_library.domain.user.dto.UserResponse;
import com.__found.smart_library.domain.user.entity.AppUser;
import com.__found.smart_library.domain.user.repository.AppUserRepository;
import com.__found.smart_library.global.exception.BusinessException;
import com.__found.smart_library.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final AppUserRepository userRepository;
    private final AuthSessionStore sessionStore;

    public UserResponse getMe(Long userId) {
        return UserResponse.from(findActive(userId));
    }

    /** 회원탈퇴: 계정을 WITHDRAWN 으로 바꾸고(소프트 삭제) 모든 세션을 폐기한다. */
    @Transactional
    public void withdraw(Long userId) {
        AppUser user = findActive(userId);
        user.withdraw();
        sessionStore.revokeAllByUser(userId);
    }

    private AppUser findActive(Long userId) {
        return userRepository.findById(userId)
                .filter(AppUser::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }
}
