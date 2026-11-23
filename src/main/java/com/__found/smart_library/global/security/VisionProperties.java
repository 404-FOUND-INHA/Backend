package com.__found.smart_library.global.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Vision 서버 전용 API Key 설정 (application.yml 의 app.vision.*, 환경변수 VISION_API_KEY).
 * 비어 있으면 Vision 인증이 꺼진 것으로 보고 모든 요청을 거부한다(fail-closed).
 * 값이 있는데 {@value #MIN_KEY_LENGTH}자 미만이면 약한 키이므로 기동을 실패시킨다(키 값은 메시지에 넣지 않는다).
 */
@ConfigurationProperties(prefix = "app.vision")
public record VisionProperties(String apiKey) {

    static final int MIN_KEY_LENGTH = 32;

    public VisionProperties {
        if (apiKey != null && !apiKey.isBlank() && apiKey.length() < MIN_KEY_LENGTH) {
            throw new IllegalStateException(
                    "VISION_API_KEY 는 " + MIN_KEY_LENGTH + "자 이상이어야 합니다. 사용하지 않으려면 비워 두세요.");
        }
    }

    /** 키가 설정되어 Vision 인증을 쓸 수 있는지. */
    public boolean enabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** 실수로 로그에 찍혀도 키가 노출되지 않게 한다. */
    @Override
    public String toString() {
        return "VisionProperties[apiKey=" + (enabled() ? "****" : "<unset>") + "]";
    }
}
