package com.__found.smart_library.domain.missing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 점검에서 발견되지 않은 도서(소재불명 후보). 회차를 확정할 때 회차·도서당 1건씩 만들어지고,
 * 이후 현장 확인 결과를 기록한다. 다른 테이블은 연관관계 대신 ID 로 보관한다.
 */
@Getter
@Entity
@Table(name = "missing_candidate")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MissingCandidate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "missing_id")
    private Long id;

    @Column(name = "scan_session_id", nullable = false, updatable = false)
    private Long scanSessionId;

    @Column(name = "book_id", nullable = false, updatable = false)
    private Long bookId;

    @Column(name = "expected_shelf_id", updatable = false)
    private Long expectedShelfId;

    @Column(name = "consecutive_missing_count", nullable = false, updatable = false)
    private int consecutiveMissingCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private MissingCandidateStatus status;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(columnDefinition = "text")
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private MissingCandidate(Long scanSessionId, Long bookId, Long expectedShelfId, int consecutiveMissingCount,
                             LocalDateTime createdAt) {
        this.scanSessionId = scanSessionId;
        this.bookId = bookId;
        this.expectedShelfId = expectedShelfId;
        this.consecutiveMissingCount = consecutiveMissingCount;
        this.status = MissingCandidateStatus.PENDING;
        this.createdAt = createdAt;
    }

    public static MissingCandidate create(Long scanSessionId, Long bookId, Long expectedShelfId,
                                          int consecutiveMissingCount, LocalDateTime createdAt) {
        return new MissingCandidate(scanSessionId, bookId, expectedShelfId, consecutiveMissingCount, createdAt);
    }

    /** 현장 확인 결과를 기록한다. 다시 기록하면 덮어쓴다. */
    public void record(MissingCandidateStatus status, String note, Long reviewerId, LocalDateTime reviewedAt) {
        this.status = status;
        this.note = note;
        this.reviewedBy = reviewerId;
        this.reviewedAt = reviewedAt;
    }
}
