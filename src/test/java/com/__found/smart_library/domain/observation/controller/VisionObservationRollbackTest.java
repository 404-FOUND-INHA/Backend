package com.__found.smart_library.domain.observation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;

import com.__found.smart_library.domain.observation.repository.ObservationMatchCandidateRepository;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 저장 도중 실패하면 이미 저장한 관측까지 모두 롤백되고 회차 상태도 바뀌지 않아야 한다(전체가 한 트랜잭션).
 */
@Import(VisionTestParserConfig.class)
class VisionObservationRollbackTest extends VisionReceiveTestSupport {

    @MockitoSpyBean ObservationMatchCandidateRepository candidateRepository;

    @Test
    @DisplayName("관측을 저장한 뒤 후보 저장에서 실패하면 관측·서가·회차 변경이 모두 롤백된다")
    void receive_failureAfterObservationsSaved_rollsBackEverything() throws Exception {
        Fixture fx = fixture(3);
        Map<String, Object> request = standardScenario(fx).body();
        Map<String, Object> before = snapshot(fx.sessionId());
        doThrow(new IllegalStateException("후보 저장 실패(테스트)")).when(candidateRepository).saveAll(anyList());

        MvcResult result = send(request);

        assertThat(status(result)).isEqualTo(500);
        assertThat(observationCount(fx.sessionId())).isZero();
        assertThat(sessionStatus(fx.sessionId())).isEqualTo("IN_PROGRESS");
        assertThat(count("select count(*) from scan_session_shelf where scan_session_id = ? "
                + "and scan_status <> 'WAITING'", fx.sessionId())).isZero();
        assertThat(snapshot(fx.sessionId())).isEqualTo(before);
    }
}
