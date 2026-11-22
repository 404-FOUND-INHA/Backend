package com.__found.smart_library.domain.scan.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ScanSessionShelfIdTest {

    @Test
    @DisplayName("회차와 서가가 같으면 같은 키로 취급한다 (HashSet 포함 여부까지 확인)")
    void equalsAndHashCode_sameSessionAndShelf_treatedAsSameKey() {
        ScanSessionShelfId a = new ScanSessionShelfId(1L, 2L);
        ScanSessionShelfId b = new ScanSessionShelfId(1L, 2L);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        Set<ScanSessionShelfId> set = new HashSet<>(Set.of(a));
        assertThat(set).contains(b);
    }

    @Test
    @DisplayName("회차나 서가 중 하나라도 다르면 다른 키로 취급한다 (값을 서로 바꾼 경우 포함)")
    void equalsAndHashCode_differentSessionOrShelf_treatedAsDifferentKey() {
        ScanSessionShelfId base = new ScanSessionShelfId(1L, 2L);

        assertThat(base).isNotEqualTo(new ScanSessionShelfId(1L, 3L));
        assertThat(base).isNotEqualTo(new ScanSessionShelfId(9L, 2L));
        // 값을 서로 바꾼 (2,1) 도 다른 키
        assertThat(base).isNotEqualTo(new ScanSessionShelfId(2L, 1L));
    }
}
