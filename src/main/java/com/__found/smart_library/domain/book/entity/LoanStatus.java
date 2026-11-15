package com.__found.smart_library.domain.book.entity;

/** 대출 상태: 대출 가능 / 대출 중 / 예약 / 분실 */
public enum LoanStatus {
    AVAILABLE,
    ON_LOAN,
    RESERVED,
    LOST
}
