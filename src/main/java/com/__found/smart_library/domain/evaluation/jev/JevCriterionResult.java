package com.__found.smart_library.domain.evaluation.jev;

import com.__found.smart_library.domain.evaluation.entity.CriterionType;
import java.math.BigDecimal;
import java.util.Map;

/** 검증을 마친 평가축 1개의 결과(저장 형식). */
public record JevCriterionResult(
        CriterionType criterionType,
        BigDecimal rawScore,
        BigDecimal normalizedScore,
        BigDecimal confidence,
        int selectedGrade,
        Map<String, BigDecimal> probabilityDistribution) {
}
