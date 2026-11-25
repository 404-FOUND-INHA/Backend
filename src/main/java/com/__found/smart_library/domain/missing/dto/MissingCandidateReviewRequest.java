package com.__found.smart_library.domain.missing.dto;

import com.__found.smart_library.domain.missing.entity.MissingCandidateStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 현장 확인 결과. status 는 PENDING 이 아닌 값이어야 한다. */
public record MissingCandidateReviewRequest(
        @NotNull MissingCandidateStatus status,
        @Size(max = 1000) String note
) {
}
