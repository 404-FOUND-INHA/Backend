package com.__found.smart_library.domain.review.entity;

/**
 * 사서 검토 후의 최종 상태. 조치와 도서·서가 정보로 서버가 계산하며 클라이언트가 지정하지 않는다.
 * 자동 분석 상태(book_observation.analysis_status)는 바꾸지 않고 이 값을 따로 기록한다.
 */
public enum ReviewFinalStatus {
    /** 도서를 확정했고 예상 서가와 관측 서가가 같다. */
    NORMAL,
    /** 도서를 확정했으나 예상 서가와 다르거나 예상 서가가 지정되어 있지 않다. */
    MISPLACED,
    /** 도서를 알 수 없다. */
    UNIDENTIFIED,
    /** 점검 결과에서 제외했다. */
    EXCLUDED
}
