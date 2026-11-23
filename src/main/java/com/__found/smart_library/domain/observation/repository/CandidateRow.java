package com.__found.smart_library.domain.observation.repository;

import com.__found.smart_library.domain.book.entity.LifecycleStatus;
import com.__found.smart_library.domain.observation.entity.CandidateReason;
import java.math.BigDecimal;

/** 매칭 후보 1건과 후보 도서 정보. */
public record CandidateRow(
        Integer rank,
        CandidateReason reason,
        Long bookId,
        String registrationNo,
        String title,
        String callNumber,
        String copyNumber,
        LifecycleStatus lifecycleStatus,
        Long expectedShelfId,
        String expectedShelfCode,
        BigDecimal bookWidthCm
) {
}
