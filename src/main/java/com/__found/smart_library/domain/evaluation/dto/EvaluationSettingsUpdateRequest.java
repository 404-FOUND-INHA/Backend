package com.__found.smart_library.domain.evaluation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

/** 모든 평가축의 가중치를 한 번에 보낸다(합계 1.0). 일부만 바꾸는 요청은 받지 않는다. */
public record EvaluationSettingsUpdateRequest(
        @NotNull @DecimalMin("0.0") @DecimalMax("100.0") @Digits(integer = 3, fraction = 2) BigDecimal disposalThreshold,
        @NotEmpty List<@NotNull @Valid CriterionWeight> weights
) {
}
