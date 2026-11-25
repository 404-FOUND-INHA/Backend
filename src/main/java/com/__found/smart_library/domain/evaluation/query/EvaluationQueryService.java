package com.__found.smart_library.domain.evaluation.query;

import com.__found.smart_library.domain.book.entity.Book;
import com.__found.smart_library.domain.book.repository.BookRepository;
import com.__found.smart_library.domain.evaluation.entity.BookEvaluation;
import com.__found.smart_library.domain.evaluation.entity.CandidateStatus;
import com.__found.smart_library.domain.evaluation.entity.EvaluationCriterionResult;
import com.__found.smart_library.domain.evaluation.entity.EvaluationType;
import com.__found.smart_library.domain.evaluation.repository.BookEvaluationRepository;
import com.__found.smart_library.domain.evaluation.repository.EvaluationCriterionResultRepository;
import com.__found.smart_library.domain.scan.repository.ScanSessionRepository;
import com.__found.smart_library.domain.shelf.repository.ShelfRepository;
import com.__found.smart_library.global.exception.BusinessException;
import com.__found.smart_library.global.exception.ErrorCode;
import com.__found.smart_library.global.response.PageResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 도서 평가 결과·폐기 검토 후보 조회(읽기 전용). 시스템은 후보를 제안만 하며 최종 결정은 사서가 한다.
 * 서가 위치는 book.current_shelf 기준이다. 쿼리 수는 데이터 양과 무관하게 API 당 고정이다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EvaluationQueryService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final BigDecimal MIN_SCORE = BigDecimal.ZERO;
    private static final BigDecimal MAX_SCORE = BigDecimal.valueOf(100);

    private final EvaluationQueryRepository queryRepository;
    private final ScanSessionRepository scanSessionRepository;
    private final BookEvaluationRepository evaluationRepository;
    private final EvaluationCriterionResultRepository criterionResultRepository;
    private final BookRepository bookRepository;
    private final ShelfRepository shelfRepository;

    /** 회차의 종합 평가 목록. 판정·점수로 거르고 점수가 높은 순(점수 없는 도서는 마지막)으로 돌려준다. */
    public EvaluationListResponse list(Long sessionId, CandidateStatus status, BigDecimal minScore,
                                       BigDecimal maxScore, int page, int size) {
        requireSession(sessionId);
        validateFilters(status, minScore, maxScore);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        if ((long) safePage * safeSize > Integer.MAX_VALUE) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "page 값이 너무 큽니다.");
        }
        List<EvaluationItem> items = queryRepository
                .findItems(sessionId, status, minScore, maxScore, safePage * safeSize, safeSize).stream()
                .map(EvaluationItem::from).toList();
        long total = queryRepository.countItems(sessionId, status, minScore, maxScore);
        return new EvaluationListResponse(summary(sessionId),
                PageResponse.from(new PageImpl<>(items, PageRequest.of(safePage, safeSize), total)));
    }

    /** 회차의 폐기 검토 후보 목록(점수 높은 순, 서가 위치 포함). */
    public EvaluationListResponse disposalCandidates(Long sessionId, int page, int size) {
        return list(sessionId, CandidateStatus.DISPOSAL_REVIEW, null, null, page, size);
    }

    /**
     * 서가별 폐기 검토 후보. shelfId 를 주면 그 서가만 돌려준다. 후보가 없는 서가는 나오지 않는다.
     */
    public ShelfCandidatesResponse candidatesByShelf(Long sessionId, Long shelfId) {
        requireSession(sessionId);
        if (shelfId != null && !shelfRepository.existsById(shelfId)) {
            throw new BusinessException(ErrorCode.SHELF_NOT_FOUND);
        }
        List<EvaluationItemRow> rows = queryRepository.findCandidates(sessionId, shelfId);
        Map<Long, List<EvaluationItemRow>> byShelf = new LinkedHashMap<>();
        for (EvaluationItemRow row : rows) {
            byShelf.computeIfAbsent(row.shelfId(), k -> new ArrayList<>()).add(row);
        }
        List<ShelfCandidatesResponse.ShelfGroup> groups = byShelf.values().stream()
                .map(EvaluationQueryService::toGroup)
                .sorted(Comparator
                        .comparing((ShelfCandidatesResponse.ShelfGroup g) -> g.shelf() == null)   // 서가 미지정은 마지막
                        .thenComparing(ShelfCandidatesResponse.ShelfGroup::candidateCount, Comparator.reverseOrder())
                        .thenComparing(g -> g.shelf() == null ? "" : g.shelf().shelfCode()))
                .toList();
        return new ShelfCandidatesResponse(rows.size(), groups);
    }

    /** 도서 평가 상세: 종합 결과와 선정 근거, 평가축별 결과(JEV 원본과 Backend 계산 구분). */
    public EvaluationDetailResponse detail(Long sessionId, Long bookId) {
        requireSession(sessionId);
        BookEvaluation composite = findOne(sessionId, EvaluationType.BACKEND, bookId);
        if (composite == null) {
            throw new BusinessException(ErrorCode.EVALUATION_NOT_FOUND);
        }
        BookEvaluation jev = findOne(sessionId, EvaluationType.JEV, bookId);
        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BOOK_NOT_FOUND));

        List<Long> evaluationIds = jev == null ? List.of(composite.getId()) : List.of(composite.getId(), jev.getId());
        List<EvaluationDetailResponse.Axis> axes = criterionResultRepository.findByEvaluationIdIn(evaluationIds)
                .stream()
                .sorted(Comparator.comparing(EvaluationCriterionResult::getCriterionType))
                .map(EvaluationQueryService::toAxis)
                .toList();
        return new EvaluationDetailResponse(
                sessionId,
                new EvaluationDetailResponse.BookInfo(book.getId(), book.getRegistrationNo(), book.getTitle(),
                        book.getAuthor(), book.getCallNumberRaw(), book.getLifecycleStatus(), book.getLoanStatus()),
                ShelfInfo.from(book.getCurrentShelf()),
                new EvaluationDetailResponse.Composite(composite.getId(), composite.getFinalScore(),
                        composite.getCandidateStatus(), composite.getModelVersion(), composite.getEvaluationReason(),
                        composite.getEvaluatedAt()),
                jev == null ? null : new EvaluationDetailResponse.JevInfo(jev.getId(), jev.getModelVersion(),
                        jev.getEvaluatedAt()),
                axes);
    }

    private EvaluationListResponse.Summary summary(Long sessionId) {
        Map<CandidateStatus, Long> counts = queryRepository.countByStatus(sessionId);
        long disposal = counts.get(CandidateStatus.DISPOSAL_REVIEW);
        long keep = counts.get(CandidateStatus.KEEP);
        long notEvaluable = counts.get(CandidateStatus.NOT_EVALUABLE);
        return new EvaluationListResponse.Summary(disposal + keep + notEvaluable, disposal, keep, notEvaluable);
    }

    private BookEvaluation findOne(Long sessionId, EvaluationType type, Long bookId) {
        return evaluationRepository.findByScanSessionIdAndEvaluationTypeAndBookIdIn(sessionId, type, List.of(bookId))
                .stream().findFirst().orElse(null);
    }

    private void requireSession(Long sessionId) {
        if (!scanSessionRepository.existsById(sessionId)) {
            throw new BusinessException(ErrorCode.SCAN_SESSION_NOT_FOUND);
        }
    }

    private static void validateFilters(CandidateStatus status, BigDecimal minScore, BigDecimal maxScore) {
        if (status == CandidateStatus.UNDECIDED) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "종합 평가에는 UNDECIDED 상태가 없습니다.");
        }
        for (BigDecimal score : new BigDecimal[] {minScore, maxScore}) {
            if (score != null && (score.compareTo(MIN_SCORE) < 0 || score.compareTo(MAX_SCORE) > 0)) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "점수는 0~100 이어야 합니다.");
            }
        }
        if (minScore != null && maxScore != null && minScore.compareTo(maxScore) > 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "minScore 는 maxScore 보다 클 수 없습니다.");
        }
    }

    private static ShelfCandidatesResponse.ShelfGroup toGroup(List<EvaluationItemRow> rows) {
        List<ShelfCandidatesResponse.CandidateBook> books = rows.stream()
                .map(r -> new ShelfCandidatesResponse.CandidateBook(r.bookId(), r.title(), r.callNumber(),
                        r.finalScore()))
                .toList();
        // 후보는 점수가 있는 도서뿐이므로 첫 줄(점수 높은 순)이 최고 점수이다.
        return new ShelfCandidatesResponse.ShelfGroup(ShelfInfo.from(rows.get(0)), books.size(),
                rows.get(0).finalScore(), books);
    }

    private static EvaluationDetailResponse.Axis toAxis(EvaluationCriterionResult r) {
        return new EvaluationDetailResponse.Axis(r.getCriterionType(), r.getEvaluatorType(), r.getModelVersion(),
                r.getRawScore(), r.getNormalizedScore(), r.getConfidence(), r.getSelectedGrade(),
                r.getProbabilityDistribution());
    }
}
