package com.__found.smart_library.domain.evaluation.jev;

import java.math.BigDecimal;
import java.util.Map;

/** System One 응답 중 Backend 가 쓰는 부분. 나머지 필드(usage, legend 등)는 무시한다. */
public record JevResponse(String model, Map<String, Answer> answers) {

    /** 질문 1개의 답. 점수형(score) 질문은 score·confidence·probabilities 를 돌려준다. */
    public record Answer(String type, BigDecimal score, BigDecimal confidence,
                         Map<String, BigDecimal> probabilities) {
    }
}
