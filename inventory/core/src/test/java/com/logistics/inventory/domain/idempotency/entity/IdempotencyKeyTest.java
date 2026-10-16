package com.logistics.inventory.domain.idempotency.entity;

import static com.logistics.inventory.domain.common.ErrorCode.INVALID_IDEMPOTENCY_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.logistics.inventory.domain.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class IdempotencyKeyTest {

    @Test
    @DisplayName("TC-15-01 공백이 아닌 1~64자 키는 만들어진다")
    void 정상_키() {
        assertThat(new IdempotencyKey("6f1c2a3e-9b7d-4c1a-8e2f-0a5b3c7d9e11").value()).hasSize(36);
        assertThat(new IdempotencyKey("a".repeat(64)).value()).hasSize(64);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("TC-15-01 비었거나 공백뿐인 키는 INVALID_IDEMPOTENCY_KEY")
    void 빈_키(final String value) {
        assertInvalid(value);
    }

    @Test
    @DisplayName("TC-15-01 65자 키는 INVALID_IDEMPOTENCY_KEY")
    void 긴_키() {
        assertInvalid("a".repeat(65));
    }

    private static void assertInvalid(final String value) {
        assertThatThrownBy(() -> new IdempotencyKey(value))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(INVALID_IDEMPOTENCY_KEY);
    }
}
