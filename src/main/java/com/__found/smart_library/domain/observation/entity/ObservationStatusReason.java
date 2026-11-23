package com.__found.smart_library.domain.observation.entity;

/**
 * 관측 상태의 대표 사유. book_observation.status_reason 에 코드 값(name)으로 저장되고 화면 문구는 Frontend 가 매핑한다.
 * 한 관측에 사유가 여러 개 해당하면 하나만 저장하며, 도서가 식별된 경우의 우선순위는
 * DISPOSED_BOOK > DUPLICATE_OBSERVATION > NO_EXPECTED_SHELF 이다.
 */
public enum ObservationStatusReason {
    /** 라벨이 없거나 파서가 해석하지 못했다. */
    OCR_FAILED,
    /** 청구기호·복본기호가 모두 일치하는 도서가 없다. */
    NO_MATCH,
    /** 청구기호·복본기호가 모두 일치하는 도서가 둘 이상이라 하나로 정할 수 없다. */
    AMBIGUOUS_MATCH,
    /** 도서는 식별되었으나 예상 서가와 다른 서가에서 관측되었다(MISPLACED). */
    WRONG_SHELF,
    /** 같은 회차에서 같은 도서가 두 번 이상 관측되었다. */
    DUPLICATE_OBSERVATION,
    /** 제적 처리된 도서가 서가에서 발견되었다. */
    DISPOSED_BOOK,
    /** 도서에 예상 서가가 지정되어 있지 않아 위치를 판정할 수 없다. */
    NO_EXPECTED_SHELF
}
