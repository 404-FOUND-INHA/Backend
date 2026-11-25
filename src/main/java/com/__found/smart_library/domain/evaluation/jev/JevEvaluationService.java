package com.__found.smart_library.domain.evaluation.jev;

import com.__found.smart_library.domain.book.entity.Book;
import com.__found.smart_library.domain.book.repository.BookRepository;
import com.__found.smart_library.domain.evaluation.entity.EvaluationType;
import com.__found.smart_library.domain.evaluation.repository.BookEvaluationRepository;
import com.__found.smart_library.domain.observation.entity.ObservationAnalysisStatus;
import com.__found.smart_library.domain.observation.repository.BookObservationRepository;
import com.__found.smart_library.domain.review.entity.ReviewFinalStatus;
import com.__found.smart_library.domain.review.repository.ReviewRepository;
import com.__found.smart_library.domain.scan.entity.ScanSession;
import com.__found.smart_library.domain.scan.entity.ScanSessionStatus;
import com.__found.smart_library.domain.scan.repository.ScanSessionRepository;
import com.__found.smart_library.global.exception.BusinessException;
import com.__found.smart_library.global.exception.ErrorCode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 점검이 확정된 회차의 도서를 JEV 에 평가시키고 결과를 저장한다.
 *
 * <p>대상은 확정된 도서(자동 분석으로 NORMAL·MISPLACED 이거나 사서가 도서를 확정한 관측)이며, 이미 이 회차의 JEV 결과가
 * 있는 도서는 비용을 아끼려고 다시 호출하지 않는다. 도서 1권당 한 번 호출하고 권마다 따로 저장하므로, 일부가 실패해도
 * 성공한 도서는 남고 다시 요청하면 실패한 도서만 평가한다.
 *
 * <p>이 메서드는 트랜잭션 없이 실행된다(외부 호출 동안 DB 연결을 잡지 않기 위해). 점검 확정 결과에는 영향을 주지 않는다.
 *
 * <p>같은 회차를 동시에 평가하면 같은 도서를 외부에 두 번 호출해 비용이 낭비되므로, 이미 진행 중인 회차의 요청은 호출 없이
 * 409 로 거부한다. 이 보호는 서버 인스턴스 안에서만 유효하다(서버를 여러 대로 늘리면 중복 호출은 막지 못하고, 저장만 DB
 * 유니크 인덱스로 한 건으로 지켜진다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JevEvaluationService {

    private static final List<ObservationAnalysisStatus> RESOLVED_OBSERVATIONS =
            List.of(ObservationAnalysisStatus.NORMAL, ObservationAnalysisStatus.MISPLACED);
    private static final List<ReviewFinalStatus> CONFIRMED_REVIEWS =
            List.of(ReviewFinalStatus.NORMAL, ReviewFinalStatus.MISPLACED);

    private final JevProperties properties;
    private final JevClient client;
    private final JevRequestFactory requestFactory;
    private final JevResultMapper resultMapper;
    private final JevEvaluationWriter writer;
    private final ScanSessionRepository scanSessionRepository;
    private final BookObservationRepository observationRepository;
    private final ReviewRepository reviewRepository;
    private final BookRepository bookRepository;
    private final BookEvaluationRepository evaluationRepository;

    /** 지금 JEV 평가를 진행 중인 회차. */
    private final Set<Long> runningSessions = ConcurrentHashMap.newKeySet();

    public JevEvaluationResponse evaluateSession(Long sessionId) {
        if (!properties.enabled()) {
            throw new BusinessException(ErrorCode.JEV_NOT_CONFIGURED);
        }
        ScanSession session = scanSessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SCAN_SESSION_NOT_FOUND));
        if (session.getStatus() != ScanSessionStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.JEV_SESSION_NOT_COMPLETED);
        }

        if (!runningSessions.add(sessionId)) {
            throw new BusinessException(ErrorCode.JEV_EVALUATION_IN_PROGRESS);
        }
        try {
            return evaluateBooks(sessionId);
        } finally {
            runningSessions.remove(sessionId);
        }
    }

    private JevEvaluationResponse evaluateBooks(Long sessionId) {
        Set<Long> bookIds = new TreeSet<>(observationRepository.findResolvedBookIds(sessionId, RESOLVED_OBSERVATIONS));
        bookIds.addAll(reviewRepository.findConfirmedBookIds(sessionId, CONFIRMED_REVIEWS));
        if (bookIds.isEmpty()) {
            return new JevEvaluationResponse(0, 0, 0, List.of());
        }

        Set<Long> alreadyEvaluated = new HashSet<>(
                evaluationRepository.findEvaluatedBookIds(sessionId, EvaluationType.JEV, bookIds));
        List<Book> targets = bookRepository.findAllById(bookIds.stream()
                .filter(id -> !alreadyEvaluated.contains(id)).toList()).stream()
                .sorted(Comparator.comparing(Book::getId))
                .toList();

        int succeeded = 0;
        int skipped = alreadyEvaluated.size();
        List<JevEvaluationResponse.Failure> failed = new ArrayList<>();
        for (Book book : targets) {
            try {
                JevResponse response = client.evaluate(requestFactory.create(book));
                List<JevCriterionResult> results = resultMapper.map(response);
                writer.save(sessionId, book.getId(), response.model(), results);
                succeeded++;
            } catch (DataIntegrityViolationException e) {
                skipped++;   // 다른 서버가 먼저 같은 도서의 결과를 저장했다(서버가 한 대면 일어나지 않는다).
            } catch (JevCallException | JevInvalidResponseException e) {
                log.warn("JEV 평가 실패 sessionId={} bookId={}: {}", sessionId, book.getId(), e.getMessage());
                failed.add(new JevEvaluationResponse.Failure(book.getId(), e.getMessage()));
            }
        }
        return new JevEvaluationResponse(bookIds.size(), succeeded, skipped, failed);
    }
}
