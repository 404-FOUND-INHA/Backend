package com.__found.smart_library.domain.scan.entity;

import com.__found.smart_library.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 장서점검 회차. 생성과 동시에 진행(IN_PROGRESS) 상태로 시작한다.
 * A1 에서 쓰는 컬럼만 매핑한다(나머지 컬럼은 해당 기능 구현 시 추가).
 */
@Getter
@Entity
@Table(name = "scan_session")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScanSession extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "scan_session_id")
    private Long id;

    @Column(nullable = false, length = 100)
    private String sessionName;

    /** 점검 대상 자료실(shelf.zone). */
    @Column(length = 100)
    private String targetArea;

    private LocalDate plannedStartDate;

    private LocalDateTime startedAt;

    private LocalDateTime finalizedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ScanSessionStatus status;

    /** 생성한 사용자(app_user.user_id). 연관관계 대신 ID 만 보관한다. */
    @Column(nullable = false)
    private Long createdBy;

    private ScanSession(String sessionName, String targetArea, LocalDate plannedStartDate, Long createdBy) {
        this.sessionName = sessionName;
        this.targetArea = targetArea;
        this.plannedStartDate = plannedStartDate;
        this.createdBy = createdBy;
        this.status = ScanSessionStatus.IN_PROGRESS;
        this.startedAt = LocalDateTime.now();
    }

    public static ScanSession create(String sessionName, String targetArea, LocalDate plannedStartDate,
                                     Long createdBy) {
        return new ScanSession(sessionName, targetArea, plannedStartDate, createdBy);
    }

    /**
     * Vision 결과의 분석·저장이 끝나 사서 검토를 시작할 수 있는 상태(REVIEW)로 바꾼다.
     * 진행 중(IN_PROGRESS)인 회차만 전이할 수 있다.
     */
    public void markReviewReady() {
        if (this.status != ScanSessionStatus.IN_PROGRESS) {
            throw new IllegalStateException("진행 중인 회차만 검토 상태로 바꿀 수 있습니다. 현재 상태=" + this.status);
        }
        this.status = ScanSessionStatus.REVIEW;
    }
}
