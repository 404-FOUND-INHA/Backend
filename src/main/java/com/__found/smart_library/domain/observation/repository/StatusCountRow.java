package com.__found.smart_library.domain.observation.repository;

import com.__found.smart_library.domain.observation.entity.ObservationAnalysisStatus;
import com.__found.smart_library.domain.observation.entity.ObservationStatusReason;

/** 서가·분석 상태·사유별 관측 수. statusReason 은 NORMAL 등에서 null 이다. */
public record StatusCountRow(
        Long shelfId,
        ObservationAnalysisStatus status,
        ObservationStatusReason reason,
        Long total
) {
}
