package com.__found.smart_library.domain.evaluation.service;

import com.__found.smart_library.domain.observation.entity.ObservationAnalysisStatus;
import com.__found.smart_library.domain.observation.repository.BookObservationRepository;
import com.__found.smart_library.domain.review.entity.ReviewFinalStatus;
import com.__found.smart_library.domain.review.repository.ReviewRepository;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 회차에서 평가할 도서. 자동 분석으로 도서가 확정된 관측(NORMAL·MISPLACED)과, 사서가 도서를 확정한 관측
 * (NORMAL·MISPLACED)의 도서이며 같은 도서가 여러 번 나와도 한 번만 돌려준다(도서 ID 순).
 */
@Component
@RequiredArgsConstructor
public class SessionBookResolver {

    private static final List<ObservationAnalysisStatus> RESOLVED_OBSERVATIONS =
            List.of(ObservationAnalysisStatus.NORMAL, ObservationAnalysisStatus.MISPLACED);
    private static final List<ReviewFinalStatus> CONFIRMED_REVIEWS =
            List.of(ReviewFinalStatus.NORMAL, ReviewFinalStatus.MISPLACED);

    private final BookObservationRepository observationRepository;
    private final ReviewRepository reviewRepository;

    public Set<Long> findConfirmedBookIds(Long sessionId) {
        Set<Long> bookIds = new TreeSet<>(observationRepository.findResolvedBookIds(sessionId, RESOLVED_OBSERVATIONS));
        bookIds.addAll(reviewRepository.findConfirmedBookIds(sessionId, CONFIRMED_REVIEWS));
        return bookIds;
    }
}
