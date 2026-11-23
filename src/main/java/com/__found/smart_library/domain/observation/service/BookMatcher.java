package com.__found.smart_library.domain.observation.service;

import com.__found.smart_library.domain.book.repository.BookMatchInfo;
import com.__found.smart_library.domain.book.repository.BookRepository;
import com.__found.smart_library.domain.observation.entity.CandidateReason;
import com.__found.smart_library.domain.observation.service.MatchResult.Candidate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 파싱된 청구기호를 book 과 대조해 도서를 식별하고, 식별하지 못하면 사서가 확인할 후보(최대 3개)를 고른다.
 *
 * 자동 매칭: 분류기호·도서기호·복본기호(null 포함)가 모두 같은 도서가 정확히 1권일 때만. 문자열은 그대로 비교한다.
 * 후보 근거(강한 순): EXACT_MATCH(완전 일치 다수) > COPY_MISMATCH > SYMBOL_ONLY > CLASSIFICATION_ONLY.
 * 같은 근거 안에서는 관측 서가가 기대 서가인 도서를 먼저, 그다음 등록번호 순으로 정렬한다(정렬에만 사용).
 * 제적 도서도 대상에 포함한다. 조회는 요청 전체를 모아 단계별 최대 1회이며 관측 수만큼 반복하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class BookMatcher {

    public static final int MAX_CANDIDATES = 3;

    private final BookRepository bookRepository;

    /** 결과는 입력과 같은 순서다. */
    @Transactional(readOnly = true)
    public List<MatchResult> match(List<MatchRequest> requests) {
        if (requests.isEmpty()) {
            return List.of();
        }

        // 1단계: 분류기호·도서기호가 같은 도서 → 완전 일치 / 복본기호만 다른 후보
        Map<CallKey, List<BookMatchInfo>> byPair = new HashMap<>();
        for (BookMatchInfo b : bookRepository.findMatchInfosByClassificationNoAndBookSymbol(
                distinct(requests, r -> r.parsed().classificationNo()),
                distinct(requests, r -> r.parsed().bookSymbol()))) {
            byPair.computeIfAbsent(new CallKey(b.classificationNo(), b.bookSymbol()), k -> new ArrayList<>()).add(b);
        }

        MatchResult[] results = new MatchResult[requests.size()];
        List<Integer> pendingIndexes = new ArrayList<>();   // 완전 일치가 없어 후보 보강 대상이 될 수 있는 요청
        for (int i = 0; i < requests.size(); i++) {
            MatchRequest request = requests.get(i);
            ParsedCallNumber parsed = request.parsed();
            List<BookMatchInfo> sameCallNumber = byPair.getOrDefault(
                    new CallKey(parsed.classificationNo(), parsed.bookSymbol()), List.of());
            List<BookMatchInfo> exact = sameCallNumber.stream()
                    .filter(b -> Objects.equals(b.copyNumber(), parsed.copyNumber())).toList();

            if (exact.size() == 1) {
                results[i] = MatchResult.matched(exact.get(0));
            } else if (exact.size() >= 2) {
                results[i] = MatchResult.ambiguous(
                        rank(List.of(), exact, CandidateReason.EXACT_MATCH, request.detectedShelfId()));
            } else {
                List<Candidate> candidates = rank(List.of(), sameCallNumber, CandidateReason.COPY_MISMATCH,
                        request.detectedShelfId());
                results[i] = MatchResult.noMatch(candidates);
                pendingIndexes.add(i);
            }
        }

        // 2단계: 후보가 3개 미만인 요청만 도서기호가 같은 도서를 보충
        List<Integer> needSymbol = underfilled(pendingIndexes, results);
        if (!needSymbol.isEmpty()) {
            Map<String, List<BookMatchInfo>> bySymbol = new HashMap<>();
            for (BookMatchInfo b : bookRepository.findMatchInfosByBookSymbol(
                    distinctAt(requests, needSymbol, r -> r.parsed().bookSymbol()))) {
                bySymbol.computeIfAbsent(b.bookSymbol(), k -> new ArrayList<>()).add(b);
            }
            for (int i : needSymbol) {
                MatchRequest request = requests.get(i);
                List<BookMatchInfo> sameSymbol = bySymbol.getOrDefault(request.parsed().bookSymbol(), List.of()).stream()
                        .filter(b -> !Objects.equals(b.classificationNo(), request.parsed().classificationNo())).toList();
                results[i] = MatchResult.noMatch(rank(results[i].candidates(), sameSymbol,
                        CandidateReason.SYMBOL_ONLY, request.detectedShelfId()));
            }
        }

        // 3단계: 그래도 3개 미만인 요청만 분류기호가 같은 도서를 보충
        List<Integer> needClassification = underfilled(pendingIndexes, results);
        if (!needClassification.isEmpty()) {
            Map<String, List<BookMatchInfo>> byClassification = new HashMap<>();
            for (BookMatchInfo b : bookRepository.findMatchInfosByClassificationNo(
                    distinctAt(requests, needClassification, r -> r.parsed().classificationNo()))) {
                byClassification.computeIfAbsent(b.classificationNo(), k -> new ArrayList<>()).add(b);
            }
            for (int i : needClassification) {
                MatchRequest request = requests.get(i);
                List<BookMatchInfo> sameClassification = byClassification
                        .getOrDefault(request.parsed().classificationNo(), List.of()).stream()
                        .filter(b -> !Objects.equals(b.bookSymbol(), request.parsed().bookSymbol())).toList();
                results[i] = MatchResult.noMatch(rank(results[i].candidates(), sameClassification,
                        CandidateReason.CLASSIFICATION_ONLY, request.detectedShelfId()));
            }
        }
        return List.of(results);
    }

    /** 이미 고른 후보 뒤에 이번 근거의 도서를 정렬해 붙이고, 최대 3개에서 자른다(순위는 1부터 연속). */
    private List<Candidate> rank(List<Candidate> existing, List<BookMatchInfo> books, CandidateReason reason,
                                 Long detectedShelfId) {
        List<Candidate> ranked = new ArrayList<>(existing);
        books.stream()
                .sorted(Comparator
                        .comparing((BookMatchInfo b) -> !isExpectedHere(b, detectedShelfId))   // 기대 서가 일치(false)가 먼저
                        .thenComparing(BookMatchInfo::registrationNo))
                .limit(Math.max(0, MAX_CANDIDATES - ranked.size()))
                .forEach(b -> ranked.add(new Candidate(ranked.size() + 1, b, reason)));
        return ranked;
    }

    private boolean isExpectedHere(BookMatchInfo book, Long detectedShelfId) {
        return detectedShelfId != null && detectedShelfId.equals(book.expectedShelfId());
    }

    /** 아직 후보가 3개 미만인 NO_MATCH 요청의 인덱스. */
    private List<Integer> underfilled(List<Integer> indexes, MatchResult[] results) {
        return indexes.stream().filter(i -> results[i].candidates().size() < MAX_CANDIDATES).toList();
    }

    private Set<String> distinct(List<MatchRequest> requests, java.util.function.Function<MatchRequest, String> key) {
        Set<String> keys = new HashSet<>();
        requests.forEach(r -> keys.add(key.apply(r)));
        return keys;
    }

    private Collection<String> distinctAt(List<MatchRequest> requests, List<Integer> indexes,
                                          java.util.function.Function<MatchRequest, String> key) {
        Set<String> keys = new HashSet<>();
        indexes.forEach(i -> keys.add(key.apply(requests.get(i))));
        return keys;
    }

    private record CallKey(String classificationNo, String bookSymbol) {
    }
}
