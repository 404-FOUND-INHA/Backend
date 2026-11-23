package com.__found.smart_library.domain.observation.dto;

import com.__found.smart_library.domain.observation.entity.ObservationStatusReason;
import com.__found.smart_library.domain.scan.entity.ProcessingStage;
import com.__found.smart_library.domain.scan.entity.ScanSessionStatus;
import com.__found.smart_library.domain.scan.entity.ScanShelfStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 회차의 자동 분석 결과 요약과 서가별 현황. 모든 값은 Backend 의 자동 분석 결과이며 사서의 검토 결과와 무관하다.
 * 수신 전(IN_PROGRESS)에는 모든 건수가 0 이고 서가는 WAITING 이다.
 *
 * <p>reasonCounts 는 사유가 있는 관측의 사유별 건수이며 값이 있는 사유만 나온다.
 */
public record ScanAnalysisResponse(
        SessionInfo session,
        Counts counts,
        Map<ObservationStatusReason, Long> reasonCounts,
        List<ShelfStatus> shelves
) {

    public record SessionInfo(Long id, ScanSessionStatus status, ProcessingStage processingStage) {
    }

    /** 분석 상태별 관측 수. reviewRequired 는 "인식 불가"가 아니라 사서 확인이 필요하다는 뜻이다(사유는 reasonCounts). */
    public record Counts(long total, long normal, long misplaced, long reviewRequired) {
    }

    public record ShelfStatus(
            Long shelfId,
            String shelfCode,
            BigDecimal shelfWidthCm,
            ScanShelfStatus scanStatus,
            Integer detectedBookCount,
            LocalDateTime scannedAt,
            Counts counts
    ) {
    }
}
