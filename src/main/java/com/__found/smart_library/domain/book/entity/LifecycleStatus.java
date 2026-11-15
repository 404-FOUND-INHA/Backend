package com.__found.smart_library.domain.book.entity;

/** 도서 생애 상태: 정상 / 소재불명 / 훼손 / 제적 */
public enum LifecycleStatus {
    NORMAL,
    MISSING,
    DAMAGED,
    DISPOSED
}
