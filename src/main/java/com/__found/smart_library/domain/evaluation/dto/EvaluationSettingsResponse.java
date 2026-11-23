package com.__found.smart_library.domain.evaluation.dto;

import com.__found.smart_library.domain.evaluation.entity.EvaluationProfile;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 현재 평가 설정. 저장된 설정이 없으면 기본값이며 profileVersion 이 null, isDefault 가 true 이다.
 */
public record EvaluationSettingsResponse(
        String profileVersion,
        boolean isDefault,
        BigDecimal disposalThreshold,
        List<CriterionWeight> weights,
        Long configuredBy,
        LocalDateTime configuredAt
) {

    public static EvaluationSettingsResponse of(EvaluationProfile profile, List<CriterionWeight> weights) {
        return new EvaluationSettingsResponse(profile.getProfileVersion(), false, profile.getDisposalThreshold(),
                weights, profile.getConfiguredBy(), profile.getCreatedAt());
    }

    public static EvaluationSettingsResponse defaults(BigDecimal threshold, List<CriterionWeight> weights) {
        return new EvaluationSettingsResponse(null, true, threshold, weights, null, null);
    }
}
