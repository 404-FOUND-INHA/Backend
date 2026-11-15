package com.__found.smart_library.domain.book.dto;

import com.__found.smart_library.domain.book.entity.LifecycleStatus;
import com.__found.smart_library.domain.book.entity.LoanStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 부분 수정(PATCH). 보낸 필드만 바뀌고, 생략하거나 null 로 보낸 필드는 그대로 유지된다.
 * 등록번호(registrationNo)는 수정할 수 없다. 값을 비우는(null 로 만드는) 수정은 지원하지 않는다.
 */
public record BookUpdateRequest(
        @Pattern(regexp = "^(\\d{10}|\\d{13})$", message = "ISBN 은 숫자 10자리 또는 13자리여야 합니다.") String isbn,
        @Size(min = 1, max = 255) String title,
        @Size(max = 255) String author,
        @Size(max = 255) String publisher,
        @Min(0) @Max(9999) Integer publishedYear,
        @Size(max = 50) String edition,
        @Size(min = 1, max = 150) String callNumberRaw,
        @Size(min = 1, max = 50) String classificationNo,
        @Size(min = 1, max = 50) String bookSymbol,
        @Size(max = 30) String copyNumber,
        Long expectedShelfId,
        Long currentShelfId,
        LifecycleStatus lifecycleStatus,
        LoanStatus loanStatus,
        @DecimalMin("0") @Digits(integer = 4, fraction = 2) BigDecimal bookWidthCm
) {
}
