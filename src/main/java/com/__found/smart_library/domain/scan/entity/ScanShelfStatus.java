package com.__found.smart_library.domain.scan.entity;

/**
 * 회차 내 서가별 점검 상태. 값은 필요한 기능을 구현할 때 추가한다.
 */
public enum ScanShelfStatus {
    /** 점검 대기(회차 생성 직후). */
    WAITING,
    /** Vision 결과를 수신해 자동 분석 결과를 저장함. */
    RESULT_RECEIVED
}
