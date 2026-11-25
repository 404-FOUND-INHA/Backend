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
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 평가 1건의 평가축별 세부 결과. JEV 원본은 받은 값을 그대로 보관하고 수정하지 않는다. */
@Getter
@Entity
@Table(name = "evaluation_criterion_result")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EvaluationCriterionResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "criterion_result_id")
    private Long id;

    @Column(name = "evaluation_id", nullable = false, updatable = false)
    private Long evaluationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "criterion_type", nullable = false, length = 30, updatable = false)
    private CriterionType criterionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "evaluator_type", nullable = false, length = 20, updatable = false)
    private EvaluationType evaluatorType;

    @Column(name = "model_version", length = 30)
    private String modelVersion;

    @Column(name = "raw_score", precision = 5, scale = 2)
    private BigDecimal rawScore;

    @Column(name = "normalized_score", precision = 5, scale = 2)
    private BigDecimal normalizedScore;

    @Column(precision = 5, scale = 4)
    private BigDecimal confidence;

    @Column(name = "selected_grade")
    private Integer selectedGrade;

    /** 단계별 확률 분포(단계 번호 → 확률). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "probability_distribution")
    private Map<String, BigDecimal> probabilityDistribution;

    private EvaluationCriterionResult(Long evaluationId, CriterionType criterionType, EvaluationType evaluatorType,
                                      String modelVersion, BigDecimal rawScore, BigDecimal normalizedScore,
                                      BigDecimal confidence, Integer selectedGrade,
                                      Map<String, BigDecimal> probabilityDistribution) {
        this.evaluationId = evaluationId;
        this.criterionType = criterionType;
        this.evaluatorType = evaluatorType;
        this.modelVersion = modelVersion;
        this.rawScore = rawScore;
        this.normalizedScore = normalizedScore;
        this.confidence = confidence;
        this.selectedGrade = selectedGrade;
        this.probabilityDistribution = probabilityDistribution;
    }

    /** Backend 가 계산한 평가축 결과. 신뢰도·등급·확률 분포는 없다. */
    public static EvaluationCriterionResult ofBackend(Long evaluationId, CriterionType criterionType,
                                                      String profileVersion, BigDecimal rawScore,
                                                      BigDecimal normalizedScore) {
        return new EvaluationCriterionResult(evaluationId, criterionType, EvaluationType.BACKEND, profileVersion,
                rawScore, normalizedScore, null, null, null);
    }

    public static EvaluationCriterionResult ofJev(Long evaluationId, CriterionType criterionType, String modelVersion,
                                                  BigDecimal rawScore, BigDecimal normalizedScore,
                                                  BigDecimal confidence, int selectedGrade,
                                                  Map<String, BigDecimal> probabilityDistribution) {
        return new EvaluationCriterionResult(evaluationId, criterionType, EvaluationType.JEV, modelVersion,
                rawScore, normalizedScore, confidence, selectedGrade, probabilityDistribution);
    }
}
