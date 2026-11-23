package com.__found.smart_library.domain.observation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.__found.smart_library.domain.observation.entity.CandidateReason;
import com.__found.smart_library.domain.observation.entity.ObservationAnalysisStatus;
import com.__found.smart_library.domain.observation.entity.ObservationStatusReason;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실제 BookMatcher·DB 와 연결한 분석 흐름 테스트(롤백). 운영에는 파서 구현이 없으므로 이 테스트에서만
 * "분류기호|도서기호|복본기호" 형식의 테스트 전용 파서를 등록하고, 데이터는 테스트마다 고유한 값으로 직접 만든다.
 */
@SpringBootTest
@Transactional
class ObservationAnalyzerDbTest {

    @TestConfiguration
    static class TestParserConfig {
        @Bean
        CallNumberParser testCallNumberParser() {
            return raw -> {
                String[] parts = raw.split("\\|", -1);
                if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank()) {
                    return Optional.empty();
                }
                return Optional.of(new ParsedCallNumber(parts[0], parts[1], parts[2]));
            };
        }
    }

    @Autowired ObservationAnalyzer analyzer;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;

    private final String id = UUID.randomUUID().toString().substring(0, 8);
    private int seq = 0;

    @Test
    @DisplayName("정상·오배치·제적·예상 서가 없음·중복·매칭 실패·복수 매칭·해석 불가가 한 번의 분석에서 각각 판정된다")
    void analyze_allScenarios_judgedAsExpected() {
        Long shelfA = shelf();
        Long shelfB = shelf();
        Long normal = book("N", "c.1", shelfA, "NORMAL");
        Long misplaced = book("M", "c.1", shelfA, "NORMAL");
        Long disposed = book("D", "c.1", shelfA, "DISPOSED");
        Long noShelf = book("E", "c.1", null, "NORMAL");
        Long dup = book("P", "c.1", shelfA, "NORMAL");
        Long copyOne = book("C", "c.1", shelfA, "NORMAL");
        Long amb1 = book("X", "c.1", shelfA, "NORMAL");
        Long amb2 = book("X", "c.1", shelfA, "NORMAL");   // 같은 청구기호·복본기호 중복 등록

        List<ObservationAnalysis> r = analyzer.analyze(List.of(
                input("N", "c.1", shelfA),       // 0 정상
                input("M", "c.1", shelfB),       // 1 오배치
                input("D", "c.1", shelfA),       // 2 제적
                input("E", "c.1", shelfA),       // 3 예상 서가 없음
                input("P", "c.1", shelfA),       // 4 중복 1
                input("P", "c.1", shelfB),       // 5 중복 2(다른 서가)
                input("C", "c.2", shelfA),       // 6 복본기호 불일치 → 매칭 실패
                input("Z", "c.1", shelfA),       // 7 아무것도 일치하지 않음
                input("X", "c.1", shelfA),       // 8 복수 매칭
                new AnalysisInput("깨진 라벨", shelfA),   // 9 해석 불가
                new AnalysisInput(null, shelfA)));        // 10 라벨 없음

        assertThat(r).hasSize(11);
        assertResult(r.get(0), ObservationAnalysisStatus.NORMAL, null, normal);
        assertResult(r.get(1), ObservationAnalysisStatus.MISPLACED, ObservationStatusReason.WRONG_SHELF, misplaced);
        assertResult(r.get(2), ObservationAnalysisStatus.REVIEW_REQUIRED, ObservationStatusReason.DISPOSED_BOOK,
                disposed);
        assertResult(r.get(3), ObservationAnalysisStatus.REVIEW_REQUIRED, ObservationStatusReason.NO_EXPECTED_SHELF,
                noShelf);
        assertResult(r.get(4), ObservationAnalysisStatus.REVIEW_REQUIRED,
                ObservationStatusReason.DUPLICATE_OBSERVATION, dup);
        assertResult(r.get(5), ObservationAnalysisStatus.REVIEW_REQUIRED,
                ObservationStatusReason.DUPLICATE_OBSERVATION, dup);

        assertResult(r.get(6), ObservationAnalysisStatus.REVIEW_REQUIRED, ObservationStatusReason.NO_MATCH, null);
        assertThat(r.get(6).candidates()).singleElement().satisfies(c -> {
            assertThat(c.book().bookId()).isEqualTo(copyOne);
            assertThat(c.reason()).isEqualTo(CandidateReason.COPY_MISMATCH);
        });
        assertResult(r.get(7), ObservationAnalysisStatus.REVIEW_REQUIRED, ObservationStatusReason.NO_MATCH, null);
        assertThat(r.get(7).candidates()).isEmpty();

        assertResult(r.get(8), ObservationAnalysisStatus.REVIEW_REQUIRED, ObservationStatusReason.AMBIGUOUS_MATCH,
                null);
        assertThat(r.get(8).candidates()).extracting(c -> c.book().bookId()).containsExactly(amb1, amb2);
        assertThat(r.get(8).candidates()).extracting(c -> c.reason()).containsOnly(CandidateReason.EXACT_MATCH);

        assertResult(r.get(9), ObservationAnalysisStatus.REVIEW_REQUIRED, ObservationStatusReason.OCR_FAILED, null);
        assertResult(r.get(10), ObservationAnalysisStatus.REVIEW_REQUIRED, ObservationStatusReason.OCR_FAILED, null);
    }

    @Test
    @DisplayName("관측이 많아도 분석 전체의 쿼리는 최대 3개이고 엔티티는 로드하지 않는다")
    void analyze_manyObservations_executesAtMostThreeQueriesWithoutLoadingEntities() {
        Long shelf = shelf();
        List<AnalysisInput> inputs = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            book("Q" + i, "c.1", shelf, "NORMAL");
            inputs.add(input("Q" + i, i % 2 == 0 ? "c.1" : "c.9", shelf));   // 짝수는 일치, 홀수는 매칭 실패
        }
        inputs.add(new AnalysisInput("깨진 라벨", shelf));
        Statistics stats = statistics();

        List<ObservationAnalysis> results = analyzer.analyze(inputs);

        assertThat(results).hasSize(51);
        assertThat(results.get(0).status()).isEqualTo(ObservationAnalysisStatus.NORMAL);
        assertThat(results.get(1).reason()).isEqualTo(ObservationStatusReason.NO_MATCH);
        assertThat(results.get(50).reason()).isEqualTo(ObservationStatusReason.OCR_FAILED);
        assertThat(stats.getQueryExecutionCount()).isLessThanOrEqualTo(3);
        assertThat(stats.getEntityLoadCount()).isZero();
        stats.setStatisticsEnabled(false);
    }

    private static void assertResult(ObservationAnalysis result, ObservationAnalysisStatus status,
                                     ObservationStatusReason reason, Long bookId) {
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.reason()).isEqualTo(reason);
        assertThat(result.bookId()).isEqualTo(bookId);
        if (bookId != null) {
            assertThat(result.confidence()).isEqualByComparingTo("1.0000");
        } else {
            assertThat(result.confidence()).isNull();
        }
    }

    private AnalysisInput input(String key, String copy, Long shelfId) {
        return new AnalysisInput(cls(key) + "|" + sym(key) + "|" + copy, shelfId);
    }

    private String cls(String key) {
        return "T" + id + "." + key;
    }

    private String sym(String key) {
        return "S" + id + "ㅈ" + key;
    }

    private Long book(String key, String copy, Long expectedShelfId, String lifecycleStatus) {
        return jdbc.queryForObject("insert into book (registration_no, title, call_number_raw, classification_no, "
                        + "book_symbol, copy_number, expected_shelf_id, lifecycle_status, loan_status, created_at, updated_at) "
                        + "values (?, '테스트 도서', ?, ?, ?, ?, ?, ?, 'AVAILABLE', now(), now()) returning book_id",
                Long.class, id + "-" + String.format("%03d", ++seq), cls(key) + " " + sym(key),
                cls(key), sym(key), copy, expectedShelfId, lifecycleStatus);
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
