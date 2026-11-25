package com.__found.smart_library.domain.evaluation.entity;

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
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 도서 1권의 평가 결과 1건(JEV 원본 또는 Backend 종합). 축별 세부 결과는 {@link EvaluationCriterionResult} 에 있다.
 * created_at/updated_at 컬럼이 없어 BaseEntity 를 상속하지 않고, 다른 테이블은 연관관계 대신 ID 로 보관한다.
 */
@Getter
@Entity
@Table(name = "book_evaluation")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BookEvaluation {

    /** 평가 설정이 저장된 적 없을 때(기본 가중치·기준 점수)의 설정 버전 표기. */
    public static final String DEFAULT_PROFILE_VERSION = "default";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "evaluation_id")
    private Long id;

    @Column(name = "book_id", nullable = false, updatable = false)
    private Long bookId;

    @Column(name = "scan_session_id", updatable = false)
    private Long scanSessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "evaluation_type", nullable = false, length = 20, updatable = false)
    private EvaluationType evaluationType;

    @Column(name = "model_version", length = 30)
    private String modelVersion;

    /** 종합 점수. JEV 원본 결과에는 없다. */
    @Column(name = "final_score", precision = 5, scale = 2)
    private BigDecimal finalScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "candidate_status", nullable = false, length = 30)
    private CandidateStatus candidateStatus;

    /** Backend 종합 평가의 선정 근거(축별 점수·가중치·기여도). JEV 원본 결과에는 없다. */
    @Column(name = "evaluation_reason", columnDefinition = "text")
    private String evaluationReason;

    @Column(name = "evaluated_at", nullable = false)
    private LocalDateTime evaluatedAt;

    private BookEvaluation(Long bookId, Long scanSessionId, EvaluationType type, String modelVersion,
                           CandidateStatus candidateStatus, LocalDateTime evaluatedAt) {
        this.bookId = bookId;
        this.scanSessionId = scanSessionId;
        this.evaluationType = type;
        this.modelVersion = modelVersion;
        this.candidateStatus = candidateStatus;
        this.evaluatedAt = evaluatedAt;
    }

    /**
     * Backend 종합 평가. modelVersion 에는 계산에 쓴 평가 설정 버전(가중치·기준 점수)을 기록하고,
     * 설정이 저장된 적 없는 기본값이면 {@value #DEFAULT_PROFILE_VERSION} 이다.
     */
    public static BookEvaluation forBackend(Long bookId, Long scanSessionId, String profileVersion,
                                            BigDecimal finalScore, CandidateStatus candidateStatus, String reason,
                                            LocalDateTime evaluatedAt) {
        BookEvaluation evaluation = new BookEvaluation(bookId, scanSessionId, EvaluationType.BACKEND, profileVersion,
                candidateStatus, evaluatedAt);
        evaluation.finalScore = finalScore;
        evaluation.evaluationReason = reason;
        return evaluation;
    }

    /** 다시 계산한 결과로 바꾼다(Backend 종합 평가 전용). */
    public void recalculate(String profileVersion, BigDecimal finalScore, CandidateStatus candidateStatus,
                            String reason, LocalDateTime evaluatedAt) {
        this.modelVersion = profileVersion;
        this.finalScore = finalScore;
        this.candidateStatus = candidateStatus;
        this.evaluationReason = reason;
        this.evaluatedAt = evaluatedAt;
    }

    /** JEV 가 계산한 원본 결과. 후보 여부는 아직 판정하지 않는다. */
    public static BookEvaluation fromJev(Long bookId, Long scanSessionId, String modelVersion,
                                         LocalDateTime evaluatedAt) {
        return new BookEvaluation(bookId, scanSessionId, EvaluationType.JEV, modelVersion,
                CandidateStatus.UNDECIDED, evaluatedAt);
    }
}
