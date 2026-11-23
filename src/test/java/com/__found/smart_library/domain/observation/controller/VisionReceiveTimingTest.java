package com.__found.smart_library.domain.observation.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.__found.smart_library.domain.observation.service.AnalysisInput;
import com.__found.smart_library.domain.observation.service.BookMatcher;
import com.__found.smart_library.domain.observation.service.MatchRequest;
import com.__found.smart_library.domain.observation.service.ObservationAnalyzer;
import com.__found.smart_library.domain.observation.service.ParsedCallNumber;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * 수신 처리 시간 측정. 데모 규모는 항상 실행해 정확성과 쿼리 수를 확인하고, 1,000건·5,000건은 확장성 참고용이라
 * 환경변수 VISION_PERF=true 일 때만 실행한다(시간은 단언하지 않고 [PERF] 로그로만 남긴다).
 * 로컬 DB 기준 수치이며 EC2 와 다를 수 있고, 테스트 파서는 단순해 실제 파서 시간은 포함하지 않는다.
 */
@Import(VisionTestParserConfig.class)
class VisionReceiveTimingTest extends VisionReceiveTestSupport {

    private enum Mode {
        /** 모든 관측이 유일 매칭(후보 없음). */
        BEST,
        /** 모든 관측이 매칭 실패이며 후보 3건씩 저장(후보 행 수 최대). */
        WORST
    }

    @Autowired ObservationAnalyzer analyzer;
    @Autowired BookMatcher matcher;
    @Autowired EntityManagerFactory entityManagerFactory;

    @Test
    @DisplayName("데모 규모(서가 5개 x 20권)의 수신·저장·재전송이 정확하고 쿼리 수가 고정이다")
    void demoScale_best() throws Exception {
        measure(5, 20, Mode.BEST);
    }

    @Test
    @DisplayName("데모 규모에서 전부 매칭 실패(후보 3건씩)여도 정확하게 저장된다")
    void demoScale_worst() throws Exception {
        measure(5, 20, Mode.WORST);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "VISION_PERF", matches = "true")
    @DisplayName("[참고] 1,000건 전부 유일 매칭")
    void perf1000_best() throws Exception {
        measure(10, 100, Mode.BEST);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "VISION_PERF", matches = "true")
    @DisplayName("[참고] 1,000건 전부 매칭 실패(후보 3,000행)")
    void perf1000_worst() throws Exception {
        measure(10, 100, Mode.WORST);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "VISION_PERF", matches = "true")
    @DisplayName("[참고] 5,000건 전부 유일 매칭")
    void perf5000_best() throws Exception {
        measure(50, 100, Mode.BEST);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "VISION_PERF", matches = "true")
    @DisplayName("[참고] 5,000건 전부 매칭 실패(후보 15,000행)")
    void perf5000_worst() throws Exception {
        measure(50, 100, Mode.WORST);
    }

    private void measure(int shelfCount, int perShelf, Mode mode) throws Exception {
        Fixture fx = fixture(shelfCount);
        int total = shelfCount * perShelf;
        List<Map<String, Object>> shelves = new ArrayList<>();
        List<Object[]> bookRows = new ArrayList<>();
        List<AnalysisInput> inputs = new ArrayList<>();
        List<MatchRequest> matchRequests = new ArrayList<>();

        if (mode == Mode.WORST) {
            // 분류기호 50개, 분류기호마다 책 4권(도서기호는 서로 다름)
            for (int k = 0; k < 50; k++) {
                for (int j = 0; j < 4; j++) {
                    bookRows.add(bookRow(cls("W" + k), sym("W" + k + "b" + j), "c.1", null));
                }
            }
        }
        int n = 0;
        for (int s = 0; s < shelfCount; s++) {
            List<Map<String, Object>> observations = new ArrayList<>();
            for (int i = 1; i <= perShelf; i++, n++) {
                String cls;
                String sym;
                if (mode == Mode.BEST) {
                    cls = cls("L" + n);
                    sym = sym("L" + n);
                    bookRows.add(bookRow(cls, sym, "c.1", fx.shelfIds().get(s)));
                } else {
                    cls = cls("W" + (n % 50));
                    sym = sym("U" + n);   // 이 도서기호의 책은 없다
                }
                String label = cls + "|" + sym + "|c.1";
                observations.add(obs(i, label));
                inputs.add(new AnalysisInput(label, fx.shelfIds().get(s)));
                matchRequests.add(new MatchRequest(new ParsedCallNumber(cls, sym, "c.1"), fx.shelfIds().get(s)));
            }
            Map<String, Object> shelf = new LinkedHashMap<>();
            shelf.put("shelfCode", fx.shelfCodes().get(s));
            shelf.put("observations", observations);
            shelves.add(shelf);
        }
        insertBooks(bookRows);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("scanSessionId", fx.sessionId());
        request.put("shelves", shelves);
        int jsonBytes = objectMapper.writeValueAsBytes(request).length;

        // 구간별 시간: 매칭만, 파싱+매칭+판정(분석 전체), 전체 요청
        long t0 = System.nanoTime();
        matcher.match(matchRequests);
        long matchMs = ms(t0);
        t0 = System.nanoTime();
        analyzer.analyze(inputs);
        long analyzeMs = ms(t0);

        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        t0 = System.nanoTime();
        MvcResult created = send(request);
        long receiveMs = ms(t0);
        long queries = stats.getQueryExecutionCount();
        long statements = stats.getPrepareStatementCount();
        long entityInserts = stats.getEntityInsertCount();
        stats.setStatisticsEnabled(false);

        t0 = System.nanoTime();
        MvcResult replayed = send(request);
        long replayMs = ms(t0);

        System.out.printf("[PERF] mode=%s observations=%d shelves=%d requestBytes=%d | match=%dms analyze(parse+match+judge)=%dms "
                        + "receive(total, 201)=%dms persistence+overhead≈%dms replay(200)=%dms | JPQL queries=%d "
                        + "statements=%d entityInserts=%d%n",
                mode, total, shelfCount, jsonBytes, matchMs, analyzeMs, receiveMs, receiveMs - analyzeMs, replayMs,
                queries, statements, entityInserts);

        assertThat(status(created)).isEqualTo(201);
        JsonNode data = json(created).get("data");
        assertThat(data.get("observationCount").asInt()).isEqualTo(total);
        if (mode == Mode.BEST) {
            assertThat(data.get("normalCount").asInt()).isEqualTo(total);
            assertThat(entityInserts).isEqualTo(total);
        } else {
            assertThat(data.get("reviewRequiredCount").asInt()).isEqualTo(total);
            assertThat(count("select count(*) from observation_match_candidate c join book_observation o "
                    + "on o.observation_id = c.observation_id where o.scan_session_id = ?", fx.sessionId()))
                    .isEqualTo(3L * total);
            assertThat(entityInserts).isEqualTo(4L * total);
        }
        // 조회 쿼리 수는 관측 수와 무관하게 고정이다(회차 잠금·대상 서가·서가 코드·잔존 확인·매칭 최대 3개 등).
        assertThat(queries).isLessThanOrEqualTo(10);
        assertThat(status(replayed)).isEqualTo(200);
        assertThat(observationCount(fx.sessionId())).isEqualTo(total);
    }

    private Object[] bookRow(String cls, String sym, String copy, Long expectedShelfId) {
        return new Object[] {prefix + "-" + String.format("%05d", ++seq), cls + " " + sym, cls, sym, copy,
                expectedShelfId};
    }

    private void insertBooks(List<Object[]> rows) {
        jdbc.batchUpdate("insert into book (registration_no, title, call_number_raw, classification_no, "
                + "book_symbol, copy_number, expected_shelf_id, lifecycle_status, loan_status, created_at, "
                + "updated_at) values (?, '측정용 도서', ?, ?, ?, ?, ?, 'NORMAL', 'AVAILABLE', now(), now())", rows);
    }

    private static long ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
