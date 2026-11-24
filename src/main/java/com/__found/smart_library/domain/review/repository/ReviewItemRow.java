package com.__found.smart_library.domain.review.repository;

import com.__found.smart_library.domain.observation.entity.ObservationStatusReason;
import com.__found.smart_library.domain.review.entity.ReviewAction;
import com.__found.smart_library.domain.review.entity.ReviewFinalStatus;
import java.time.LocalDateTime;

/** 검토 목록의 한 행(검토 필요 관측 + 자동 매칭 도서 + 검토 결과). 검토 전이면 review* 는 모두 null. */
public record ReviewItemRow(
        Long observationId,
        Long shelfId,
        String shelfCode,
        Integer detectedOrder,
        String detectedLabelRaw,
        String detectedTitle,
        ObservationStatusReason statusReason,
        Long autoBookId,
        String autoRegistrationNo,
        String autoTitle,
        String autoCallNumber,
        long candidateCount,
        Long reviewId,
        ReviewAction reviewAction,
        ReviewFinalStatus finalStatus,
        Long finalBookId,
        String finalRegistrationNo,
        String finalTitle,
        String finalCallNumber,
        String comment,
        Long reviewerId,
        String reviewerName,
        LocalDateTime reviewedAt
) {
}
