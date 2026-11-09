package com.logistics.inventory.domain.erp.model;

import com.logistics.inventory.domain.outbox.entity.StockChangedPayload;

// ERP에 보내는 재고 변동 한 건.
public record ErpStockMovement(
    String idempotencyKey,
    StockChangedPayload payload
) {
}
