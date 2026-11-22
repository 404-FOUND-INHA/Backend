package com.__found.smart_library.domain.scan.entity;

/**
 * 회차의 진행 단계(화면 표시용). 저장하지 않고 상태에서 계산한다.
 * ANALYZING 판단 기준은 Vision 연동(A2)에서 정하므로 지금은 값만 정의한다.
 */
public enum ProcessingStage {
    VISION_WAITING,
    ANALYZING,
    ANALYSIS_DONE;

    public static ProcessingStage from(ScanSessionStatus status) {
        return switch (status) {
            case IN_PROGRESS -> VISION_WAITING;
            case REVIEW, COMPLETED -> ANALYSIS_DONE;
        };
    }
}
