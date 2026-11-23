package com.__found.smart_library.domain.evaluation.dto;

import com.__found.smart_library.domain.evaluation.entity.CriterionType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** 평가축 하나의 가중치. 요청과 응답에 함께 쓴다. */
public record CriterionWeight(
        @NotNull CriterionType criterion,
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") @Digits(integer = 1, fraction = 4) BigDecimal weight
) {
}
