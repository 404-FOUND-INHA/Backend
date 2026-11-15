package com.__found.smart_library.domain.auth.repository;

import com.__found.smart_library.domain.auth.entity.AuthSession;
import java.time.LocalDateTime;
import java.util.Optional;
import com.__found.smart_library.domain.user.entity.UserStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthSessionRepository extends JpaRepository<AuthSession, Long> {

    Optional<AuthSession> findByRefreshTokenHash(String refreshTokenHash);

    /** 동시 refresh 요청이 같은 토큰으로 둘 다 통과하지 못하도록 행을 잠그고 조회한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AuthSession s where s.refreshTokenHash = :hash")
    Optional<AuthSession> findByRefreshTokenHashForUpdate(@Param("hash") String hash);

    /** 세션이 폐기되지 않았고 소유 계정이 활성 상태일 때만 true. */
    @Query("select count(s) > 0 from AuthSession s, AppUser u "
            + "where s.id = :id and s.revokedAt is null and u.id = s.userId and u.status = :status")
    boolean existsActiveSession(@Param("id") Long id, @Param("status") UserStatus status);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AuthSession s set s.revokedAt = :now where s.userId = :userId and s.revokedAt is null")
    int revokeAllByUserId(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
