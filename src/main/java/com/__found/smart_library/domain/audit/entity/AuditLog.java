package com.__found.smart_library.domain.audit.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 주요 데이터 변경 이력. before/after 에는 "바뀐 필드만" 담는다.
 */
@Getter
@Entity
@Table(name = "audit_log")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog {

    public static final String ENTITY_BOOK = "BOOK";
    public static final String ENTITY_REVIEW = "REVIEW";
    public static final String ENTITY_MISSING_CANDIDATE = "MISSING_CANDIDATE";

    public static final String ACTION_CREATE = "CREATE";
    public static final String ACTION_UPDATE = "UPDATE";
    public static final String ACTION_STATUS_CHANGE = "STATUS_CHANGE";
    public static final String ACTION_DELETE = "DELETE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "audit_log_id")
    private Long id;

    private Long actorId;

    @Column(nullable = false, length = 50)
    private String entityType;

    @Column(nullable = false)
    private Long entityId;

    @Column(nullable = false, length = 30)
    private String action;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> beforeData;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> afterData;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private AuditLog(Long actorId, String entityType, Long entityId, String action,
                     Map<String, Object> beforeData, Map<String, Object> afterData) {
        this.actorId = actorId;
        this.entityType = entityType;
        this.entityId = entityId;
        this.action = action;
        this.beforeData = beforeData;
        this.afterData = afterData;
    }
}
