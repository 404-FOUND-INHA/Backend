package com.__found.smart_library.domain.observation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ParsedCallNumberTest {

    @Test
    @DisplayName("분류기호·도서기호·복본기호를 가공 없이 그대로 보관한다")
    void create_validValues_keepsValuesAsIs() {
        ParsedCallNumber parsed = new ParsedCallNumber("005.1", "최65ㅈ", "c.1");

        assertThat(parsed.classificationNo()).isEqualTo("005.1");
        assertThat(parsed.bookSymbol()).isEqualTo("최65ㅈ");
        assertThat(parsed.copyNumber()).isEqualTo("c.1");
    }

    @Test
    @DisplayName("복본기호가 null 이거나 공백이면 null 로 통일된다 (c.1 로 간주하지 않는다)")
    void create_blankOrNullCopyNumber_becomesNull() {
        assertThat(new ParsedCallNumber("005.1", "최65ㅈ", null).copyNumber()).isNull();
        assertThat(new ParsedCallNumber("005.1", "최65ㅈ", "").copyNumber()).isNull();
        assertThat(new ParsedCallNumber("005.1", "최65ㅈ", "  ").copyNumber()).isNull();
    }

    @Test
    @DisplayName("분류기호가 없거나 공백이면 생성할 수 없다")
    void create_blankClassificationNo_throws() {
        assertThatThrownBy(() -> new ParsedCallNumber(null, "최65ㅈ", "c.1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ParsedCallNumber(" ", "최65ㅈ", "c.1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("도서기호가 없거나 공백이면 생성할 수 없다")
    void create_blankBookSymbol_throws() {
        assertThatThrownBy(() -> new ParsedCallNumber("005.1", null, "c.1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ParsedCallNumber("005.1", "", "c.1")).isInstanceOf(IllegalArgumentException.class);
    }
}
