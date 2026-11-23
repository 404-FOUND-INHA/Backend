package com.__found.smart_library.domain.observation.service;

import java.util.Optional;

/**
 * OCR 청구기호 라벨 → 구성요소 파서. 실제 구현(파싱·정규화)은 Vision 담당자가 제공한다.
 * Backend 에는 구현을 두지 않으며, 구현 빈이 없는 환경에서 분석이 성공한 것처럼 처리해서는 안 된다.
 */
public interface CallNumberParser {

    /**
     * @return 분류기호·도서기호를 모두 추출하면 그 결과, 라벨을 해석할 수 없으면 빈 값(OCR 인식 실패로 처리된다).
     *         결과 값은 저장 컬럼 길이(분류기호·도서기호 50자, 복본기호 30자) 이내여야 하며, 이를 넘기면 {@link ParsedCallNumber}
     *         생성 시 예외가 나 요청 전체가 실패한다(파서 계약 위반은 OCR 인식 실패로 숨기지 않는다).
     */
    Optional<ParsedCallNumber> parse(String rawLabel);
}
