package com.__found.smart_library.domain.evaluation.jev;

import com.__found.smart_library.domain.evaluation.entity.BookEvaluation;
import com.__found.smart_library.domain.evaluation.entity.EvaluationCriterionResult;
import com.__found.smart_library.domain.evaluation.repository.BookEvaluationRepository;
import com.__found.smart_library.domain.evaluation.repository.EvaluationCriterionResultRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 도서 1권의 JEV 결과(평가 1건 + 평가축별 결과)를 한 트랜잭션으로 저장한다.
 * HTTP 호출은 이 트랜잭션 밖에서 끝낸 뒤에 부르므로, 외부 호출이 느려도 DB 연결을 붙잡지 않는다.
 * 같은 회차·도서의 결과가 이미 있으면 DB 유니크 인덱스 때문에 {@link org.springframework.dao.DataIntegrityViolationException}
 * 이 나고 아무것도 저장되지 않는다.
 */
@Component
@RequiredArgsConstructor
public class JevEvaluationWriter {

    private final BookEvaluationRepository evaluationRepository;
    private final EvaluationCriterionResultRepository criterionResultRepository;
    private final Clock clock;

    @Transactional
    public void save(Long sessionId, Long bookId, String modelVersion, List<JevCriterionResult> results) {
        BookEvaluation evaluation = evaluationRepository.saveAndFlush(
                BookEvaluation.fromJev(bookId, sessionId, modelVersion, LocalDateTime.now(clock)));
        List<EvaluationCriterionResult> rows = results.stream()
                .map(r -> EvaluationCriterionResult.ofJev(evaluation.getId(), r.criterionType(), modelVersion,
                        r.rawScore(), r.normalizedScore(), r.confidence(), r.selectedGrade(),
                        r.probabilityDistribution()))
                .toList();
        criterionResultRepository.saveAll(rows);
    }
}
