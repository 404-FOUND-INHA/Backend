package com.__found.smart_library.domain.evaluation.query;

import com.__found.smart_library.domain.evaluation.entity.CandidateStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 평가 결과 목록의 한 건. finalScore 는 평가 불가(NOT_EVALUABLE)이면 null 이다. */
public record EvaluationItem(
        Long evaluationId,
        Long bookId,
        String title,
        String registrationNo,
        String callNumber,
        BigDecimal finalScore,
        CandidateStatus candidateStatus,
        String profileVersion,
        LocalDateTime evaluatedAt,
        ShelfInfo shelf) {

    static EvaluationItem from(EvaluationItemRow row) {
        return new EvaluationItem(row.evaluationId(), row.bookId(), row.title(), row.registrationNo(),
                row.callNumber(), row.finalScore(), row.candidateStatus(), row.profileVersion(), row.evaluatedAt(),
                ShelfInfo.from(row));
    }
}
