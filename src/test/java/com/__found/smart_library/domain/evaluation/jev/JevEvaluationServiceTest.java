package com.__found.smart_library.domain.evaluation.jev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.__found.smart_library.domain.book.repository.BookRepository;
import com.__found.smart_library.domain.evaluation.repository.BookEvaluationRepository;
import com.__found.smart_library.domain.evaluation.service.SessionBookResolver;
import com.__found.smart_library.domain.scan.repository.ScanSessionRepository;
import com.__found.smart_library.global.exception.BusinessException;
import com.__found.smart_library.global.exception.ErrorCode;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JevEvaluationServiceTest {

    @Test
    @DisplayName("API Key 가 비어 있으면 회차를 조회하거나 JEV 를 호출하지 않고 503(EVAL_002)로 거부한다(fail-closed)")
    void rejectsWithoutKey() {
        JevProperties noKey = new JevProperties("", "https://api.typesafe.ai", "jev-latest",
                Duration.ofSeconds(5), Duration.ofSeconds(60), 3, Duration.ofSeconds(1));
        JevClient client = mock(JevClient.class);
        ScanSessionRepository sessions = mock(ScanSessionRepository.class);
        JevEvaluationService service = new JevEvaluationService(noKey, client, mock(JevRequestFactory.class),
                new JevResultMapper(), mock(JevEvaluationWriter.class), sessions,
                mock(SessionBookResolver.class), mock(BookRepository.class), mock(BookEvaluationRepository.class));

        assertThatThrownBy(() -> service.evaluateSession(1L))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.JEV_NOT_CONFIGURED);
                    assertThat(e.getErrorCode().getStatus().value()).isEqualTo(503);
                    assertThat(e.getErrorCode().getCode()).isEqualTo("EVAL_002");
                });
        verifyNoInteractions(client, sessions);
    }
}
