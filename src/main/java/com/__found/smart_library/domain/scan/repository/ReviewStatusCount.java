package com.__found.smart_library.domain.scan.repository;

import com.__found.smart_library.domain.review.entity.ReviewFinalStatus;

/** 회차의 사서 검토 결과별 건수. */
public record ReviewStatusCount(ReviewFinalStatus finalStatus, long total) {
}
