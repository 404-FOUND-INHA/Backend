package com.__found.smart_library.domain.missing.dto;

import com.__found.smart_library.domain.missing.entity.MissingCandidateStatus;
import com.__found.smart_library.domain.missing.repository.CandidateItemRow;
import java.time.LocalDateTime;

public record MissingCandidateResponse(
        Long candidateId,
        Book book,
        Long expectedShelfId,
        String expectedShelfCode,
        int consecutiveMissingCount,
        MissingCandidateStatus status,
        Long reviewedBy,
        LocalDateTime reviewedAt,
        String note,
        LocalDateTime createdAt
) {

    public record Book(Long bookId, String registrationNo, String title, String callNumber) {
    }

    public static MissingCandidateResponse from(CandidateItemRow r) {
        return new MissingCandidateResponse(
                r.candidateId(), new Book(r.bookId(), r.registrationNo(), r.title(), r.callNumber()),
                r.expectedShelfId(), r.expectedShelfCode(), r.consecutiveMissingCount(), r.status(),
                r.reviewedBy(), r.reviewedAt(), r.note(), r.createdAt());
    }
}
