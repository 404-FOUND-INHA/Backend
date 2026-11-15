package com.__found.smart_library.domain.book.dto;

import com.__found.smart_library.domain.book.entity.LifecycleStatus;
import com.__found.smart_library.domain.book.entity.LoanStatus;

public record BookSearchCondition(
        String keyword,
        LifecycleStatus lifecycleStatus,
        LoanStatus loanStatus,
        Long shelfId,
        String classificationNo
) {
}
