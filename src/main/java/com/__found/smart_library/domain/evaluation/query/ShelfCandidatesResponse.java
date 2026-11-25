package com.__found.smart_library.domain.evaluation.query;

import java.math.BigDecimal;
import java.util.List;

/**
 * 서가별 폐기 검토 후보(서가·3D 시각화용). 서가는 후보 수가 많은 순이고, 현재 서가가 지정되지 않은 도서는
 * shelf 가 null 인 마지막 묶음에 들어간다.
 */
public record ShelfCandidatesResponse(long totalCandidates, List<ShelfGroup> shelves) {

    /** 서가 하나의 후보. maxScore 는 이 서가 후보 중 가장 높은 종합 점수이고, books 는 점수가 높은 순이다. */
    public record ShelfGroup(ShelfInfo shelf, long candidateCount, BigDecimal maxScore, List<CandidateBook> books) {
    }

    public record CandidateBook(Long bookId, String title, String callNumber, BigDecimal finalScore) {
    }
}
