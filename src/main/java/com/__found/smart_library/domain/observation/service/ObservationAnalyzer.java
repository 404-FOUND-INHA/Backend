package com.__found.smart_library.domain.observation.service;

import com.__found.smart_library.domain.book.entity.LifecycleStatus;
import com.__found.smart_library.domain.book.repository.BookMatchInfo;
import com.__found.smart_library.domain.observation.entity.ObservationAnalysisStatus;
import com.__found.smart_library.domain.observation.entity.ObservationStatusReason;
import com.__found.smart_library.global.exception.BusinessException;
import com.__found.smart_library.global.exception.ErrorCode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 관측 목록을 파싱 → 매칭 → 판정해 상태를 계산한다(저장은 하지 않는다).
 *
 * <p>건별 판정(위에서부터 먼저 해당하는 것):
 * <ol>
 *   <li>라벨이 없거나 파서가 해석하지 못함 → REVIEW_REQUIRED / OCR_FAILED</li>
 *   <li>완전 일치 도서 없음 → REVIEW_REQUIRED / NO_MATCH (후보 유지)</li>
 *   <li>완전 일치 도서 2권 이상 → REVIEW_REQUIRED / AMBIGUOUS_MATCH (후보 유지)</li>
 *   <li>유일 매칭 + 제적 도서 → REVIEW_REQUIRED / DISPOSED_BOOK</li>
 *   <li>유일 매칭 + 예상 서가 없음 → REVIEW_REQUIRED / NO_EXPECTED_SHELF</li>
 *   <li>유일 매칭 + 예상 서가 = 관측 서가 → NORMAL</li>
 *   <li>유일 매칭 + 예상 서가 ≠ 관측 서가 → MISPLACED / WRONG_SHELF</li>
 * </ol>
 * 회차 전체 규칙: 같은 도서로 유일 매칭된 관측이 둘 이상이면 그 관측 모두 REVIEW_REQUIRED / DUPLICATE_OBSERVATION
 * (이미 DISPOSED_BOOK 이면 그 사유를 유지). 도서가 식별된 경우 book_id·신뢰도는 검토 필요여도 유지한다.
 *
 * <p>파서 구현은 Vision 담당자가 제공한다. 빈이 없으면 성공한 것처럼 처리하지 않고 503 으로 실패시키며,
 * 파서가 던지는 예외는 숨기지 않고 그대로 전파한다(라벨을 해석하지 못한 정상적인 경우만 OCR_FAILED).
 */
@Component
@RequiredArgsConstructor
public class ObservationAnalyzer {

    private final ObjectProvider<CallNumberParser> parserProvider;
    private final BookMatcher bookMatcher;

    /** 결과는 입력과 같은 순서다. 매칭 조회는 파싱된 전체를 모아 한 번만 호출한다. */
    public List<ObservationAnalysis> analyze(List<AnalysisInput> inputs) {
        CallNumberParser parser = parserProvider.getIfAvailable();
        if (parser == null) {
            throw new BusinessException(ErrorCode.SCAN_PARSER_UNAVAILABLE);
        }

        ObservationAnalysis[] results = new ObservationAnalysis[inputs.size()];
        List<Integer> matchIndexes = new ArrayList<>();      // 매칭 대상(파싱 성공)의 입력 위치
        List<MatchRequest> matchRequests = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            AnalysisInput input = inputs.get(i);
            Optional<ParsedCallNumber> parsed = parse(parser, input.detectedLabelRaw());
            if (parsed.isEmpty()) {
                results[i] = ObservationAnalysis.ocrFailed();
            } else {
                matchIndexes.add(i);
                matchRequests.add(new MatchRequest(parsed.get(), input.detectedShelfId()));
            }
        }

        if (!matchRequests.isEmpty()) {
            List<MatchResult> matches = bookMatcher.match(matchRequests);
            for (int k = 0; k < matchIndexes.size(); k++) {
                int i = matchIndexes.get(k);
                results[i] = judge(matchRequests.get(k).parsed(), matches.get(k), inputs.get(i).detectedShelfId());
            }
        }

        markDuplicates(results);
        return List.of(results);
    }

    private static Optional<ParsedCallNumber> parse(CallNumberParser parser, String rawLabel) {
        if (rawLabel == null || rawLabel.isBlank()) {
            return Optional.empty();
        }
        return parser.parse(rawLabel);
    }

    private static ObservationAnalysis judge(ParsedCallNumber parsed, MatchResult match, Long detectedShelfId) {
        return switch (match.outcome()) {
            case NO_MATCH -> ObservationAnalysis.unresolved(ObservationStatusReason.NO_MATCH, parsed,
                    match.candidates());
            case AMBIGUOUS -> ObservationAnalysis.unresolved(ObservationStatusReason.AMBIGUOUS_MATCH, parsed,
                    match.candidates());
            case MATCHED -> judgeIdentified(parsed, match, detectedShelfId);
        };
    }

    private static ObservationAnalysis judgeIdentified(ParsedCallNumber parsed, MatchResult match,
                                                       Long detectedShelfId) {
        BookMatchInfo book = match.matchedBook();
        if (book.lifecycleStatus() == LifecycleStatus.DISPOSED) {
            return ObservationAnalysis.identified(ObservationAnalysisStatus.REVIEW_REQUIRED,
                    ObservationStatusReason.DISPOSED_BOOK, parsed, match);
        }
        if (book.expectedShelfId() == null) {
            return ObservationAnalysis.identified(ObservationAnalysisStatus.REVIEW_REQUIRED,
                    ObservationStatusReason.NO_EXPECTED_SHELF, parsed, match);
        }
        if (Objects.equals(book.expectedShelfId(), detectedShelfId)) {
            return ObservationAnalysis.identified(ObservationAnalysisStatus.NORMAL, null, parsed, match);
        }
        return ObservationAnalysis.identified(ObservationAnalysisStatus.MISPLACED,
                ObservationStatusReason.WRONG_SHELF, parsed, match);
    }

    /** 같은 도서로 식별된 관측이 둘 이상이면 모두 중복 관측으로 바꾼다. 도서가 식별되지 않은 관측은 대상이 아니다. */
    private static void markDuplicates(ObservationAnalysis[] results) {
        Map<Long, List<Integer>> indexesByBook = new HashMap<>();
        for (int i = 0; i < results.length; i++) {
            Long bookId = results[i].bookId();
            if (bookId != null) {
                indexesByBook.computeIfAbsent(bookId, k -> new ArrayList<>()).add(i);
            }
        }
        for (List<Integer> indexes : indexesByBook.values()) {
            if (indexes.size() < 2) {
                continue;
            }
            for (int i : indexes) {
                // 제적 도서는 더 중요한 사유이므로 유지한다.
                if (results[i].reason() != ObservationStatusReason.DISPOSED_BOOK) {
                    results[i] = results[i].asDuplicate();
                }
            }
        }
    }
}
