package com.__found.smart_library.domain.evaluation.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.__found.smart_library.domain.shelf.entity.Shelf;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 조회 API 의 쿼리 수(N+1 없음). 도서·서가 수와 상관없이 API 당 SQL 수가 같아야 한다.
 * jwt() 목 인증으로 호출하므로 조회 API(컨트롤러·서비스)가 실행하는 SQL 만 센다.
 */
class EvaluationQueryQueryCountTest extends EvaluationQueryTestSupport {

    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired EntityManager em;

    @BeforeEach
    void setUp() {
        createUser();
    }

    @Test
    @DisplayName("쿼리 수는 도서·서가 수와 무관하게 목록 4·후보 목록 4·서가별 2(서가 지정 3)·상세 5 로 고정이다")
    void queryCountsAreFixedRegardlessOfSize() throws Exception {
        int[] small = counts(2, 2);
        int[] large = counts(12, 8);

        assertThat(small).containsExactly(4, 4, 2, 3, 5);
        assertThat(large).as("데이터가 많아도 쿼리 수가 같아야 한다").containsExactly(4, 4, 2, 3, 5);
    }

    /** [목록, 후보 목록, 서가별, 서가 지정 서가별, 상세]의 SQL 실행 수. */
    private int[] counts(int shelfCount, int perShelf) throws Exception {
        Long sid = session();
        List<Shelf> shelves = new ArrayList<>();
        for (int s = 0; s < shelfCount; s++) {
            shelves.add(shelf("Q" + shelfCount + "-" + s, 1 + s % 3, "1.00", "2.00", "3.00"));
        }
        Long detailBook = null;
        for (Shelf shelf : shelves) {
            for (int i = 0; i < perShelf; i++) {
                Long bookId = book("B" + shelfCount + "-" + shelf.getId() + "-" + i, shelf);
                Long evaluationId = backend(sid, bookId, String.valueOf(50 + i), "DISPOSAL_REVIEW", "근거");
                if (detailBook == null) {
                    detailBook = bookId;
                    backendAxis(evaluationId, "DEMAND", "1.00", "60.00");
                    backendAxis(evaluationId, "DUPLICATION", "2.00", "50.00");
                    Long jev = jevEvaluation(sid, bookId);
                    jevAxis(jev, "RARITY", "40.00", 2);
                    jevAxis(jev, "CONTENT_VALIDITY", "55.00", 3);
                }
            }
        }
        em.flush();

        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        try {
            String base = "/api/scan-sessions/" + sid;
            return new int[] {
                    statements(stats, base + "/evaluations?size=50"),
                    statements(stats, base + "/disposal-candidates?size=50"),
                    statements(stats, base + "/disposal-candidates/by-shelf"),
                    statements(stats, base + "/disposal-candidates/by-shelf?shelfId=" + shelves.get(0).getId()),
                    statements(stats, base + "/books/" + detailBook + "/evaluation")};
        } finally {
            stats.setStatisticsEnabled(false);
        }
    }

    private int statements(Statistics stats, String url) throws Exception {
        em.clear();   // 앞선 호출·준비 과정에서 읽은 엔티티가 쿼리를 가리지 않게 한다
        stats.clear();
        fetch(url).andExpect(status().isOk());
        return (int) stats.getPrepareStatementCount();
    }
}
