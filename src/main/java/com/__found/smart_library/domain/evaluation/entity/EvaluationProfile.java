package com.__found.smart_library.domain.evaluation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** 평가 설정 한 버전. 저장 후에는 수정하지 않고(활성 여부만 bulk 로 끈다) 새 버전을 만든다. */
@Getter
@Entity
@Table(name = "evaluation_profile")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EvaluationProfile {

    @Id
    @Column(name = "profile_version", length = 30)
    private String profileVersion;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal disposalThreshold;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(nullable = false)
    private Long configuredBy;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private EvaluationProfile(String profileVersion, BigDecimal disposalThreshold, Long configuredBy) {
        this.profileVersion = profileVersion;
        this.disposalThreshold = disposalThreshold;
        this.configuredBy = configuredBy;
        this.active = true;
    }

    public static EvaluationProfile activate(String profileVersion, BigDecimal disposalThreshold, Long configuredBy) {
        return new EvaluationProfile(profileVersion, disposalThreshold, configuredBy);
    }
}
