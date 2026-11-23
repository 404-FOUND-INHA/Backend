package com.__found.smart_library.domain.observation.entity;

/**
 * 자동 분석이 내린 관측 도서의 상태. book_observation.analysis_status 에 이름(name)으로 저장된다.
 * 매칭 단계의 NO_MATCH·AMBIGUOUS 는 상태가 아니라 REVIEW_REQUIRED 의 사유(ObservationStatusReason)로 표현한다.
 * 사서의 최종 판단은 이 값을 바꾸지 않고 별도로 기록된다.
 */
public enum ObservationAnalysisStatus {
    /** 도서가 식별되었고 예상 서가와 관측 서가가 같다. */
    NORMAL,
    /** 도서가 식별되었으나 예상 서가가 아닌 곳에서 관측되었다. */
    MISPLACED,
    /** 자동으로 판단할 수 없어 사서의 확인이 필요하다(사유는 ObservationStatusReason). */
    REVIEW_REQUIRED
}
