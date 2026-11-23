package com.__found.smart_library.domain.evaluation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** 설정 한 버전의 평가축별 가중치(0~1). 한 버전의 가중치 합은 1이다. */
@Getter
@Entity
@Table(name = "evaluation_criterion_weight")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EvaluationCriterionWeight {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "criterion_weight_id")
    private Long id;

    @Column(nullable = false, length = 30)
    private String profileVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CriterionType criterionType;

    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal weight;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(nullable = false)
    private Long configuredBy;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private EvaluationCriterionWeight(String profileVersion, CriterionType criterionType, BigDecimal weight,
                                      Long configuredBy) {
        this.profileVersion = profileVersion;
        this.criterionType = criterionType;
        this.weight = weight;
        this.configuredBy = configuredBy;
        this.active = true;
    }

    public static EvaluationCriterionWeight activate(String profileVersion, CriterionType criterionType,
                                                     BigDecimal weight, Long configuredBy) {
        return new EvaluationCriterionWeight(profileVersion, criterionType, weight, configuredBy);
    }
}
