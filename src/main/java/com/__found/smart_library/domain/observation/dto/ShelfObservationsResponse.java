package com.__found.smart_library.domain.observation.dto;

import com.__found.smart_library.domain.observation.entity.ObservationAnalysisStatus;
import com.__found.smart_library.domain.observation.entity.ObservationStatusReason;
import com.__found.smart_library.domain.observation.repository.ObservationRow;
import com.__found.smart_library.domain.observation.repository.ShelfRow;
import com.__found.smart_library.domain.scan.entity.ScanShelfStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 서가 1개의 관측 목록(서가 배치도용). 배치 순서(detectedOrder) 오름차순이며 전체를 한 번에 내려 준다(페이지네이션 없음).
 * 후보 전체는 상세 API 에서만 제공하고 여기서는 candidateCount 만 준다.
 *
 * <p><b>상태 표시 규칙</b> analysisStatus 는 Backend 의 자동 분석 결과이며 불변이다(사서의 최종 검토 결과가 아니다).
 * <ul>
 *   <li>NORMAL: 정상</li>
 *   <li>MISPLACED(WRONG_SHELF): 오배치 — matchedBook.expectedShelf* 로 어느 서가의 도서인지 알 수 있다</li>
 *   <li>REVIEW_REQUIRED: 사서 확인이 필요하며 "인식 불가"를 뜻하지 않는다. 사유(statusReason)로 구분한다.
 *     OCR_FAILED·NO_MATCH·AMBIGUOUS_MATCH 는 미인식·미식별 관련이고, DUPLICATE_OBSERVATION·DISPOSED_BOOK·
 *     NO_EXPECTED_SHELF 는 도서는 식별되었으나 다른 사유로 확인이 필요한 경우다.</li>
 * </ul>
 * 폐기 검토 후보는 A2 의 분석 상태가 아니라 후속 기능의 결과이므로 여기에 나오지 않는다.
 * xPositionCm 은 Vision 이 보낸 값 그대로이며 null 일 수 있다(그 경우 FE 는 순서 기반으로 배치한다).
 */
public record ShelfObservationsResponse(ShelfInfo shelf, List<ObservationItem> observations) {

    public record ShelfInfo(
            Long shelfId,
            String shelfCode,
            BigDecimal shelfWidthCm,
            ScanShelfStatus scanStatus,
            Integer detectedBookCount,
            LocalDateTime scannedAt
    ) {
        public static ShelfInfo from(ShelfRow r) {
            return new ShelfInfo(r.shelfId(), r.shelfCode(), r.widthCm(), r.scanStatus(), r.detectedBookCount(),
                    r.scannedAt());
        }
    }

    public record ObservationItem(
            Long observationId,
            Integer detectedOrder,
            BigDecimal xPositionCm,
            String detectedLabelRaw,
            String detectedTitle,
            String detectedClassificationNo,
            String detectedBookSymbol,
            String detectedCopyNumber,
            ObservationAnalysisStatus analysisStatus,
            ObservationStatusReason statusReason,
            BigDecimal matchConfidence,
            Long bookId,
            MatchedBook matchedBook,
            long candidateCount
    ) {
        public static ObservationItem from(ObservationRow r) {
            return new ObservationItem(r.observationId(), r.detectedOrder(), r.xPositionCm(), r.detectedLabelRaw(),
                    r.detectedTitle(), r.detectedClassificationNo(), r.detectedBookSymbol(), r.detectedCopyNumber(),
                    r.analysisStatus(), r.statusReason(), r.matchConfidence(), r.bookId(), MatchedBook.from(r),
                    r.candidateCount() == null ? 0 : r.candidateCount());
        }
    }
}
