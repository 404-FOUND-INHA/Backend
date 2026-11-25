package com.__found.smart_library.domain.observation.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 저장된 관측 중 Vision 이 보낸 입력값. 동일 결과 재전송 판별에 쓴다(equals 는 값 비교). */
public record StoredObservationInput(
        Long shelfId,
        Integer detectedOrder,
        String detectedLabelRaw,
        String detectedTitle,
        BigDecimal xPositionCm,
        LocalDateTime detectedAt,
        String ocrText,
        String partialCopyMark
) {
}
