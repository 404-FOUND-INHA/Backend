package com.__found.smart_library.domain.review.entity;

/** 사서가 검토 필요 관측에 취한 조치. review.review_action 에 이름으로 저장된다. */
public enum ReviewAction {
    /** 이 관측이 어떤 도서인지 확정한다(자동 매칭 도서를 그대로 인정하는 경우 포함). 도서 지정이 필요하다. */
    IDENTIFY_BOOK,
    /** 직접 확인했지만 어떤 도서인지 알 수 없다. */
    MARK_UNIDENTIFIED,
    /** 도서가 아니거나 잘못 검출된 관측이라 점검 결과에서 제외한다. */
    EXCLUDE
}
