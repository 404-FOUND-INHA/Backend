package com.__found.smart_library.domain.evaluation.metric;

import com.__found.smart_library.domain.book.repository.BookMetricSource;
import com.__found.smart_library.domain.book.repository.BookRepository;
import com.__found.smart_library.domain.book.repository.CopyCount;
import com.__found.smart_library.domain.condition.entity.BookConditionReport;
import com.__found.smart_library.domain.condition.repository.BookConditionReportRepository;
import com.__found.smart_library.domain.evaluation.metric.BackendMetricCalculator.ConditionInput;
import com.__found.smart_library.domain.evaluation.metric.BackendMetricCalculator.DemandInput;
import com.__found.smart_library.domain.loan.repository.LoanCount;
import com.__found.smart_library.domain.loan.repository.LoanRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 도서들의 Backend 평가 지표(수요성·물리적 상태·중복성)를 데이터베이스 기록으로 계산한다. 저장은 하지 않는다
 * (종합 점수 계산에서 JEV 결과와 결합한 뒤 저장한다). 여러 도서를 묶어서 조회하므로 회차 단위 계산에도 쓸 수 있다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BackendMetricService {

    /** IN 절이 너무 길어지지 않도록 나눠 조회하는 크기. */
    private static final int CHUNK = 1000;

    private final BackendMetricCalculator calculator;
    private final BackendMetricProperties properties;
    private final BookRepository bookRepository;
    private final LoanRepository loanRepository;
    private final BookConditionReportRepository conditionReportRepository;
    private final Clock clock;

    /**
     * 도서별 지표(수요성, 중복성, 물리적 상태 순). 존재하지 않는 도서는 결과에 없다.
     */
    public Map<Long, List<CriterionScore>> calculate(Collection<Long> bookIds) {
        LocalDate today = LocalDate.now(clock);
        List<Long> ids = bookIds.stream().distinct().toList();

        Map<Long, BookMetricSource> books = new LinkedHashMap<>();
        Map<Long, Long> recentLoans = new HashMap<>();
        Map<Long, List<ConditionInput>> conditions = new HashMap<>();
        for (List<Long> chunk : chunks(ids)) {
            bookRepository.findMetricSources(chunk).forEach(b -> books.put(b.bookId(), b));
            for (LoanCount c : loanRepository.countSince(chunk,
                    today.minusYears(properties.getDemand().getWindowYears()).atStartOfDay())) {
                recentLoans.put(c.bookId(), c.count());
            }
            for (BookConditionReport r : conditionReportRepository.findByBookIdIn(chunk)) {
                conditions.computeIfAbsent(r.getBookId(), k -> new ArrayList<>())
                        .add(new ConditionInput(r.getConditionType(), r.getSeverity()));
            }
        }
        Map<CallKey, Long> copies = copyCounts(books.values());

        Map<Long, List<CriterionScore>> result = new LinkedHashMap<>();
        for (BookMetricSource b : books.values()) {
            result.put(b.bookId(), List.of(
                    calculator.demand(demandInput(b, recentLoans.getOrDefault(b.bookId(), 0L), today), today),
                    calculator.duplication(copies.getOrDefault(CallKey.of(b), 1L).intValue()),
                    calculator.physicalCondition(conditions.getOrDefault(b.bookId(), List.of()))));
        }
        return result;
    }

    /**
     * 대출 이력(loan)이 없어도 도서의 마지막 대출일이 기간 안이면 최소 1건은 있었던 것으로 본다.
     * 이관된 도서는 상세 이력 없이 총 대출 횟수와 마지막 대출일만 있을 수 있기 때문이다.
     */
    private DemandInput demandInput(BookMetricSource b, long loanRows, LocalDate today) {
        LocalDate windowStart = today.minusYears(properties.getDemand().getWindowYears());
        boolean lastInWindow = b.lastLoanDate() != null && !b.lastLoanDate().isBefore(windowStart);
        long recent = Math.max(loanRows, lastInWindow ? 1 : 0);
        return new DemandInput((int) Math.min(recent, Integer.MAX_VALUE), b.lastLoanDate(), b.totalLoanCount());
    }

    /** 청구기호(분류기호 + 도서기호)별 복본 수. 제적 도서는 세지 않는다. */
    private Map<CallKey, Long> copyCounts(Collection<BookMetricSource> books) {
        Set<String> classificationNos = new HashSet<>();
        Set<String> bookSymbols = new HashSet<>();
        Set<CallKey> wanted = new HashSet<>();
        for (BookMetricSource b : books) {
            classificationNos.add(b.classificationNo());
            bookSymbols.add(b.bookSymbol());
            wanted.add(CallKey.of(b));
        }
        Map<CallKey, Long> counts = new HashMap<>();
        if (wanted.isEmpty()) {
            return counts;
        }
        for (List<String> cn : chunks(new ArrayList<>(classificationNos))) {
            for (List<String> bs : chunks(new ArrayList<>(bookSymbols))) {
                for (CopyCount c : bookRepository.countCopies(cn, bs)) {
                    CallKey key = new CallKey(c.classificationNo(), c.bookSymbol());
                    if (wanted.contains(key)) {
                        counts.merge(key, c.count(), Long::sum);
                    }
                }
            }
        }
        return counts;
    }

    private static <T> List<List<T>> chunks(List<T> items) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i += CHUNK) {
            out.add(items.subList(i, Math.min(i + CHUNK, items.size())));
        }
        return out;
    }

    private record CallKey(String classificationNo, String bookSymbol) {
        static CallKey of(BookMetricSource b) {
            return new CallKey(b.classificationNo(), b.bookSymbol());
        }
    }
}
