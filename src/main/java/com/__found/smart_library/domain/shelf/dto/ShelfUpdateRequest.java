package com.__found.smart_library.domain.shelf.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 서가 위치·구성 설정(PUT = 전체 교체). 보내지 않은 필드는 null 로 저장된다.
 * shelfCode 는 Vision 과 공유하는 식별자라 수정 대상이 아니다.
 */
public record ShelfUpdateRequest(
        @Size(max = 30) String zone,
        @PositiveOrZero Integer floorNo,
        @PositiveOrZero Integer shelfNo,
        @PositiveOrZero Integer levelNo,
        @Size(max = 150) String callNumberStart,
        @Size(max = 150) String callNumberEnd,
        @Size(max = 50) String markerId,
        @Digits(integer = 8, fraction = 2) BigDecimal mapX,
        @Digits(integer = 8, fraction = 2) BigDecimal mapY,
        @Digits(integer = 8, fraction = 2) BigDecimal mapZ,
        @DecimalMin("0") @Digits(integer = 6, fraction = 2) BigDecimal widthCm,
        @DecimalMin("0") @Digits(integer = 6, fraction = 2) BigDecimal heightCm,
        @DecimalMin("0") @Digits(integer = 6, fraction = 2) BigDecimal depthCm,
        @DecimalMin("0") @DecimalMax("360") @Digits(integer = 3, fraction = 2) BigDecimal rotationDegree,
        @PositiveOrZero Integer displayOrder,
        @NotNull Boolean active
) {
}
