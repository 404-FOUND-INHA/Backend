package com.__found.smart_library.domain.scan.entity;

import java.util.List;

/**
 * 장서점검 회차 상태. DB 에는 문자열로 저장되며(scan_session.status), 허용 값은 이 enum 이 보장한다.
 * 미완료 회차 = IN_PROGRESS + REVIEW.
 */
public enum ScanSessionStatus {
    IN_PROGRESS,
    REVIEW,
    COMPLETED;

    /** 미완료 회차 상태. 자료실당 하나만 존재할 수 있다. */
    public static final List<ScanSessionStatus> OPEN_STATUSES = List.of(IN_PROGRESS, REVIEW);
}
