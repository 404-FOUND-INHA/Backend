package com.__found.smart_library.domain.observation.service;

import com.__found.smart_library.domain.observation.entity.ObservationAnalysisStatus;
import com.__found.smart_library.domain.observation.entity.ObservationStatusReason;
import com.__found.smart_library.domain.observation.service.MatchResult.Candidate;
import java.math.BigDecimal;
import java.util.List;

/**
 * 관측 1건의 자동 분석 결과(저장 전 계산 결과).
 *
 * @param reason     NORMAL 이면 null
 * @param parsed     파싱에 성공했을 때만 값이 있다(OCR_FAILED 이면 null)
 * @param bookId     자동으로 식별한 도서. 제적·중복·예상 서가 없음으로 검토가 필요해도 식별되었다면 유지하고,
 *                   OCR_FAILED·NO_MATCH·AMBIGUOUS_MATCH 이면 null
 * @param confidence 식별한 도서가 있을 때만 값이 있다(자동 매칭 1.0)
 * @param candidates NO_MATCH·AMBIGUOUS_MATCH 일 때의 후보(0~3건), 그 외에는 빈 목록
 */
public record ObservationAnalysis(
        ObservationAnalysisStatus status,
        ObservationStatusReason reason,
        ParsedCallNumber parsed,
        Long bookId,
        BigDecimal confidence,
        List<Candidate> candidates
) {

    static ObservationAnalysis ocrFailed() {
        return new ObservationAnalysis(ObservationAnalysisStatus.REVIEW_REQUIRED, ObservationStatusReason.OCR_FAILED,
                null, null, null, List.of());
    }

    static ObservationAnalysis unresolved(ObservationStatusReason reason, ParsedCallNumber parsed,
                                          List<Candidate> candidates) {
        return new ObservationAnalysis(ObservationAnalysisStatus.REVIEW_REQUIRED, reason, parsed, null, null,
                candidates);
    }

    /** 도서가 식별된 경우의 판정 결과. 식별 정보(도서·신뢰도)는 어떤 상태든 유지한다. */
    static ObservationAnalysis identified(ObservationAnalysisStatus status, ObservationStatusReason reason,
                                          ParsedCallNumber parsed, MatchResult match) {
        return new ObservationAnalysis(status, reason, parsed, match.matchedBook().bookId(), match.confidence(),
                List.of());
    }

    /** 같은 도서가 여러 번 관측된 경우: 식별 정보는 그대로 두고 검토 필요로 바꾼다. */
    ObservationAnalysis asDuplicate() {
        return new ObservationAnalysis(ObservationAnalysisStatus.REVIEW_REQUIRED,
                ObservationStatusReason.DUPLICATE_OBSERVATION, parsed, bookId, confidence, candidates);
    }
}
