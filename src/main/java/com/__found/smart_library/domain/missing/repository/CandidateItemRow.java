package com.__found.smart_library.domain.missing.repository;

import com.__found.smart_library.domain.missing.entity.MissingCandidateStatus;
import java.time.LocalDateTime;

/** 목록 조회용 투영. 후보와 도서·서가 정보를 한 번에 가져온다. */
public record CandidateItemRow(
        Long candidateId, Long bookId, String registrationNo, String title, String callNumber,
        Long expectedShelfId, String expectedShelfCode, int consecutiveMissingCount,
        MissingCandidateStatus status, Long reviewedBy, LocalDateTime reviewedAt, String note,
        LocalDateTime createdAt) {
}
