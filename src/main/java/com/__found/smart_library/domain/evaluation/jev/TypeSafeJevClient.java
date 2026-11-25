package com.__found.smart_library.domain.evaluation.jev;

import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * TypeSafe AI System One API 호출(POST /v1/systemone).
 *
 * <p>429(요청 한도 초과)·529(과부하)·5xx·시간 초과·연결 실패는 간격을 2배씩 늘려가며 최대 maxAttempts 번까지 호출한다.
 * 그 밖의 오류(400·401·422 등)는 다시 보내도 같은 결과이므로 바로 실패시킨다.
 */
public class TypeSafeJevClient implements JevClient {

    static final String PATH = "/v1/systemone";

    private final RestClient restClient;
    private final int maxAttempts;
    private final Duration retryBackoff;
    private final Sleeper sleeper;

    public TypeSafeJevClient(RestClient restClient, int maxAttempts, Duration retryBackoff, Sleeper sleeper) {
        this.restClient = restClient;
        this.maxAttempts = maxAttempts;
        this.retryBackoff = retryBackoff;
        this.sleeper = sleeper;
    }

    @Override
    public JevResponse evaluate(JevRequest request) {
        for (int attempt = 1; ; attempt++) {
            JevCallException failure;
            try {
                JevResponse response = restClient.post()
                        .uri(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(JevResponse.class);
                if (response == null) {
                    throw new JevCallException("JEV 응답이 비어 있습니다.", null, false, null);
                }
                return response;
            } catch (JevCallException e) {
                throw e;
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                failure = new JevCallException("JEV 호출 실패(HTTP " + status + ")", status, isRetryable(status), e);
            } catch (ResourceAccessException e) {
                failure = new JevCallException("JEV 호출 실패(연결 실패 또는 시간 초과)", null, true, e);
            } catch (RestClientException e) {
                throw new JevCallException("JEV 응답을 해석할 수 없습니다.", null, false, e);
            }
            if (!failure.isRetryable() || attempt >= maxAttempts) {
                throw failure;
            }
            pause(attempt, failure);
        }
    }

    private static boolean isRetryable(int status) {
        return status == 429 || status == 529 || status >= 500;
    }

    private void pause(int attempt, JevCallException failure) {
        try {
            sleeper.sleep(retryBackoff.multipliedBy(1L << (attempt - 1)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JevCallException("JEV 호출이 중단되었습니다.", failure.getHttpStatus(), false, e);
        }
    }

    /** 재시도 사이의 대기. 테스트에서 실제로 기다리지 않도록 분리했다. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }
}
