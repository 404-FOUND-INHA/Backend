package com.__found.smart_library.domain.observation.entity;

/**
 * 매칭 후보를 고른 근거. 코드 값(name)이 observation_match_candidate.match_reason 에 저장되고,
 * 화면 문구는 Frontend 가 매핑한다. 선언 순서가 곧 우선순위(앞쪽이 더 강한 근거)다.
 */
public enum CandidateReason {
    /** 분류기호·도서기호·복본기호가 모두 일치하는 도서가 여러 권. */
    EXACT_MATCH,
    /** 분류기호·도서기호는 일치하고 복본기호만 다름(복본 표기 누락·오독 가능). */
    COPY_MISMATCH,
    /** 도서기호만 일치하고 분류기호가 다름(분류기호 오독 가능). */
    SYMBOL_ONLY,
    /** 분류기호만 일치하고 도서기호가 다름(같은 주제 서가). */
    CLASSIFICATION_ONLY
}
