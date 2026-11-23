package com.__found.smart_library.domain.observation.service;

import com.__found.smart_library.domain.book.repository.BookMatchInfo;
import com.__found.smart_library.domain.observation.entity.CandidateReason;
import java.math.BigDecimal;
import java.util.List;

/**
 * 매칭 결과. 정확히 1권이 완전 일치할 때만 MATCHED(신뢰도 1.0)이고, 그 외에는 도서를 확정하지 않는다.
 * 후보는 사서가 확인할 참고 정보이며 후보가 있다는 이유로 자동 확정하지 않는다.
 */
public record MatchResult(Outcome outcome, BookMatchInfo matchedBook, BigDecimal confidence,
                          List<Candidate> candidates) {

    public enum Outcome {
        /** 분류기호·도서기호·복본기호가 모두 일치하는 도서가 정확히 1권. */
        MATCHED,
        /** 모두 일치하는 도서가 2권 이상. */
        AMBIGUOUS,
        /** 모두 일치하는 도서가 없음(후보는 있을 수 있음). */
        NO_MATCH
    }

    /** rank 는 1부터 연속이며 최대 BookMatcher.MAX_CANDIDATES 개. */
    public record Candidate(int rank, BookMatchInfo book, CandidateReason reason) {
    }

    static MatchResult matched(BookMatchInfo book) {
        return new MatchResult(Outcome.MATCHED, book, new BigDecimal("1.0000"), List.of());
    }

    static MatchResult ambiguous(List<Candidate> candidates) {
        return new MatchResult(Outcome.AMBIGUOUS, null, null, candidates);
    }

    static MatchResult noMatch(List<Candidate> candidates) {
        return new MatchResult(Outcome.NO_MATCH, null, null, candidates);
    }
}
