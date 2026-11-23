package com.__found.smart_library.domain.book.repository;

import com.__found.smart_library.domain.book.entity.LifecycleStatus;

/** 도서 매칭에 필요한 컬럼만 담은 조회 결과(엔티티를 로드하지 않는다). */
public record BookMatchInfo(
        Long bookId,
        String registrationNo,
        String title,
        String classificationNo,
        String bookSymbol,
        String copyNumber,
        Long expectedShelfId,
        LifecycleStatus lifecycleStatus
) {
}
