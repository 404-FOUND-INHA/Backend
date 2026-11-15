package com.__found.smart_library.domain.book.dto;

import com.__found.smart_library.domain.book.entity.LifecycleStatus;
import com.__found.smart_library.domain.book.entity.LoanStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** lifecycleStatus / loanStatus 를 생략하면 각각 NORMAL / AVAILABLE 로 등록된다. */
public record BookCreateRequest(
        @NotBlank @Size(max = 50) String registrationNo,
        @Pattern(regexp = "^(\\d{10}|\\d{13})$", message = "ISBN 은 숫자 10자리 또는 13자리여야 합니다.") String isbn,
        @NotBlank @Size(max = 255) String title,
        @Size(max = 255) String author,
        @Size(max = 255) String publisher,
        @Min(0) @Max(9999) Integer publishedYear,
        @Size(max = 50) String edition,
        @NotBlank @Size(max = 150) String callNumberRaw,
        @NotBlank @Size(max = 50) String classificationNo,
        @NotBlank @Size(max = 50) String bookSymbol,
        @Size(max = 30) String copyNumber,
        Long expectedShelfId,
        Long currentShelfId,
        LifecycleStatus lifecycleStatus,
        LoanStatus loanStatus,
        @DecimalMin("0") @Digits(integer = 4, fraction = 2) BigDecimal bookWidthCm
) {
}
