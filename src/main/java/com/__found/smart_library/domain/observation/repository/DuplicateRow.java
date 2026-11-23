package com.__found.smart_library.domain.observation.repository;

/** 같은 도서로 매칭된 다른 관측의 위치. */
public record DuplicateRow(
        Long observationId,
        Long detectedShelfId,
        String detectedShelfCode,
        Integer detectedOrder
) {
}
