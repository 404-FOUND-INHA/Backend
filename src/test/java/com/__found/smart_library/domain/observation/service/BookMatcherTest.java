package com.__found.smart_library.domain.observation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.__found.smart_library.domain.book.entity.LifecycleStatus;
import com.__found.smart_library.domain.observation.entity.CandidateReason;
import com.__found.smart_library.domain.observation.service.MatchResult.Candidate;
import com.__found.smart_library.domain.observation.service.MatchResult.Outcome;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 도서 매칭 테스트(롤백). 시드나 다른 데이터와 섞이지 않도록 테스트마다 고유한 분류기호·도서기호 접두어를 쓰고,
 * 파싱 결과는 사람이 정확히 만든 값을 직접 넣는다(파서는 사용하지 않는다).
 */
@SpringBootTest
@Transactional
class BookMatcherTest {

    @Autowired BookMatcher matcher;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;

    private final String id = UUID.randomUUID().toString().substring(0, 8);
    private final String cls = "T" + id + ".1";       // 이 테스트 전용 분류기호
    private final String sym = "S" + id + "ㅈ";        // 이 테스트 전용 도서기호
    private int seq = 0;

    // ---- 자동 매칭 ----

    @Test
    @DisplayName("분류기호·도서기호·복본기호가 모두 일치하는 도서가 1권이면 자동 매칭되고 신뢰도는 1.0이다")
    void match_uniqueExactMatch_returnsMatchedWithFullConfidence() {
        Long bookId = book(cls, sym, "c.1", null, "NORMAL");
        book(cls, sym, "c.2", null, "NORMAL");   // 복본만 다른 도서는 영향 없음

        MatchResult result = matchOne(cls, sym, "c.1", null);

        assertThat(result.outcome()).isEqualTo(Outcome.MATCHED);
        assertThat(result.matchedBook().bookId()).isEqualTo(bookId);
        assertThat(result.confidence()).isEqualByComparingTo(new BigDecimal("1.0000"));
        assertThat(result.candidates()).isEmpty();
    }

    @Test
    @DisplayName("복본기호가 양쪽 모두 null 이면 정확히 일치한 것으로 보고 자동 매칭한다")
    void match_copyNumberNullOnBothSides_returnsMatched() {
        Long bookId = book(cls, sym, null, null, "NORMAL");

        MatchResult result = matchOne(cls, sym, null, null);

        assertThat(result.outcome()).isEqualTo(Outcome.MATCHED);
        assertThat(result.matchedBook().bookId()).isEqualTo(bookId);
    }

    @Test
    @DisplayName("라벨에 복본기호가 없고 DB 에는 c.1 이 있으면 c.1 로 간주하지 않고 매칭하지 않는다")
    void match_parsedCopyNumberNullButDbHasCopy_doesNotMatch() {
        Long bookId = book(cls, sym, "c.1", null, "NORMAL");

        MatchResult result = matchOne(cls, sym, null, null);

        assertThat(result.outcome()).isEqualTo(Outcome.NO_MATCH);
        assertThat(result.matchedBook()).isNull();
        assertThat(result.confidence()).isNull();
        assertThat(result.candidates()).singleElement().satisfies(c -> {
            assertThat(c.book().bookId()).isEqualTo(bookId);
            assertThat(c.reason()).isEqualTo(CandidateReason.COPY_MISMATCH);
        });
    }

    @Test
    @DisplayName("라벨에는 복본기호가 있고 DB 의 복본기호가 null 이면 매칭하지 않는다")
    void match_parsedCopyNumberPresentButDbNull_doesNotMatch() {
        book(cls, sym, null, null, "NORMAL");

        MatchResult result = matchOne(cls, sym, "c.1", null);

        assertThat(result.outcome()).isEqualTo(Outcome.NO_MATCH);
        assertThat(result.candidates()).extracting(Candidate::reason).containsExactly(CandidateReason.COPY_MISMATCH);
    }

    @Test
    @DisplayName("제적 도서도 매칭 대상에 포함되어 정확히 일치하면 매칭 결과가 보존된다")
    void match_disposedBook_isStillMatched() {
        Long bookId = book(cls, sym, "c.1", null, "DISPOSED");

        MatchResult result = matchOne(cls, sym, "c.1", null);

        assertThat(result.outcome()).isEqualTo(Outcome.MATCHED);
        assertThat(result.matchedBook().bookId()).isEqualTo(bookId);
        assertThat(result.matchedBook().lifecycleStatus()).isEqualTo(LifecycleStatus.DISPOSED);
    }

    @Test
    @DisplayName("앞뒤 공백이나 표기 차이는 같은 값으로 취급하지 않는다 (정규화는 파서의 책임)")
    void match_whitespaceDifference_doesNotMatch() {
        book(cls, sym, "c.1", null, "NORMAL");

        MatchResult result = matchOne(cls + " ", sym, "c.1", null);

        assertThat(result.outcome()).isEqualTo(Outcome.NO_MATCH);
    }

    // ---- 복수 후보 ----

    @Test
    @DisplayName("완전 일치 도서가 여러 권이면 AMBIGUOUS 이고 그 도서들이 EXACT_MATCH 후보가 된다")
    void match_multipleExactMatches_returnsAmbiguousWithExactCandidates() {
        Long first = book(cls, sym, "c.1", null, "NORMAL");
        Long second = book(cls, sym, "c.1", null, "NORMAL");   // 같은 청구기호·복본기호의 중복 등록
        book(cls, sym, "c.2", null, "NORMAL");                  // 복본만 다른 도서는 섞이지 않는다

        MatchResult result = matchOne(cls, sym, "c.1", null);

        assertThat(result.outcome()).isEqualTo(Outcome.AMBIGUOUS);
        assertThat(result.matchedBook()).isNull();
        assertThat(result.confidence()).isNull();
        assertThat(result.candidates()).extracting(c -> c.book().bookId()).containsExactly(first, second);
        assertThat(result.candidates()).extracting(Candidate::rank).containsExactly(1, 2);
        assertThat(result.candidates()).extracting(Candidate::reason).containsOnly(CandidateReason.EXACT_MATCH);
    }

    // ---- 매칭 실패와 후보 탐색 ----

    @Test
    @DisplayName("복본기호만 다른 도서는 COPY_MISMATCH 후보이고, 후보가 1권뿐이어도 자동 확정하지 않는다")
    void match_onlyCopyMismatch_returnsNoMatchWithCandidates() {
        Long b1 = book(cls, sym, "c.1", null, "NORMAL");
        Long b2 = book(cls, sym, "c.2", null, "NORMAL");

        MatchResult many = matchOne(cls, sym, "c.3", null);
        assertThat(many.outcome()).isEqualTo(Outcome.NO_MATCH);
        assertThat(many.candidates()).extracting(c -> c.book().bookId()).containsExactly(b1, b2);

        String otherCls = "O" + id + ".1";   // 위 도서들과 분류기호가 겹치지 않게 분리
        String otherSym = "O" + id + "ㅊ";
        Long only = book(otherCls, otherSym, "c.1", null, "NORMAL");
        MatchResult one = matchOne(otherCls, otherSym, "c.9", null);
        assertThat(one.outcome()).isEqualTo(Outcome.NO_MATCH);
        assertThat(one.matchedBook()).isNull();
        assertThat(one.candidates()).extracting(c -> c.book().bookId()).containsExactly(only);
    }

    @Test
    @DisplayName("도서기호만 일치하면 SYMBOL_ONLY, 분류기호만 일치하면 CLASSIFICATION_ONLY 후보가 된다")
    void match_partialComponentMatch_returnsCandidatesWithReasons() {
        Long symbolOnly = book("X" + id + ".9", sym, "c.1", null, "NORMAL");
        Long classificationOnly = book(cls, "Y" + id + "ㅋ", "c.1", null, "NORMAL");

        MatchResult result = matchOne(cls, sym, "c.1", null);

        assertThat(result.outcome()).isEqualTo(Outcome.NO_MATCH);
        assertThat(result.candidates()).extracting(c -> c.book().bookId())
                .containsExactly(symbolOnly, classificationOnly);
        assertThat(result.candidates()).extracting(Candidate::reason)
                .containsExactly(CandidateReason.SYMBOL_ONLY, CandidateReason.CLASSIFICATION_ONLY);
    }

    @Test
    @DisplayName("복본기호만 같은 도서는 후보가 되지 않고, 일치하는 구성요소가 없으면 빈 후보로 NO_MATCH 이다")
    void match_noComponentMatch_returnsNoMatchWithEmptyCandidates() {
        book("Z" + id + ".5", "Q" + id + "ㅅ", "c.1", null, "NORMAL");   // 복본기호(c.1)만 같다

        MatchResult result = matchOne(cls, sym, "c.1", null);

        assertThat(result.outcome()).isEqualTo(Outcome.NO_MATCH);
        assertThat(result.candidates()).isEmpty();
    }

    @Test
    @DisplayName("근거가 섞이면 강한 근거 순으로 최대 3개만 순위 1~3으로 제공한다")
    void match_manyCandidates_orderedByReasonAndCappedAtThree() {
        Long copy1 = book(cls, sym, "c.1", null, "NORMAL");
        Long copy2 = book(cls, sym, "c.2", null, "NORMAL");
        Long symbol1 = book("X" + id + ".9", sym, "c.1", null, "NORMAL");
        book("X" + id + ".8", sym, "c.1", null, "NORMAL");
        book(cls, "Y" + id + "ㅋ", "c.1", null, "NORMAL");

        MatchResult result = matchOne(cls, sym, "c.7", null);

        assertThat(result.candidates()).hasSize(BookMatcher.MAX_CANDIDATES);
        assertThat(result.candidates()).extracting(Candidate::rank).containsExactly(1, 2, 3);
        assertThat(result.candidates()).extracting(c -> c.book().bookId()).containsExactly(copy1, copy2, symbol1);
        assertThat(result.candidates()).extracting(Candidate::reason).containsExactly(
                CandidateReason.COPY_MISMATCH, CandidateReason.COPY_MISMATCH, CandidateReason.SYMBOL_ONLY);
    }

    @Test
    @DisplayName("같은 근거 안에서는 관측 서가가 기대 서가인 도서를 먼저, 그다음 등록번호 순으로 정렬한다")
    void match_sameReason_sortedByExpectedShelfThenRegistrationNo() {
        Long shelfA = shelf();
        Long shelfB = shelf();
        Long aOnA = book(cls, sym, "c.1", shelfA, "NORMAL");   // 등록번호가 가장 앞선다
        Long bOnB = book(cls, sym, "c.2", shelfB, "NORMAL");
        Long cOnB = book(cls, sym, "c.3", shelfB, "NORMAL");

        MatchResult result = matchOne(cls, sym, "c.9", shelfB);

        assertThat(result.candidates()).extracting(c -> c.book().bookId()).containsExactly(bOnB, cOnB, aOnA);
    }

    @Test
    @DisplayName("관측 서가와 기대 서가가 일치해도 정확 일치 조건이 아니면 자동 매칭하지 않는다")
    void match_expectedShelfMatchesButCopyDiffers_doesNotAutoMatch() {
        Long shelf = shelf();
        book(cls, sym, "c.1", shelf, "NORMAL");

        MatchResult result = matchOne(cls, sym, "c.2", shelf);

        assertThat(result.outcome()).isEqualTo(Outcome.NO_MATCH);
        assertThat(result.matchedBook()).isNull();
    }

    @Test
    @DisplayName("완전 일치가 있으면 하위 근거(도서기호·분류기호 일치) 후보를 섞지 않는다")
    void match_exactMatchExists_doesNotMixLowerReasonCandidates() {
        book(cls, sym, "c.1", null, "NORMAL");
        book(cls, sym, "c.1", null, "NORMAL");
        book("X" + id + ".9", sym, "c.1", null, "NORMAL");
        book(cls, "Y" + id + "ㅋ", "c.1", null, "NORMAL");

        MatchResult result = matchOne(cls, sym, "c.1", null);

        assertThat(result.candidates()).extracting(Candidate::reason).containsOnly(CandidateReason.EXACT_MATCH);
        assertThat(result.candidates()).hasSize(2);
    }

    // ---- 일괄 처리 ----

    @Test
    @DisplayName("여러 건을 한 번에 요청하면 결과가 입력 순서대로 대응하고 같은 입력은 같은 결과를 받는다")
    void match_batch_resultsAlignedWithRequestOrder() {
        Long matched = book(cls, sym, "c.1", null, "NORMAL");
        book("B" + id + ".2", "B" + id + "ㄱ", "c.1", null, "NORMAL");
        book("B" + id + ".2", "B" + id + "ㄱ", "c.1", null, "NORMAL");

        List<MatchResult> results = matcher.match(List.of(
                request("B" + id + ".2", "B" + id + "ㄱ", "c.1", null),   // 모호
                request(cls, sym, "c.1", null),                             // 일치
                request("N" + id + ".0", "N" + id + "ㄴ", "c.1", null),   // 일치 구성요소 없음
                request(cls, sym, "c.1", null)));                           // 위와 같은 입력

        assertThat(results).extracting(MatchResult::outcome)
                .containsExactly(Outcome.AMBIGUOUS, Outcome.MATCHED, Outcome.NO_MATCH, Outcome.MATCHED);
        assertThat(results.get(1).matchedBook().bookId()).isEqualTo(matched);
        assertThat(results.get(3).matchedBook().bookId()).isEqualTo(matched);
    }

    @Test
    @DisplayName("빈 입력은 쿼리 없이 빈 결과를 반환한다")
    void match_emptyRequests_returnsEmptyWithoutQuery() {
        Statistics stats = statistics();

        assertThat(matcher.match(List.of())).isEmpty();

        assertThat(stats.getQueryExecutionCount()).isZero();
        stats.setStatisticsEnabled(false);
    }

    @Test
    @DisplayName("관측이 아무리 많아도 쿼리는 최대 3개이고 엔티티는 로드하지 않는다")
    void match_manyRequests_executesAtMostThreeQueriesWithoutLoadingEntities() {
        List<MatchRequest> requests = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            String c = "M" + id + "." + i;
            String s = "M" + id + "ㅁ" + i;
            book(c, s, "c.1", null, "NORMAL");
            requests.add(request(c, s, i % 2 == 0 ? "c.1" : "c.9", null));   // 짝수는 일치, 홀수는 복본 불일치
        }
        requests.add(request("U" + id + ".0", "U" + id + "ㅇ", "c.1", null)); // 아무것도 일치하지 않음
        em.flush();
        Statistics stats = statistics();

        List<MatchResult> results = matcher.match(requests);

        assertThat(results).hasSize(51);
        assertThat(results.stream().filter(r -> r.outcome() == Outcome.MATCHED)).hasSize(25);
        assertThat(stats.getQueryExecutionCount()).isLessThanOrEqualTo(3);
        assertThat(stats.getEntityLoadCount()).isZero();
        stats.setStatisticsEnabled(false);
    }

    @Test
    @DisplayName("같은 분류기호 도서가 3,000권이어도 후보는 3개이고 쿼리 3개로 처리된다 (대량 데이터 확인)")
    void match_thousandsOfSameClassification_returnsThreeCandidatesWithinThreeQueries() {
        String bigCls = "BIG" + id + ".1";
        jdbc.update("insert into book (registration_no, title, call_number_raw, classification_no, book_symbol, "
                + "lifecycle_status, loan_status, created_at, updated_at) "
                + "select ?||'-'||lpad(g::text, 5, '0'), '대량 도서 ' || g, ?||' x', ?, 'BIGSYM' || g, "
                + "'NORMAL', 'AVAILABLE', now(), now() from generate_series(1, 3000) g",
                "BIG" + id, bigCls, bigCls);
        em.flush();
        Statistics stats = statistics();
        long start = System.nanoTime();

        MatchResult result = matchOne(bigCls, "NOSUCH" + id, "c.1", null);

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.println("[대량 매칭 측정] 분류기호 일치 3,000권 조회 소요 " + elapsedMs + "ms, 쿼리 "
                + stats.getQueryExecutionCount() + "개");
        assertThat(result.outcome()).isEqualTo(Outcome.NO_MATCH);
        assertThat(result.candidates()).hasSize(3);
        assertThat(result.candidates()).extracting(Candidate::reason).containsOnly(CandidateReason.CLASSIFICATION_ONLY);
        assertThat(result.candidates()).extracting(c -> c.book().registrationNo()).isSorted();
        assertThat(stats.getQueryExecutionCount()).isEqualTo(3);
        assertThat(stats.getEntityLoadCount()).isZero();
        stats.setStatisticsEnabled(false);
    }

    // ---- helpers ----

    private MatchResult matchOne(String classificationNo, String bookSymbol, String copyNumber, Long shelfId) {
        return matcher.match(List.of(request(classificationNo, bookSymbol, copyNumber, shelfId))).get(0);
    }

    private MatchRequest request(String classificationNo, String bookSymbol, String copyNumber, Long shelfId) {
        return new MatchRequest(new ParsedCallNumber(classificationNo, bookSymbol, copyNumber), shelfId);
    }

    private Long book(String classificationNo, String bookSymbol, String copyNumber, Long expectedShelfId,
                      String lifecycleStatus) {
        // 등록번호는 접두어+순번이라 생성 순서가 곧 등록번호 순서다
        return jdbc.queryForObject("insert into book (registration_no, title, call_number_raw, classification_no, "
                        + "book_symbol, copy_number, expected_shelf_id, lifecycle_status, loan_status, created_at, updated_at) "
                        + "values (?, '테스트 도서', ?, ?, ?, ?, ?, ?, 'AVAILABLE', now(), now()) returning book_id",
                Long.class, id + "-" + String.format("%03d", ++seq), classificationNo + " " + bookSymbol,
                classificationNo, bookSymbol, copyNumber, expectedShelfId, lifecycleStatus);
    }

    private Long shelf() {
        return jdbc.queryForObject("insert into shelf (shelf_code, is_active, created_at, updated_at) "
                + "values (?, true, now(), now()) returning shelf_id", Long.class, "T-" + id + "-" + (++seq));
    }

    private Statistics statistics() {
        Statistics stats = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        return stats;
    }
}
