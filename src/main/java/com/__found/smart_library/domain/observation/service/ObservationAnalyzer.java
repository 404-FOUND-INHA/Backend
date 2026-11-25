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

/** OCR 원문 또는 기존 라벨로 도서를 식별한 뒤 서가 위치와 회차 내 중복을 판정한다. */
@Component
@RequiredArgsConstructor
public class ObservationAnalyzer {

    private final ObjectProvider<CallNumberParser> parserProvider;
    private final BookMatcher bookMatcher;

    /** 결과는 입력과 같은 순서다. 기존 라벨 요청은 일괄 매칭한다. */
    public List<ObservationAnalysis> analyze(List<AnalysisInput> inputs) {
        CallNumberParser parser = parserProvider.getIfAvailable();
        if (parser == null && (inputs.isEmpty() || inputs.stream().anyMatch(input -> input.ocrText() == null))) {
            throw new BusinessException(ErrorCode.SCAN_PARSER_UNAVAILABLE);
        }

        ObservationAnalysis[] results = new ObservationAnalysis[inputs.size()];
        List<Integer> matchIndexes = new ArrayList<>();      // 매칭 대상(파싱 성공)의 입력 위치
        List<MatchRequest> matchRequests = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            AnalysisInput input = inputs.get(i);
            if (input.ocrText() != null) {
                results[i] = input.ocrText().isBlank() ? ObservationAnalysis.ocrFailed()
                        : judge(null, bookMatcher.matchText(input.ocrText(), input.partialCopyMark()), input.detectedShelfId());
                continue;
            }
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
