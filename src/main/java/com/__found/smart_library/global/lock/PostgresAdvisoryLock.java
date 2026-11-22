package com.__found.smart_library.global.lock;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL 트랜잭션 수준 Advisory Lock.
 * 락은 커밋/롤백 시 자동 해제된다. 트랜잭션 밖에서 호출하면 곧바로 풀려 의미가 없으므로 MANDATORY 로 강제한다.
 */
@Component
@RequiredArgsConstructor
public class PostgresAdvisoryLock {

    private final JdbcTemplate jdbcTemplate;

    /** (namespace, hashtext(key)) 2-int 키로 락을 잡는다. 같은 키를 쥔 트랜잭션이 끝날 때까지 대기한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(int namespace, String key) {
        jdbcTemplate.query("select pg_advisory_xact_lock(?, hashtext(?))",
                (ResultSetExtractor<Void>) rs -> null, namespace, key);
    }
}
