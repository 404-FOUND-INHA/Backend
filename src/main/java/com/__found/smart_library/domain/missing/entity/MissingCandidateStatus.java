package com.__found.smart_library.domain.missing.entity;

/** 소재불명 후보의 현장 확인 상태. 후보는 PENDING 으로 생성되고, 확인 결과를 기록하면 나머지 값으로 바뀐다. */
public enum MissingCandidateStatus {
    /** 확인 전. */
    PENDING,
    /** 현장에서 찾음. */
    FOUND,
    /** 현장에서 찾지 못함. */
    NOT_FOUND,
    /** 분실이 의심되어 분실 처리를 검토해야 함. */
    LOST_CANDIDATE,
    /** 사서가 종결 처리함. */
    RESOLVED;

    /** 연속 미발견 횟수를 이어 가지 않는 상태(도서가 확인되었거나 종결됨). */
    public boolean breaksStreak() {
        return this == FOUND || this == RESOLVED;
    }
}
