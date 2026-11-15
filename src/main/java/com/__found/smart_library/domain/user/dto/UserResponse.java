package com.__found.smart_library.domain.user.dto;

import com.__found.smart_library.domain.user.entity.AppUser;
import com.__found.smart_library.domain.user.entity.UserRole;
import com.__found.smart_library.domain.user.entity.UserStatus;
import java.time.LocalDateTime;

public record UserResponse(
        Long id,
        String email,
        String name,
        UserRole role,
        UserStatus status,
        LocalDateTime lastLoginAt,
        LocalDateTime createdAt
) {

    public static UserResponse from(AppUser user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getName(), user.getRole(),
                user.getStatus(), user.getLastLoginAt(), user.getCreatedAt());
    }
}
