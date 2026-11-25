package com.__found.smart_library.domain.observation.service;

import com.__found.smart_library.domain.book.repository.BookMatchInfo;
import com.__found.smart_library.domain.observation.entity.CandidateReason;
import java.math.BigDecimal;
import java.util.List;

/** 매칭 방식이 선택한 도서와 신뢰도 또는 검토 후보. */
public record MatchResult(Outcome outcome, BookMatchInfo matchedBook, BigDecimal confidence,
                          List<Candidate> candidates) {

    public enum Outcome {
        MATCHED,
        /** 모두 일치하는 도서가 2권 이상. */
        AMBIGUOUS,
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
