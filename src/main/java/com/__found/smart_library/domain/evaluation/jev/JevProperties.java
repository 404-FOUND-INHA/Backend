package com.__found.smart_library.domain.evaluation.jev;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * JEV(TypeSafe System One API) 호출 설정 (application.yml 의 app.jev.*).
 * API Key 가 비어 있으면 JEV 평가 요청은 호출 없이 거부한다(fail-closed). 키는 로그·예외 메시지에 넣지 않는다.
 *
 * @param apiKey         Bearer API Key (환경변수 JEV_API_KEY)
 * @param baseUrl        API 서버 주소
 * @param model          호출할 모델 이름. 별칭(jev-latest)은 가리키는 모델이 바뀔 수 있다.
 * @param connectTimeout 연결 제한 시간
 * @param readTimeout    응답 대기 제한 시간
 * @param maxAttempts    한 요청당 최대 호출 횟수(첫 호출 포함). 429·529·5xx·시간 초과일 때만 다시 호출한다.
 * @param retryBackoff   첫 재시도까지 기다리는 시간. 이후 2배씩 늘어난다.
 */
@ConfigurationProperties(prefix = "app.jev")
public record JevProperties(
        String apiKey,
        @DefaultValue("https://api.typesafe.ai") String baseUrl,
        @DefaultValue("jev-latest") String model,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("60s") Duration readTimeout,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("1s") Duration retryBackoff) {

    public JevProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("JEV_BASE_URL 이 비어 있습니다.");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("JEV_MODEL 이 비어 있습니다.");
        }
        if (maxAttempts < 1) {
            throw new IllegalStateException("app.jev.max-attempts 는 1 이상이어야 합니다.");
        }
    }

    /** API Key 가 설정되어 JEV 를 호출할 수 있는지. */
    public boolean enabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** 실수로 로그에 찍혀도 키가 노출되지 않게 한다. */
    @Override
    public String toString() {
        return "JevProperties[apiKey=" + (enabled() ? "****" : "<unset>") + ", baseUrl=" + baseUrl
                + ", model=" + model + "]";
    }
}
