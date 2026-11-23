package com.__found.smart_library.domain.observation.repository;

import com.__found.smart_library.domain.scan.entity.ScanShelfStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 회차의 점검 대상 서가 1개의 정보와 수신 현황. */
public record ShelfRow(
        Long shelfId,
        String shelfCode,
        BigDecimal widthCm,
        ScanShelfStatus scanStatus,
        Integer detectedBookCount,
        LocalDateTime scannedAt
) {
}
