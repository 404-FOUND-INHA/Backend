package com.__found.smart_library.domain.evaluation.jev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JevPropertiesTest {

    private static JevProperties with(String apiKey, String baseUrl, String model, int attempts) {
        return new JevProperties(apiKey, baseUrl, model, Duration.ofSeconds(5), Duration.ofSeconds(60), attempts,
                Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("API Key 가 비어 있거나 공백이면 사용할 수 없고, 값이 있으면 사용할 수 있다")
    void enabledOnlyWithKey() {
        assertThat(with(null, "https://x", "m", 3).enabled()).isFalse();
        assertThat(with("  ", "https://x", "m", 3).enabled()).isFalse();
        assertThat(with("key", "https://x", "m", 3).enabled()).isTrue();
    }

    @Test
    @DisplayName("toString 에는 API Key 가 나오지 않는다")
    void toStringMasksKey() {
        assertThat(with("super-secret", "https://x", "m", 3).toString())
                .doesNotContain("super-secret").contains("****");
        assertThat(with("", "https://x", "m", 3).toString()).contains("<unset>");
    }

    @Test
    @DisplayName("주소·모델이 비어 있거나 최대 호출 횟수가 1 미만이면 기동을 실패시킨다")
    void rejectsInvalidSettings() {
        assertThatThrownBy(() -> with("k", " ", "m", 3)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> with("k", "https://x", "", 3)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> with("k", "https://x", "m", 0)).isInstanceOf(IllegalStateException.class);
        assertThat(with("k", "https://x", "m", 1).maxAttempts()).isEqualTo(1);
    }
}
