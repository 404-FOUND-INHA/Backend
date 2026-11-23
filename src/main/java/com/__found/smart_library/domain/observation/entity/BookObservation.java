package com.__found.smart_library.domain.observation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Vision 이 보낸 관측 1건과 Backend 의 자동 분석 결과. 한 번 저장하면 A2 는 수정하지 않는다
 * (사서의 검토 결과는 review 에 따로 기록되며 이 값을 덮어쓰지 않는다).
 * created_at/updated_at 컬럼이 없어 BaseEntity 를 상속하지 않고, 다른 테이블은 연관관계 대신 ID 로 보관한다.
 */
@Getter
@Entity
@Table(name = "book_observation")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BookObservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "observation_id")
    private Long id;

    @Column(name = "scan_session_id", nullable = false)
    private Long scanSessionId;

    /** 자동 매칭한 도서. 식별하지 못했으면 null. */
    @Column(name = "book_id")
    private Long bookId;

    @Column(name = "detected_shelf_id")
    private Long detectedShelfId;

    @Column(name = "detected_label_raw", length = 200)
    private String detectedLabelRaw;

    @Column(name = "detected_classification_no", length = 50)
    private String detectedClassificationNo;

    @Column(name = "detected_book_symbol", length = 50)
    private String detectedBookSymbol;

    @Column(name = "detected_copy_number", length = 30)
    private String detectedCopyNumber;

    @Column(name = "detected_title", length = 255)
    private String detectedTitle;

    @Column(name = "detected_order", nullable = false)
    private Integer detectedOrder;

    @Column(name = "x_position_cm", precision = 8, scale = 2)
    private BigDecimal xPositionCm;

    @Column(name = "match_confidence", precision = 5, scale = 4)
    private BigDecimal matchConfidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "analysis_status", nullable = false, length = 30)
    private ObservationAnalysisStatus analysisStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_reason", length = 255)
    private ObservationStatusReason statusReason;

    @Column(name = "detected_at")
    private LocalDateTime detectedAt;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    @Builder
    private BookObservation(Long scanSessionId, Long bookId, Long detectedShelfId, String detectedLabelRaw,
                            String detectedClassificationNo, String detectedBookSymbol, String detectedCopyNumber,
                            String detectedTitle, Integer detectedOrder, BigDecimal xPositionCm,
                            BigDecimal matchConfidence, ObservationAnalysisStatus analysisStatus,
                            ObservationStatusReason statusReason, LocalDateTime detectedAt,
                            LocalDateTime receivedAt) {
        this.scanSessionId = scanSessionId;
        this.bookId = bookId;
        this.detectedShelfId = detectedShelfId;
        this.detectedLabelRaw = detectedLabelRaw;
        this.detectedClassificationNo = detectedClassificationNo;
        this.detectedBookSymbol = detectedBookSymbol;
        this.detectedCopyNumber = detectedCopyNumber;
        this.detectedTitle = detectedTitle;
        this.detectedOrder = detectedOrder;
        this.xPositionCm = xPositionCm;
        this.matchConfidence = matchConfidence;
        this.analysisStatus = analysisStatus;
        this.statusReason = statusReason;
        this.detectedAt = detectedAt;
        this.receivedAt = receivedAt;
    }
}
