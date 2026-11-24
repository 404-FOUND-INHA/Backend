package com.__found.smart_library.domain.review.service;

import com.__found.smart_library.domain.audit.entity.AuditLog;
import com.__found.smart_library.domain.audit.repository.AuditLogRepository;
import com.__found.smart_library.domain.book.entity.Book;
import com.__found.smart_library.domain.book.repository.BookRepository;
import com.__found.smart_library.domain.observation.entity.BookObservation;
import com.__found.smart_library.domain.observation.entity.ObservationAnalysisStatus;
import com.__found.smart_library.domain.observation.repository.BookObservationRepository;
import com.__found.smart_library.domain.review.dto.ReviewListResponse;
import com.__found.smart_library.domain.review.dto.ReviewRequest;
import com.__found.smart_library.domain.review.dto.ReviewResult;
import com.__found.smart_library.domain.review.entity.Review;
import com.__found.smart_library.domain.review.entity.ReviewAction;
import com.__found.smart_library.domain.review.entity.ReviewFinalStatus;
import com.__found.smart_library.domain.review.repository.ReviewItemRow;
import com.__found.smart_library.domain.review.repository.ReviewQueryRepository;
import com.__found.smart_library.domain.review.repository.ReviewQueryRepository.ReviewCounts;
import com.__found.smart_library.domain.review.repository.ReviewRepository;
import com.__found.smart_library.domain.scan.entity.ScanSession;
import com.__found.smart_library.domain.scan.entity.ScanSessionStatus;
import com.__found.smart_library.domain.scan.repository.ScanSessionRepository;
import com.__found.smart_library.domain.user.repository.AppUserRepository;
import com.__found.smart_library.global.exception.BusinessException;
import com.__found.smart_library.global.exception.ErrorCode;
import com.__found.smart_library.global.lock.PostgresAdvisoryLock;
import com.__found.smart_library.global.response.PageResponse;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사서 검토. 자동 분석 결과(book_observation)는 건드리지 않고 검토 결과(review)를 따로 저장한다.
 *
 * <p>검토는 회차가 REVIEW 상태(Vision 결과 수신 후 ~ 확정 전)일 때만, 분석 결과가 REVIEW_REQUIRED 인 관측에만 할 수 있다.
 * 같은 회차의 검토를 같은 락으로 직렬화하므로 같은 도서를 서로 다른 관측에 동시에 확정하거나, 확정 처리와 검토가
 * 엇갈리는 일이 없다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewService {

    /** 회차별 검토·확정 직렬화용 Advisory Lock 네임스페이스. 다른 기능의 락 키와 겹치지 않게 고정값으로 쓴다. */
    public static final int REVIEW_LOCK_NAMESPACE = 40403;

    private static final int MAX_PAGE_SIZE = 100;
    private static final List<ObservationAnalysisStatus> RESOLVED_STATUSES =
            List.of(ObservationAnalysisStatus.NORMAL, ObservationAnalysisStatus.MISPLACED);

    private final ReviewRepository reviewRepository;
    private final ReviewQueryRepository reviewQueryRepository;
    private final BookObservationRepository observationRepository;
    private final ScanSessionRepository scanSessionRepository;
    private final BookRepository bookRepository;
    private final AppUserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final PostgresAdvisoryLock advisoryLock;
    private final Clock clock;

    /** 검토 필요 관측 목록. reviewed 가 null 이면 전체, true 면 검토 완료만, false 면 미검토만. */
    public ReviewListResponse list(Long sessionId, Long shelfId, Boolean reviewed, int page, int size) {
        if (!scanSessionRepository.existsById(sessionId)) {
            throw new BusinessException(ErrorCode.SCAN_SESSION_NOT_FOUND);
        }
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        if ((long) safePage * safeSize > Integer.MAX_VALUE) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "page 값이 너무 큽니다.");
        }
        List<ReviewItemRow> rows = reviewQueryRepository.findItems(
                sessionId, shelfId, reviewed, safePage * safeSize, safeSize);
        long total = reviewQueryRepository.countItems(sessionId, shelfId, reviewed);
        ReviewCounts counts = reviewQueryRepository.countAll(sessionId);
        List<ReviewListResponse.Item> items = rows.stream().map(ReviewListResponse.Item::from).toList();
        return new ReviewListResponse(
                ReviewListResponse.Summary.of(counts.totalRequired(), counts.reviewed()),
                PageResponse.from(new PageImpl<>(items, PageRequest.of(safePage, safeSize), total)));
    }

    /** 검토를 저장한다. 이미 검토한 관측이면 덮어쓴다(회차 확정 전까지). */
    @Transactional
    public ReviewResult save(Long sessionId, Long observationId, ReviewRequest request, Long reviewerId) {
        validateRequest(request);
        advisoryLock.lock(REVIEW_LOCK_NAMESPACE, String.valueOf(sessionId));

        requireReviewableSession(sessionId);
        BookObservation observation = findObservation(sessionId, observationId);
        if (observation.getAnalysisStatus() != ObservationAnalysisStatus.REVIEW_REQUIRED) {
            throw new BusinessException(ErrorCode.REVIEW_NOT_REQUIRED);
        }

        Book book = null;
        if (request.action() == ReviewAction.IDENTIFY_BOOK) {
            book = bookRepository.findById(request.finalBookId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.BOOK_NOT_FOUND));
            if (reviewRepository.existsOtherReviewWithBook(sessionId, book.getId(), observationId)
                    || reviewRepository.existsOtherResolvedObservationWithBook(
                            sessionId, book.getId(), observationId, RESOLVED_STATUSES)) {
                throw new BusinessException(ErrorCode.REVIEW_BOOK_ALREADY_ASSIGNED);
            }
        }

        ReviewFinalStatus finalStatus = calculateFinalStatus(request.action(), book, observation);
        String comment = normalize(request.comment());
        Long finalBookId = book == null ? null : book.getId();

        Review review = reviewRepository.findByObservationId(observationId).orElse(null);
        Map<String, Object> before = review == null ? null : snapshot(review);
        String auditAction = review == null ? AuditLog.ACTION_CREATE : AuditLog.ACTION_UPDATE;
        if (review == null) {
            review = Review.forObservation(observationId);
        }
        review.record(request.action(), finalBookId, finalStatus, reviewerId, comment, LocalDateTime.now(clock));
        review = reviewRepository.saveAndFlush(review);

        record(reviewerId, review.getId(), auditAction, before, snapshot(review));
        return ReviewResult.of(review, book, reviewerName(reviewerId));
    }

    /** 검토를 취소해 미검토 상태로 되돌린다(회차 확정 전까지). */
    @Transactional
    public void delete(Long sessionId, Long observationId, Long actorId) {
        advisoryLock.lock(REVIEW_LOCK_NAMESPACE, String.valueOf(sessionId));

        requireReviewableSession(sessionId);
        findObservation(sessionId, observationId);
        Review review = reviewRepository.findByObservationId(observationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REVIEW_NOT_FOUND));
        Map<String, Object> before = snapshot(review);
        reviewRepository.delete(review);
        reviewRepository.flush();
        record(actorId, review.getId(), AuditLog.ACTION_DELETE, before, null);
    }

    /**
     * 최종 상태 계산. 도서를 확정했으면 예상 서가와 관측 서가가 같을 때만 NORMAL 이고,
     * 다르거나 예상 서가가 없으면 MISPLACED 이다.
     */
    private static ReviewFinalStatus calculateFinalStatus(ReviewAction action, Book book, BookObservation observation) {
        return switch (action) {
            case MARK_UNIDENTIFIED -> ReviewFinalStatus.UNIDENTIFIED;
            case EXCLUDE -> ReviewFinalStatus.EXCLUDED;
            case IDENTIFY_BOOK -> {
                Long expectedShelfId = book.getExpectedShelf() == null ? null : book.getExpectedShelf().getId();
                yield expectedShelfId != null && Objects.equals(expectedShelfId, observation.getDetectedShelfId())
                        ? ReviewFinalStatus.NORMAL : ReviewFinalStatus.MISPLACED;
            }
        };
    }

    private static void validateRequest(ReviewRequest request) {
        boolean identify = request.action() == ReviewAction.IDENTIFY_BOOK;
        if (identify && request.finalBookId() == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "도서를 확정하려면 finalBookId 가 필요합니다.");
        }
        if (!identify && request.finalBookId() != null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "도서를 확정하는 조치가 아니면 finalBookId 를 보낼 수 없습니다.");
        }
    }

    private void requireReviewableSession(Long sessionId) {
        ScanSession session = scanSessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SCAN_SESSION_NOT_FOUND));
        if (session.getStatus() != ScanSessionStatus.REVIEW) {
            throw new BusinessException(ErrorCode.REVIEW_SESSION_NOT_OPEN);
        }
    }

    private BookObservation findObservation(Long sessionId, Long observationId) {
        return observationRepository.findById(observationId)
                .filter(o -> o.getScanSessionId().equals(sessionId))
                .orElseThrow(() -> new BusinessException(ErrorCode.SCAN_OBSERVATION_NOT_FOUND));
    }

    private String reviewerName(Long reviewerId) {
        return userRepository.findById(reviewerId).map(u -> u.getName()).orElse(null);
    }

    private static String normalize(String comment) {
        return comment == null || comment.isBlank() ? null : comment.strip();
    }

    private static Map<String, Object> snapshot(Review review) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("observationId", review.getObservationId());
        map.put("action", review.getReviewAction().name());
        map.put("finalBookId", review.getFinalBookId());
        map.put("finalStatus", review.getFinalStatus().name());
        map.put("comment", review.getComment());
        return map;
    }

    private void record(Long actorId, Long reviewId, String action, Map<String, Object> before,
                        Map<String, Object> after) {
        auditLogRepository.save(AuditLog.builder()
                .actorId(actorId)
                .entityType(AuditLog.ENTITY_REVIEW)
                .entityId(reviewId)
                .action(action)
                .beforeData(before)
                .afterData(after)
                .build());
    }
}
