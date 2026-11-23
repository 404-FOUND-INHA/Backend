package com.__found.smart_library.domain.observation.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.__found.smart_library.domain.observation.repository.ObservationQueryRepository;
import jakarta.persistence.EntityManager;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * 조회 중 Vision 수신이 커밋돼도 한 응답은 하나의 시점(스냅샷)만 보여야 한다.
 *
 * <p>조회 트랜잭션을 "첫 SELECT 이후 다음 SELECT 직전"에서 CountDownLatch 로 멈춰 세우고, 그 사이 다른 스레드(별도 DB
 * 트랜잭션)에서 수신 API 를 실제로 커밋한 뒤 조회를 재개한다. Thread.sleep 은 쓰지 않는다. 멈추는 지점은 조회 Repository 를
 * 감싼 테스트 전용 위임 프록시이며, 실제 쿼리를 먼저 그대로 실행한 뒤에만 훅을 호출한다(운영 코드 무변경). 이 클래스는
 * @Transactional 이 아니므로 수신 데이터는 다른 스레드에서도 보인다.
 */
@Import({VisionTestParserConfig.class, ObservationQuerySnapshotTest.HookConfig.class})
class ObservationQuerySnapshotTest extends ObservationQueryTestSupport {

    @Autowired QueryHook hook;
    @Autowired EntityManager entityManager;

    @AfterEach
    void resetHook() {
        hook.afterCall = name -> { };
    }

    /** 조회 Repository 메서드가 실제 쿼리를 마친 직후 호출되는 테스트 전용 훅. */
    static class QueryHook implements BeanPostProcessor {
        volatile Consumer<String> afterCall = name -> { };

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (!(bean instanceof ObservationQueryRepository real)) {
                return bean;
            }
            return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {ObservationQueryRepository.class},
                    (proxy, method, args) -> {
                        try {
                            Object result = method.invoke(real, args);
                            afterCall.accept(method.getName());
                            return result;
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }
    }

    @TestConfiguration
    static class HookConfig {
        @Bean
        static QueryHook queryHook() {
            return new QueryHook();
        }
    }

    /** 조회 스레드가 멈출 지점에 도달했음(reached)과 재개 허가(proceed) 신호. */
    private static final class Gate {
        final CountDownLatch reached = new CountDownLatch(1);
        final CountDownLatch proceed = new CountDownLatch(1);

        /** 지정한 Repository 메서드의 쿼리가 끝나면 도달을 알리고, 재개 허가가 날 때까지 같은 트랜잭션 안에서 대기한다. */
        Consumer<String> pauseAfter(String methodName) {
            return name -> {
                if (!name.equals(methodName)) {
                    return;
                }
                reached.countDown();
                try {
                    if (!proceed.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("테스트가 조회 재개를 허가하지 않았습니다.");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            };
        }
    }

    @Test
    @DisplayName("회차 요약 조회 중 Vision 수신이 커밋돼도 첫 응답은 수신 전 스냅샷과 같고 새 조회는 수신 결과를 반환한다")
    void analysis_receiveCommitsMidQuery_responseIsSingleSnapshot() throws Exception {
        Fixture fx = fixture(3);
        Scenario sc = standardScenario(fx);
        String url = analysisUrl(fx.sessionId());
        JsonNode beforeReceive = data(fetch(url));
        assertThat(beforeReceive.get("session").get("status").asString()).isEqualTo("IN_PROGRESS");

        // 회차 → (서가 현황 조회 직후 멈춤) → [여기서 수신 커밋] → 서가·상태별 집계 조회
        Gate gate = new Gate();
        hook.afterCall = gate.pauseAfter("findShelves");
        JsonNode midQuery = queryWithConcurrentReceive(url, gate, sc.body());

        assertThat(sessionStatus(fx.sessionId())).isEqualTo("REVIEW");
        assertThat(observationCount(fx.sessionId())).isEqualTo(5);
        assertThat(midQuery).as("조회 도중 커밋된 수신이 섞이지 않고 수신 전 응답과 완전히 같아야 한다")
                .isEqualTo(beforeReceive);
        assertThat(midQuery.get("session").get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(midQuery.get("counts").get("total").asInt()).isZero();
        for (JsonNode shelf : midQuery.get("shelves")) {
            assertThat(shelf.get("scanStatus").asString()).isEqualTo("WAITING");
            assertThat(shelf.get("counts").get("total").asInt()).isZero();
        }

        JsonNode afterReceive = data(fetch(url));
        assertThat(afterReceive.get("session").get("status").asString()).isEqualTo("REVIEW");
        assertThat(afterReceive.get("session").get("processingStage").asString()).isEqualTo("ANALYSIS_DONE");
        assertThat(afterReceive.get("counts").get("total").asInt()).isEqualTo(5);
        assertThat(afterReceive.get("counts").get("normal").asInt()).isEqualTo(2);
        assertThat(afterReceive.get("counts").get("misplaced").asInt()).isEqualTo(1);
        assertThat(afterReceive.get("counts").get("reviewRequired").asInt()).isEqualTo(2);
        for (JsonNode shelf : afterReceive.get("shelves")) {
            assertThat(shelf.get("scanStatus").asString()).isEqualTo("RESULT_RECEIVED");
        }
    }

    @Test
    @DisplayName("서가 관측 목록 조회 중 Vision 수신이 커밋돼도 첫 응답은 WAITING 과 빈 목록이고 새 조회는 저장된 관측을 반환한다")
    void shelfObservations_receiveCommitsMidQuery_responseIsSingleSnapshot() throws Exception {
        Fixture fx = fixture(3);
        Scenario sc = standardScenario(fx);
        Long shelfId = fx.shelfIds().get(0);
        String url = shelfUrl(fx.sessionId(), shelfId);
        JsonNode beforeReceive = data(fetch(url));
        assertThat(beforeReceive.get("shelf").get("scanStatus").asString()).isEqualTo("WAITING");
        assertThat(beforeReceive.get("observations")).isEmpty();

        // 회차 존재 → 서가 헤더 조회 직후 멈춤 → [여기서 수신 커밋] → 관측 목록 조회
        Gate gate = new Gate();
        hook.afterCall = gate.pauseAfter("findShelf");
        JsonNode midQuery = queryWithConcurrentReceive(url, gate, sc.body());

        assertThat(sessionStatus(fx.sessionId())).isEqualTo("REVIEW");
        assertThat(midQuery).as("서가 헤더와 관측 목록이 같은 시점이어야 한다").isEqualTo(beforeReceive);
        assertThat(midQuery.get("shelf").get("scanStatus").asString()).isEqualTo("WAITING");
        assertThat(midQuery.get("observations")).isEmpty();

        JsonNode afterReceive = data(fetch(url));
        assertThat(afterReceive.get("shelf").get("scanStatus").asString()).isEqualTo("RESULT_RECEIVED");
        assertThat(afterReceive.get("shelf").get("detectedBookCount").asInt()).isEqualTo(4);
        assertThat(afterReceive.get("observations")).hasSize(4);
    }

    @Test
    @DisplayName("세 조회 메서드 모두 실제 DB 트랜잭션이 repeatable read 로 실행된다")
    void allQueryMethods_runUnderRepeatableRead() throws Exception {
        Fixture fx = fixture(3);
        receive(standardScenario(fx).body());
        Long observationId = observationId(fx.sessionId(), fx.shelfIds().get(0), 3);   // NO_MATCH(후보 있음)

        AtomicReference<String> analysis = new AtomicReference<>();
        AtomicReference<String> shelfList = new AtomicReference<>();
        AtomicReference<String> detail = new AtomicReference<>();
        // 서비스 트랜잭션 안에서 DB 가 실제로 적용한 격리 수준을 직접 읽는다(같은 트랜잭션에 참여하는 공유 EntityManager).
        hook.afterCall = name -> {
            switch (name) {
                case "findShelves" -> analysis.set(currentIsolation());
                case "findByShelf" -> shelfList.set(currentIsolation());
                case "findCandidates" -> detail.set(currentIsolation());
                default -> { }
            }
        };

        assertThat(status(fetch(analysisUrl(fx.sessionId())))).isEqualTo(200);
        assertThat(status(fetch(shelfUrl(fx.sessionId(), fx.shelfIds().get(0))))).isEqualTo(200);
        assertThat(status(fetch(observationUrl(fx.sessionId(), observationId)))).isEqualTo(200);

        assertThat(analysis.get()).as("요약").isEqualTo("repeatable read");
        assertThat(shelfList.get()).as("서가 목록").isEqualTo("repeatable read");
        assertThat(detail.get()).as("관측 상세").isEqualTo("repeatable read");
    }

    private String currentIsolation() {
        return (String) entityManager.createNativeQuery("select current_setting('transaction_isolation')")
                .getSingleResult();
    }

    /**
     * 조회를 다른 스레드에서 시작해 멈춤 지점까지 보낸 뒤, 이 스레드에서 수신 API 를 실제로 커밋하고 조회를 재개해 응답을 받는다.
     */
    private JsonNode queryWithConcurrentReceive(String url, Gate gate, Map<String, Object> receiveBody)
            throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> query = pool.submit(() -> fetch(url));
            assertThat(gate.reached.await(15, TimeUnit.SECONDS)).as("조회가 멈춤 지점에 도달해야 한다").isTrue();
            receive(receiveBody);           // 조회 트랜잭션이 열려 있는 동안 별도 트랜잭션에서 커밋(201)
            gate.proceed.countDown();       // 커밋이 끝난 뒤에만 조회를 재개한다
            return data(query.get(30, TimeUnit.SECONDS));
        } finally {
            gate.proceed.countDown();       // 검증 실패 시에도 대기 중인 조회 스레드를 풀어 준다
            requestThreadsStillRunning = !shutdownAndAwait(pool);
        }
    }

    private boolean shutdownAndAwait(ExecutorService pool) {
        pool.shutdownNow();
        try {
            return pool.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
