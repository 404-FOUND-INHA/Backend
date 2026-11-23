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

    @Test
    @DisplayName("저장 컬럼 길이(분류기호 50, 도서기호 50, 복본기호 30)와 같은 값은 허용된다")
    void create_valuesAtMaxLength_areAccepted() {
        ParsedCallNumber parsed = new ParsedCallNumber("a".repeat(50), "b".repeat(50), "c".repeat(30));

        assertThat(parsed.classificationNo()).hasSize(50);
        assertThat(parsed.bookSymbol()).hasSize(50);
        assertThat(parsed.copyNumber()).hasSize(30);
    }

    @Test
    @DisplayName("분류기호 51자, 도서기호 51자, 복본기호 31자는 잘라 저장하지 않고 생성 단계에서 예외이며 메시지에 값을 담지 않는다")
    void create_valuesOverMaxLength_throwsWithoutExposingValue() {
        String longValue = "secret-".repeat(10);

        assertThatThrownBy(() -> new ParsedCallNumber("a".repeat(51), "b", "c"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("분류기호");
        assertThatThrownBy(() -> new ParsedCallNumber("a", "b".repeat(51), "c"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("도서기호");
        assertThatThrownBy(() -> new ParsedCallNumber("a", "b", "c".repeat(31)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("복본기호");
        assertThatThrownBy(() -> new ParsedCallNumber(longValue, "b", "c"))
                .hasMessageNotContaining("secret-");
    }

    @Test
    @DisplayName("길이는 UTF-16 단위가 아니라 문자 수로 센다 (보충 문자 25개 = 25자)")
    void create_supplementaryCharacters_countedAsCharacters() {
        String emojiFifty = "\uD83D\uDE00".repeat(50);   // 문자 50개, UTF-16 100단위

        assertThat(new ParsedCallNumber(emojiFifty, "b", null).classificationNo()).isEqualTo(emojiFifty);
        assertThatThrownBy(() -> new ParsedCallNumber(emojiFifty + "a", "b", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("공백뿐인 복본기호는 길이와 무관하게 null 로 통일된다")
    void create_blankLongCopyNumber_becomesNull() {
        assertThat(new ParsedCallNumber("a", "b", " ".repeat(40)).copyNumber()).isNull();
    }
}
