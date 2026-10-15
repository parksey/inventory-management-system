package com.logistics.inventory.domain.inventory.entity;

import static com.logistics.inventory.domain.common.ErrorCode.INVALID_QUANTITY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.logistics.inventory.domain.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QuantityTest {

    @Test
    @DisplayName("TC-7-03 수량 0은 만들어진다")
    void 수량_0() {
        assertThat(new Quantity(0).value()).isZero();
    }

    @Test
    @DisplayName("TC-7-04 수량 -1은 INVALID_QUANTITY")
    void 음수_수량() {
        assertThatThrownBy(() -> new Quantity(-1))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(INVALID_QUANTITY);
    }
}
