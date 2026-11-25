package com.__found.smart_library.domain.evaluation.query;

import com.__found.smart_library.domain.book.entity.LifecycleStatus;
import com.__found.smart_library.domain.book.entity.LoanStatus;
import com.__found.smart_library.domain.evaluation.entity.CandidateStatus;
import com.__found.smart_library.domain.evaluation.entity.CriterionType;
import com.__found.smart_library.domain.evaluation.entity.EvaluationType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 도서 평가 상세. composite 는 Backend 종합 결과(선정 근거 포함)이고, axes 는 평가축별 결과이다.
 * 각 축의 evaluatorType 으로 JEV 원본(희소성·내용 유효성)과 Backend 계산(수요성·중복성·물리적 상태)을 구분한다.
 * jev 는 이 회차의 JEV 평가 정보이며 JEV 결과가 없으면 null 이다.
 */
public record EvaluationDetailResponse(
        Long sessionId,
        BookInfo book,
        ShelfInfo shelf,
        Composite composite,
        JevInfo jev,
        List<Axis> axes) {

    public record BookInfo(Long bookId, String registrationNo, String title, String author, String callNumber,
                           LifecycleStatus lifecycleStatus, LoanStatus loanStatus) {
    }

    /** finalScore 는 평가 불가이면 null, profileVersion 은 계산에 쓴 평가 설정 버전이다. */
    public record Composite(Long evaluationId, BigDecimal finalScore, CandidateStatus candidateStatus,
                            String profileVersion, String reason, LocalDateTime evaluatedAt) {
    }

    public record JevInfo(Long evaluationId, String modelVersion, LocalDateTime evaluatedAt) {
    }

    /** confidence·selectedGrade·probabilityDistribution 은 JEV 평가축에만 있다. */
    public record Axis(CriterionType criterionType, EvaluationType evaluatorType, String modelVersion,
                       BigDecimal rawScore, BigDecimal normalizedScore, BigDecimal confidence,
                       Integer selectedGrade, Map<String, BigDecimal> probabilityDistribution) {
    }
}
