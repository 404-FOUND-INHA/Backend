package com.__found.smart_library.domain.evaluation.query;

import com.__found.smart_library.domain.evaluation.entity.CandidateStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 목록 조회용 한 줄: 종합 평가와 도서, 도서의 현재 서가(없으면 서가 값은 모두 null)를 한 번의 쿼리로 가져온다. */
public record EvaluationItemRow(
        Long evaluationId,
        Long bookId,
        String title,
        String registrationNo,
        String callNumber,
        BigDecimal finalScore,
        CandidateStatus candidateStatus,
        String profileVersion,
        LocalDateTime evaluatedAt,
        Long shelfId,
        String shelfCode,
        String zone,
        Integer floorNo,
        Integer shelfNo,
        Integer levelNo,
        BigDecimal mapX,
        BigDecimal mapY,
        BigDecimal mapZ) {
}
