package com.__found.smart_library.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VisionPropertiesTest {

    @Test
    @DisplayName("키가 비어 있거나 공백이면 허용하지만 사용 안 함(enabled=false)이다")
    void blankKey_isAllowedButDisabled() {
        assertThat(new VisionProperties(null).enabled()).isFalse();
        assertThat(new VisionProperties("").enabled()).isFalse();
        assertThat(new VisionProperties("   ").enabled()).isFalse();
    }

    @Test
    @DisplayName("키가 32자 미만이면 기동을 막고, 오류 메시지에 키 값을 넣지 않는다")
    void shortKey_isRejectedWithoutExposingKey() {
        String shortKey = "s".repeat(31);

        assertThatThrownBy(() -> new VisionProperties(shortKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(shortKey);
    }

    @Test
    @DisplayName("키가 32자 이상이면 허용되고 enabled=true 이다")
    void validKey_isEnabled() {
        String key = "k".repeat(32);

        assertThatCode(() -> new VisionProperties(key)).doesNotThrowAnyException();
        assertThat(new VisionProperties(key).enabled()).isTrue();
    }

    @Test
    @DisplayName("toString 은 키 값을 노출하지 않는다")
    void toString_doesNotExposeKey() {
        String key = "k".repeat(40);

        assertThat(new VisionProperties(key).toString()).doesNotContain(key);
    }
}
