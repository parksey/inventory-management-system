package com.logistics.inventory.domain.transaction.entity;

import static com.logistics.inventory.domain.common.ErrorCode.INVALID_REQUESTER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.logistics.inventory.domain.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class RequesterIdTest {

    @Test
    @DisplayName("TC-12-06 공백이 아닌 1~64자 요청자 ID는 만들어진다")
    void 정상_요청자() {
        assertThat(new RequesterId("worker-01").value()).isEqualTo("worker-01");
        assertThat(new RequesterId("a".repeat(64)).value()).hasSize(64);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("TC-12-06 비었거나 공백뿐인 요청자 ID는 INVALID_REQUESTER_ID")
    void 빈_요청자(final String value) {
        assertInvalid(value);
    }

    @Test
    @DisplayName("TC-12-06 65자 요청자 ID는 INVALID_REQUESTER_ID")
    void 긴_요청자() {
        assertInvalid("a".repeat(65));
    }

    private static void assertInvalid(final String value) {
        assertThatThrownBy(() -> new RequesterId(value))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(INVALID_REQUESTER_ID);
    }
}
