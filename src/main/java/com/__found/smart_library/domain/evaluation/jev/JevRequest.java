package com.__found.smart_library.domain.evaluation.jev;

import java.util.Map;

/** System One 요청 본문. state 는 평가 대상(도서 정보), questions 는 질문 이름별 질문. */
public record JevRequest(String model, Map<String, Object> state, Map<String, Object> questions) {
}
