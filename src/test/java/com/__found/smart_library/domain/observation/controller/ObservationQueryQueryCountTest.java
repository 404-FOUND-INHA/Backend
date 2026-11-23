package com.__found.smart_library.domain.observation.controller;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * 조회 API 의 쿼리 수(N+1 없음)·읽기 전용·규모별 응답 크기. 쿼리 수는 데이터 양과 무관하게 API 당 고정이어야 한다.
 * 시간은 단언하지 않고 [PERF] 로그로만 남긴다(로컬 DB 기준 참고값).
 *
 * <p>측정 범위: 이 테스트는 jwt() 목 인증으로 호출하므로 조회 API(컨트롤러·서비스)가 실행하는 SQL 수만 센다.
 * 실제 JWT 인증 필터가 사용자 조회로 쓰는 추가 SQL 은 포함하지 않으며, 인증을 포함한 HTTP 요청 전체의 SQL 수가 아니다.
 */
@Import(VisionTestParserConfig.class)
class ObservationQueryQueryCountTest extends ObservationQueryTestSupport {

    @Autowired EntityManagerFactory entityManagerFactory;

    @Test
    @DisplayName("쿼리 수는 관측·서가 수와 무관하게 요약 3·서가 목록 3·상세 3(중복 관측이면 4)으로 고정이다")
    void queryCountsAreFixedRegardlessOfSize() throws Exception {
        int[] small = counts(2, 3);
        int[] large = counts(6, 100);

        assertThat(small).containsExactly(3, 3, 3, 4);
        assertThat(large).as("데이터가 많아도 쿼리 수가 같아야 한다").containsExactly(3, 3, 3, 4);
    }

    /** [요약, 서가 목록, 일반 상세, 중복 관측 상세]의 SQL 실행 수. */
    private int[] counts(int shelfCount, int perShelf) throws Exception {
        Fixture fx = fixture(shelfCount);
        Long expectedShelf = fx.shelfIds().get(0);
        String dupKey = "DUP" + shelfCount;   // 같은 테스트 안의 다른 회차와 청구기호가 겹치지 않게 한다
        Long dupBook = book(dupKey, "c.1", expectedShelf, "NORMAL");
        List<Map<String, Object>> shelves = new ArrayList<>();
        for (int s = 0; s < shelfCount; s++) {
            List<Map<String, Object>> observations = new ArrayList<>();
            for (int i = 1; i <= perShelf; i++) {
                observations.add(obs(i, "해석불가"));      // OCR_FAILED
            }
            if (s == 0) {
                observations.set(0, obs(1, label(dupKey, "c.1")));   // 같은 도서를 서가 0·1 에서 관측 → 중복
            }
            if (s == 1) {
                observations.set(0, obs(1, label(dupKey, "c.1")));
            }
            shelves.add(shelf(fx.shelfCodes().get(s), observations.toArray(new Map[0])));
        }
        Map<String, Object> request = body(fx.sessionId(), shelves.toArray(new Map[0]));
        receive(request);
        assertThat(dupBook).isNotNull();

        Long plain = observationId(fx.sessionId(), fx.shelfIds().get(shelfCount - 1), 2);
        Long dup = observationId(fx.sessionId(), fx.shelfIds().get(0), 1);

        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        try {
            int analysis = statements(stats, analysisUrl(fx.sessionId()));
            int list = statements(stats, shelfUrl(fx.sessionId(), fx.shelfIds().get(0)));
            int detail = statements(stats, observationUrl(fx.sessionId(), plain));
            int duplicateDetail = statements(stats, observationUrl(fx.sessionId(), dup));
            return new int[] {analysis, list, detail, duplicateDetail};
        } finally {
            stats.setStatisticsEnabled(false);
        }
    }

    private int statements(Statistics stats, String url) throws Exception {
        stats.clear();
        MvcResult result = fetch(url);
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return (int) stats.getPrepareStatementCount();
    }

    @Test
    @DisplayName("조회 API 는 읽기 전용이다: 호출 전후로 관측·후보·서가·회차 데이터가 달라지지 않는다")
    void queriesDoNotChangeData() throws Exception {
        Fixture fx = fixture(3);
        Scenario sc = standardScenario(fx);
        receive(sc.body());
        Map<String, Object> before = snapshot(fx.sessionId());
        Long id = observationId(fx.sessionId(), fx.shelfIds().get(0), 3);

        assertThat(status(fetch(analysisUrl(fx.sessionId())))).isEqualTo(200);
        for (Long shelfId : fx.shelfIds()) {
            assertThat(status(fetch(shelfUrl(fx.sessionId(), shelfId)))).isEqualTo(200);
        }
        assertThat(status(fetch(observationUrl(fx.sessionId(), id)))).isEqualTo(200);

        assertThat(snapshot(fx.sessionId())).isEqualTo(before);
        assertThat(sessionStatus(fx.sessionId())).isEqualTo("REVIEW");
    }

    @Test
    @DisplayName("[규모] 서가당 50·200·1,000건(전부 매칭)에서 정렬·건수가 정확하고 쿼리 수가 같으며 응답 크기를 기록한다")
    void shelfList_scale() throws Exception {
        for (int size : new int[] {50, 200, 1000}) {
            Fixture fx = fixture(1);
            Long shelfId = fx.shelfIds().get(0);
            List<Object[]> bookRows = new ArrayList<>();
            List<Map<String, Object>> observations = new ArrayList<>();
            for (int i = size; i >= 1; i--) {      // 일부러 역순으로 보낸다
                bookRows.add(new Object[] {prefix + "-" + String.format("%05d", ++seq), cls("B" + size + "_" + i) + " " + sym("B" + size + "_" + i),
                        cls("B" + size + "_" + i), sym("B" + size + "_" + i), "c.1", shelfId});
                observations.add(obs(i, label("B" + size + "_" + i, "c.1")));
            }
            jdbc.batchUpdate("insert into book (registration_no, title, call_number_raw, classification_no, "
                    + "book_symbol, copy_number, expected_shelf_id, lifecycle_status, loan_status, created_at, "
                    + "updated_at) values (?, '측정용 도서', ?, ?, ?, ?, ?, 'NORMAL', 'AVAILABLE', now(), now())",
                    bookRows);
            receive(body(fx.sessionId(), shelf(fx.shelfCodes().get(0), observations.toArray(new Map[0]))));

            Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
            stats.setStatisticsEnabled(true);
            stats.clear();
            long t0 = System.nanoTime();
            MvcResult result = fetch(shelfUrl(fx.sessionId(), shelfId));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            long statements = stats.getPrepareStatementCount();
            stats.setStatisticsEnabled(false);

            JsonNode items = data(result).get("observations");
            System.out.printf("[PERF] shelfList observations=%d responseBytes=%d elapsed=%dms statements=%d%n", size,
                    result.getResponse().getContentAsByteArray().length, ms, statements);
            assertThat(items.size()).isEqualTo(size);
            for (int i = 0; i < size; i++) {
                assertThat(items.get(i).get("detectedOrder").asInt()).isEqualTo(i + 1);
                assertThat(items.get(i).get("analysisStatus").asString()).isEqualTo("NORMAL");
            }
            assertThat(statements).isEqualTo(3);
        }
    }
}
