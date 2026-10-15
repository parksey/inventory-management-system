package com.logistics.inventory.domain.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CursorQueryTest {

    @ParameterizedTest
    @CsvSource({"1, 1", "100, 100", "101, 100"})
    @DisplayName("TC-21-01 size는 100까지 그대로, 넘으면 100으로 줄어든다")
    void 크기_상한(final int requested, final int applied) {
        assertThat(new CursorQuery(null, requested).size()).isEqualTo(applied);
    }

    @Test
    @DisplayName("TC-21-01 cursor가 1 미만이거나 size가 1 미만이면 IllegalArgumentException")
    void 잘못된_커서() {
        assertThat(new CursorQuery(1L, 20).cursor()).isEqualTo(1L);
        assertThatThrownBy(() -> new CursorQuery(0L, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CursorQuery(null, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
