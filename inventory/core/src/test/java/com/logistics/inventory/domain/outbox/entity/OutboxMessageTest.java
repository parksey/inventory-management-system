package com.logistics.inventory.domain.outbox.entity;

import static com.logistics.inventory.domain.inventory.entity.StockChangeType.OUTBOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.logistics.inventory.domain.common.CompanyCode;
import com.logistics.inventory.domain.inventory.entity.Quantity;
import com.logistics.inventory.domain.inventory.entity.StockChange;
import com.logistics.inventory.domain.product.entity.ProductCode;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OutboxMessageTest {

    private static final CompanyCode COMPANY = new CompanyCode("COMPANY-A");
    private static final ProductCode PRODUCT = new ProductCode("TS-BASIC-BLK-M");
    private static final StockChange OUTBOUND_3 = new StockChange(1L, OUTBOUND, new Quantity(3), new Quantity(10), new Quantity(7));
    private static final OffsetDateTime OCCURRED_AT = OffsetDateTime.parse("2026-11-09T13:00:00+09:00");

    @Test
    @DisplayName("TC-48-07 원장 한 건으로 만든 메시지는 PENDING이고 aggregate, event, payload가 원장 값과 같다")
    void 재고_변동_메시지() {
        final OutboxMessage message = OutboxMessage.stockChanged(COMPANY, PRODUCT, 101L, OUTBOUND_3, OCCURRED_AT);

        assertThat(message.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(message.getAttempts()).isZero();
        assertThat(message.getCompanyCode()).isEqualTo(COMPANY);
        assertThat(message.getAggregateType()).isEqualTo("STOCK_TRANSACTION");
        assertThat(message.getAggregateId()).isEqualTo("101");
        assertThat(message.getEventType()).isEqualTo("STOCK_CHANGED");
        assertThat(message.getPayload()).isEqualTo(new StockChangedPayload(
                101L, "COMPANY-A", "TS-BASIC-BLK-M", OUTBOUND, 3, 10, 7, "2026-11-09T13:00+09:00"));
    }

    @Test
    @DisplayName("TC-48-07 원장을 저장하기 전(id 없음)에는 메시지를 만들 수 없다")
    void 원장_id_없음() {
        assertThatThrownBy(() -> OutboxMessage.stockChanged(COMPANY, PRODUCT, null, OUTBOUND_3, OCCURRED_AT))
                .isInstanceOf(IllegalStateException.class);
    }
}
