package com.__found.smart_library.domain.book.dto;

import com.__found.smart_library.domain.audit.entity.AuditLog;
import java.time.LocalDateTime;
import java.util.Map;

/** 도서 변경 이력 한 건. before/after 에는 바뀐 필드만 들어 있다. */
public record BookHistoryResponse(
        Long id,
        String action,
        Long actorId,
        String actorName,
        Map<String, Object> before,
        Map<String, Object> after,
        LocalDateTime createdAt
) {

    public static BookHistoryResponse of(AuditLog log, String actorName) {
        return new BookHistoryResponse(log.getId(), log.getAction(), log.getActorId(), actorName,
                log.getBeforeData(), log.getAfterData(), log.getCreatedAt());
    }
}
