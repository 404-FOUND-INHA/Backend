package com.__found.smart_library.domain.scan.service;

import com.__found.smart_library.domain.audit.entity.AuditLog;
import com.__found.smart_library.domain.audit.repository.AuditLogRepository;
import com.__found.smart_library.domain.book.entity.Book;
import com.__found.smart_library.domain.book.entity.LifecycleStatus;
import com.__found.smart_library.domain.book.repository.BookRepository;
import com.__found.smart_library.domain.missing.service.MissingCandidateGenerator;
import com.__found.smart_library.domain.observation.entity.ObservationAnalysisStatus;
import com.__found.smart_library.domain.review.entity.ReviewFinalStatus;
import com.__found.smart_library.domain.review.repository.ReviewQueryRepository;
import com.__found.smart_library.domain.review.repository.ReviewQueryRepository.ReviewCounts;
import com.__found.smart_library.domain.review.service.ReviewService;
import com.__found.smart_library.domain.scan.dto.FinalizeResponse;
import com.__found.smart_library.domain.scan.entity.ScanSession;
import com.__found.smart_library.domain.scan.entity.ScanSessionStatus;
import com.__found.smart_library.domain.scan.repository.BookFinding;
import com.__found.smart_library.domain.scan.repository.ReviewStatusCount;
import com.__found.smart_library.domain.scan.repository.ScanFindingRepository;
import com.__found.smart_library.domain.scan.repository.ScanSessionRepository;
import com.__found.smart_library.domain.shelf.entity.Shelf;
import com.__found.smart_library.domain.shelf.repository.ShelfRepository;
import com.__found.smart_library.global.exception.BusinessException;
import com.__found.smart_library.global.exception.ErrorCode;
import com.__found.smart_library.global.lock.PostgresAdvisoryLock;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 장서점검 회차 확정. 사서 검토가 모두 끝난 회차(REVIEW)만 확정할 수 있고, 확정하면 점검에서 위치가 확인된 도서를
 * BOOK 에 반영한 뒤 회차를 COMPLETED 로 바꾼다. 전체가 하나의 트랜잭션이라 어느 단계에서 실패해도 아무것도 바뀌지 않는다.
 *
 * <p>BOOK 반영 규칙(도서가 식별되어 서가가 정해진 관측만 대상):
 * <ul>
 *   <li>정상·오배치(자동 판정 또는 사서 확정): 마지막 확인 서가(current_shelf)를 관측 서가로 바꾼다.
 *       정상 배치 서가(expected_shelf)는 바꾸지 않아 오배치 도서를 나중에 제자리로 돌려놓을 수 있다.</li>
 *   <li>소재불명(MISSING)이던 도서를 찾았으면 정상(NORMAL)으로 되돌린다. 훼손·제적 상태는 바꾸지 않는다.</li>
 *   <li>식별 불가·제외: 어떤 도서인지 정해지지 않았거나 도서가 아니므로 BOOK 을 바꾸지 않는다.</li>
 *   <li>점검에서 발견되지 않은 도서는 BOOK 을 바꾸지 않고 소재불명 후보(missing_candidate)로만 만든다.</li>
 * </ul>
 * 바뀐 도서마다 audit_log 에 변경 필드만 남긴다(도서 이력 API 에 그대로 보인다).
 *
 * <p>검토와 같은 회차 락을 잡아, 확정 도중에 검토가 바뀌거나 확정이 두 번 실행되는 일이 없다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScanFinalizationService {

    /** IN 절이 너무 길어지지 않도록 도서·서가를 나눠 조회하는 크기. */
    private static final int CHUNK = 1000;
    private static final List<ObservationAnalysisStatus> RESOLVED_ANALYSIS =
            List.of(ObservationAnalysisStatus.NORMAL, ObservationAnalysisStatus.MISPLACED);
    private static final List<ReviewFinalStatus> RESOLVED_REVIEW =
            List.of(ReviewFinalStatus.NORMAL, ReviewFinalStatus.MISPLACED);

    private final ScanSessionRepository scanSessionRepository;
    private final ScanFindingRepository findingRepository;
    private final ReviewQueryRepository reviewQueryRepository;
    private final BookRepository bookRepository;
    private final ShelfRepository shelfRepository;
    private final AuditLogRepository auditLogRepository;
    private final MissingCandidateGenerator missingCandidateGenerator;
    private final PostgresAdvisoryLock advisoryLock;
    private final Clock clock;

    @Transactional
    public FinalizeResponse finalizeSession(Long sessionId, Long userId) {
        advisoryLock.lock(ReviewService.REVIEW_LOCK_NAMESPACE, String.valueOf(sessionId));

        ScanSession session = scanSessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SCAN_SESSION_NOT_FOUND));
        if (session.getStatus() != ScanSessionStatus.REVIEW) {
            throw new BusinessException(ErrorCode.SCAN_SESSION_NOT_FINALIZABLE);
        }
        ReviewCounts counts = reviewQueryRepository.countAll(sessionId);
        long remaining = counts.totalRequired() - counts.reviewed();
        if (remaining > 0) {
            throw new BusinessException(ErrorCode.SCAN_REVIEW_INCOMPLETE,
                    "검토가 끝나지 않은 관측이 " + remaining + "건 있어 확정할 수 없습니다.");
        }

        Map<Long, Long> shelfByBook = collectFindings(sessionId);
        Reflection reflection = reflectToBooks(shelfByBook, userId);

        LocalDateTime now = LocalDateTime.now(clock);
        session.finalizeBy(userId, now);
        long missingCandidates = missingCandidateGenerator.generate(session, shelfByBook.keySet(), now);

        long unidentified = 0;
        long excluded = 0;
        for (ReviewStatusCount c : findingRepository.countReviewsByFinalStatus(sessionId)) {
            if (c.finalStatus() == ReviewFinalStatus.UNIDENTIFIED) {
                unidentified = c.total();
            } else if (c.finalStatus() == ReviewFinalStatus.EXCLUDED) {
                excluded = c.total();
            }
        }
        return FinalizeResponse.of(session, new FinalizeResponse.Summary(
                shelfByBook.size(), reflection.locationChanged(), reflection.restoredFromMissing(),
                unidentified, excluded, missingCandidates));
    }

    /**
     * 도서 -> 관측 서가. 같은 도서가 두 번 나오면 검토 단계의 중복 확정 방지 규칙이 깨진 것이므로
     * 어느 쪽이 맞는지 알 수 없어 확정하지 않고 실패시킨다(롤백).
     */
    private Map<Long, Long> collectFindings(Long sessionId) {
        Map<Long, Long> shelfByBook = new LinkedHashMap<>();
        List<BookFinding> findings = new ArrayList<>(findingRepository.findAutoResolved(sessionId, RESOLVED_ANALYSIS));
        findings.addAll(findingRepository.findReviewResolved(sessionId, RESOLVED_REVIEW));
        for (BookFinding f : findings) {
            if (shelfByBook.put(f.bookId(), f.shelfId()) != null) {
                throw new IllegalStateException("회차 " + sessionId + " 에서 도서 " + f.bookId()
                        + " 가 둘 이상의 관측에 확정되어 있습니다.");
            }
        }
        return shelfByBook;
    }

    private Reflection reflectToBooks(Map<Long, Long> shelfByBook, Long actorId) {
        if (shelfByBook.isEmpty()) {
            return new Reflection(0, 0);
        }
        // 서가를 먼저 영속성 컨텍스트에 올려 두면 도서의 현재 서가(지연 로딩)를 읽을 때 추가 쿼리가 없다.
        Set<Long> shelfIds = new java.util.HashSet<>(shelfByBook.values());
        Map<Long, Shelf> shelves = new HashMap<>();
        for (List<Long> chunk : chunks(new ArrayList<>(shelfIds))) {
            shelfRepository.findAllById(chunk).forEach(s -> shelves.put(s.getId(), s));
        }

        long locationChanged = 0;
        long restored = 0;
        List<AuditLog> audits = new ArrayList<>();
        for (List<Long> chunk : chunks(new ArrayList<>(shelfByBook.keySet()))) {
            for (Book book : bookRepository.findAllById(chunk)) {
                Shelf observed = shelves.get(shelfByBook.get(book.getId()));
                Long beforeShelfId = book.getCurrentShelf() == null ? null : book.getCurrentShelf().getId();
                String beforeShelfCode = book.getCurrentShelf() == null ? null : book.getCurrentShelf().getShelfCode();
                LifecycleStatus beforeStatus = book.getLifecycleStatus();

                book.confirmAtShelf(observed);

                boolean shelfChanged = !Objects.equals(beforeShelfId, observed.getId());
                boolean statusChanged = beforeStatus != book.getLifecycleStatus();
                if (shelfChanged) {
                    locationChanged++;
                }
                if (statusChanged) {
                    restored++;
                }
                if (shelfChanged || statusChanged) {
                    audits.add(auditOf(actorId, book, shelfChanged, beforeShelfId, beforeShelfCode, observed,
                            statusChanged, beforeStatus));
                }
            }
        }
        auditLogRepository.saveAll(audits);
        return new Reflection(locationChanged, restored);
    }

    /** 바뀐 필드만 담는다(도서 이력 API 와 같은 키). 상태가 바뀌었으면 STATUS_CHANGE, 위치만 바뀌면 UPDATE. */
    private AuditLog auditOf(Long actorId, Book book, boolean shelfChanged, Long beforeShelfId,
                             String beforeShelfCode, Shelf observed, boolean statusChanged,
                             LifecycleStatus beforeStatus) {
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        if (shelfChanged) {
            before.put("currentShelfId", beforeShelfId);
            before.put("currentShelfCode", beforeShelfCode);
            after.put("currentShelfId", observed.getId());
            after.put("currentShelfCode", observed.getShelfCode());
        }
        if (statusChanged) {
            before.put("lifecycleStatus", beforeStatus.name());
            after.put("lifecycleStatus", book.getLifecycleStatus().name());
        }
        return AuditLog.builder()
                .actorId(actorId)
                .entityType(AuditLog.ENTITY_BOOK)
                .entityId(book.getId())
                .action(statusChanged ? AuditLog.ACTION_STATUS_CHANGE : AuditLog.ACTION_UPDATE)
                .beforeData(before)
                .afterData(after)
                .build();
    }

    private static List<List<Long>> chunks(List<Long> ids) {
        List<List<Long>> out = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += CHUNK) {
            out.add(ids.subList(i, Math.min(i + CHUNK, ids.size())));
        }
        return out;
    }

    private record Reflection(long locationChanged, long restoredFromMissing) {
    }
}
