package com.__found.smart_library.domain.observation.service;

import java.util.Objects;

/**
 * 분석할 관측 1건. detectedLabelRaw 는 OCR 원문(null·공백이면 해석 불가로 처리)이고,
 * detectedShelfId 는 관측된 서가로 반드시 있어야 한다(위치 판정의 기준).
 */
public record AnalysisInput(String detectedLabelRaw, Long detectedShelfId) {

    public AnalysisInput {
        Objects.requireNonNull(detectedShelfId, "관측 서가는 필수입니다.");
    }
}
