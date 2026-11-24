package com.__found.smart_library.domain.review.entity;

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
 * 관측 1건에 대한 사서의 검토 결과(관측당 최대 1건). 회차가 확정되기 전에는 다시 검토해 덮어쓸 수 있다.
 * 다른 테이블은 연관관계 대신 ID 로 보관한다. 변경 이력은 audit_log 에 남긴다.
 */
@Getter
@Entity
@Table(name = "review")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "review_id")
    private Long id;

    @Column(name = "observation_id", nullable = false, updatable = false)
    private Long observationId;

    @Column(name = "final_book_id")
    private Long finalBookId;

    @Enumerated(EnumType.STRING)
    @Column(name = "final_status", nullable = false, length = 30)
    private ReviewFinalStatus finalStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_action", nullable = false, length = 30)
    private ReviewAction reviewAction;

    @Column(name = "reviewer_id", nullable = false)
    private Long reviewerId;

    @Column(columnDefinition = "text")
    private String comment;

    @Column(name = "reviewed_at", nullable = false)
    private LocalDateTime reviewedAt;

    private Review(Long observationId) {
        this.observationId = observationId;
    }

    public static Review forObservation(Long observationId) {
        return new Review(observationId);
    }

    /** 검토 내용을 채우거나 다시 검토해 덮어쓴다. */
    public void record(ReviewAction action, Long finalBookId, ReviewFinalStatus finalStatus, Long reviewerId,
                       String comment, LocalDateTime reviewedAt) {
        this.reviewAction = action;
        this.finalBookId = finalBookId;
        this.finalStatus = finalStatus;
        this.reviewerId = reviewerId;
        this.comment = comment;
        this.reviewedAt = reviewedAt;
    }
}
