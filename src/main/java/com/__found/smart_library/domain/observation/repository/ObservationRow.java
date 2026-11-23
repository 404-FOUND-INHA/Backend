package com.__found.smart_library.domain.observation.repository;

import com.__found.smart_library.domain.book.entity.LifecycleStatus;
import com.__found.smart_library.domain.observation.entity.ObservationAnalysisStatus;
import com.__found.smart_library.domain.observation.entity.ObservationStatusReason;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 관측 1건과 매칭 도서·기대 서가·후보 수. 매칭 도서 관련 컬럼(book*, expectedShelf*)은 bookId 가 null 이면 모두 null 이다.
 * 목록과 상세가 같은 모양을 공유한다.
 */
public record ObservationRow(
        Long observationId,
        Long detectedShelfId,
        String detectedShelfCode,
        Integer detectedOrder,
        BigDecimal xPositionCm,
        String detectedLabelRaw,
        String detectedTitle,
        String detectedClassificationNo,
        String detectedBookSymbol,
        String detectedCopyNumber,
        LocalDateTime detectedAt,
        LocalDateTime receivedAt,
        ObservationAnalysisStatus analysisStatus,
        ObservationStatusReason statusReason,
        BigDecimal matchConfidence,
        Long bookId,
        String registrationNo,
        String bookTitle,
        String callNumber,
        String copyNumber,
        LifecycleStatus lifecycleStatus,
        Long expectedShelfId,
        String expectedShelfCode,
        BigDecimal bookWidthCm,
        Long candidateCount
) {
}
