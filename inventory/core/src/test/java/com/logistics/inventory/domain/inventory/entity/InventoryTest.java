package com.logistics.inventory.domain.inventory.entity;

import static com.logistics.inventory.domain.common.ErrorCode.INVALID_QUANTITY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.logistics.inventory.domain.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InventoryTest {

    @Test
    @DisplayName("TC-12-04 재고 5에 3을 입고하면 8이 되고 변경 전후가 담긴 StockChange를 돌려준다")
    void 입고() {
        final Inventory inventory = inventory(1L, 5);

        final StockChange change = inventory.increase(new Quantity(3));

        assertThat(inventory.getQuantity()).isEqualTo(new Quantity(8));
        assertThat(change).isEqualTo(new StockChange(1L, StockChangeType.INBOUND, new Quantity(3), new Quantity(5), new Quantity(8)));
    }

    @Test
    @DisplayName("TC-12-05 0을 입고하면 INVALID_QUANTITY이고 재고는 그대로다")
    void 수량_0_입고() {
        final Inventory inventory = inventory(1L, 5);

        assertThatThrownBy(() -> inventory.increase(new Quantity(0)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(INVALID_QUANTITY);
        assertThat(inventory.getQuantity()).isEqualTo(new Quantity(5));
    }

    private static Inventory inventory(final Long productId, final long quantity) {
        final Inventory inventory = Inventory.create(productId);
        inventory.increase(new Quantity(quantity));
        return inventory;
    }
}
