package com.__found.smart_library.domain.observation.dto;

import com.__found.smart_library.domain.observation.entity.CandidateReason;
import com.__found.smart_library.domain.observation.entity.ObservationAnalysisStatus;
import com.__found.smart_library.domain.observation.entity.ObservationStatusReason;
import com.__found.smart_library.domain.observation.repository.CandidateRow;
import com.__found.smart_library.domain.observation.repository.DuplicateRow;
import com.__found.smart_library.domain.observation.repository.ObservationRow;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 관측 도서 1건의 상세(서가 배치도에서 도서를 선택했을 때의 하단 패널용).
 * analysisStatus·statusReason 의 의미는 {@link ShelfObservationsResponse} 를 따른다(자동 분석 결과, 불변).
 *
 * <ul>
 *   <li>matchedBook: 자동 매칭된 도서. 식별하지 못했으면 null(원문과 파싱값만 있다).</li>
 *   <li>candidates: 매칭 후보(순위순, 최대 3건). 후보가 없으면 빈 배열.</li>
 *   <li>duplicateObservations: statusReason 이 DUPLICATE_OBSERVATION 일 때 같은 회차에서 같은 도서로 매칭된
 *     <b>현재 관측을 제외한 모든</b> 관측(3건 이상일 수 있다, 개수 제한 없음). 그 밖의 경우는 빈 배열.</li>
 * </ul>
 */
public record ObservationDetailResponse(
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
        MatchedBook matchedBook,
        List<Candidate> candidates,
        List<DuplicateObservation> duplicateObservations
) {

    public record Candidate(int rank, CandidateReason reason, MatchedBook book) {
        public static Candidate from(CandidateRow r) {
            return new Candidate(r.rank(), r.reason(), MatchedBook.from(r));
        }
    }

    public record DuplicateObservation(
            Long observationId,
            Long detectedShelfId,
            String detectedShelfCode,
            Integer detectedOrder
    ) {
        public static DuplicateObservation from(DuplicateRow r) {
            return new DuplicateObservation(r.observationId(), r.detectedShelfId(), r.detectedShelfCode(),
                    r.detectedOrder());
        }
    }

    public static ObservationDetailResponse of(ObservationRow r, List<CandidateRow> candidates,
                                               List<DuplicateRow> duplicates) {
        return new ObservationDetailResponse(r.observationId(), r.detectedShelfId(), r.detectedShelfCode(),
                r.detectedOrder(), r.xPositionCm(), r.detectedLabelRaw(), r.detectedTitle(),
                r.detectedClassificationNo(), r.detectedBookSymbol(), r.detectedCopyNumber(), r.detectedAt(),
                r.receivedAt(), r.analysisStatus(), r.statusReason(), r.matchConfidence(), r.bookId(),
                MatchedBook.from(r),
                candidates.stream().map(Candidate::from).toList(),
                duplicates.stream().map(DuplicateObservation::from).toList());
    }
}
