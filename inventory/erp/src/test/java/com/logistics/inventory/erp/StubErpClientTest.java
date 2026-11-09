package com.logistics.inventory.erp;

import static org.assertj.core.api.Assertions.assertThat;

import com.logistics.inventory.domain.erp.model.ErpLookupResult;
import com.logistics.inventory.domain.erp.model.ErpSendResult;
import com.logistics.inventory.domain.erp.model.ErpStockMovement;
import com.logistics.inventory.domain.inventory.entity.StockChangeType;
import com.logistics.inventory.domain.outbox.entity.StockChangedPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StubErpClientTest {

    @Test
    @DisplayName("TC-48-22 대역(Stub)은 보낸 키로 만든 전표 번호와 함께 성공을 돌려준다")
    void 성공() {
        final ErpStockMovement movement = new ErpStockMovement("stx-101", new StockChangedPayload(
                101L, "COMPANY-A", "TS-BASIC-BLK-M", StockChangeType.INBOUND, 3, 5, 8, "2026-11-09T13:00+09:00"));

        final ErpSendResult result = new StubErpClient().send(movement);

        assertThat(result.outcome()).isEqualTo(ErpSendResult.Outcome.SUCCESS);
        assertThat(result.documentId()).isEqualTo("LOG-stx-101");
    }

    @Test
    @DisplayName("TC-48-22 대역(Stub)은 보낸 기록이 없어 조회하면 항상 없음이다")
    void 조회() {
        assertThat(new StubErpClient().find("stx-101").outcome()).isEqualTo(ErpLookupResult.Outcome.NOT_FOUND);
    }
}
