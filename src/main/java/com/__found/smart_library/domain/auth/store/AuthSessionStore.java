package com.__found.smart_library.domain.auth.store;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 로그인 세션과 Refresh Token 을 Redis 에 저장한다. 만료는 Redis TTL 이 처리한다.
 *
 * <pre>
 * auth:session:{sid}        -> userId          (세션이 살아 있는지, Access Token 의 sid 가 가리킴)
 * auth:refresh:{hash}       -> sid             (Refresh Token 해시. 한 번 쓰면 삭제되는 1회용)
 * auth:user-sessions:{uid}  -> Set&lt;sid&gt;        (탈퇴 등으로 사용자 세션을 한 번에 폐기할 때 사용)
 * </pre>
 */
@Component
@RequiredArgsConstructor
public class AuthSessionStore {

    private static final String SESSION = "auth:session:";
    private static final String REFRESH = "auth:refresh:";
    private static final String USER_SESSIONS = "auth:user-sessions:";

    private final StringRedisTemplate redis;

    /** 새 세션을 만들고 sid 를 돌려준다. */
    public String create(Long userId, String refreshTokenHash, Duration ttl) {
        String sid = UUID.randomUUID().toString();
        save(sid, userId, refreshTokenHash, ttl);
        return sid;
    }

    /** 같은 세션의 Refresh Token 을 새 것으로 교체하고 만료 시간을 다시 늘린다. */
    public void rotate(String sid, Long userId, String newRefreshTokenHash, Duration ttl) {
        save(sid, userId, newRefreshTokenHash, ttl);
    }

    /**
     * Refresh Token 을 원자적으로 꺼내며 삭제한다(GETDEL). 동시에 같은 토큰으로 요청해도 한 요청만 sid 를 받는다.
     */
    public Optional<String> consumeRefreshToken(String refreshTokenHash) {
        return Optional.ofNullable(redis.opsForValue().getAndDelete(REFRESH + refreshTokenHash));
    }

    public Optional<Long> findUserId(String sid) {
        String value = redis.opsForValue().get(SESSION + sid);
        return value == null ? Optional.empty() : Optional.of(Long.valueOf(value));
    }

    /** 로그아웃: 세션 하나를 폐기한다. 남아 있는 Refresh Token 은 세션이 없어 사용할 수 없다. */
    public void revoke(String sid) {
        findUserId(sid).ifPresent(userId -> redis.opsForSet().remove(USER_SESSIONS + userId, sid));
        redis.delete(SESSION + sid);
    }

    /** 사용자의 모든 세션을 폐기한다(회원탈퇴). */
    public void revokeAllByUser(Long userId) {
        String userKey = USER_SESSIONS + userId;
        Set<String> sids = redis.opsForSet().members(userKey);
        if (sids != null && !sids.isEmpty()) {
            List<String> keys = sids.stream().map(sid -> SESSION + sid).toList();
            redis.delete(keys);
        }
        redis.delete(userKey);
    }

    private void save(String sid, Long userId, String refreshTokenHash, Duration ttl) {
        redis.opsForValue().set(SESSION + sid, String.valueOf(userId), ttl);
        redis.opsForSet().add(USER_SESSIONS + userId, sid);
        redis.expire(USER_SESSIONS + userId, ttl);
        // 세션이 먼저 저장된 뒤에 토큰을 등록해, 토큰이 있으면 항상 세션도 있도록 한다.
        redis.opsForValue().set(REFRESH + refreshTokenHash, sid, ttl);
    }
}
