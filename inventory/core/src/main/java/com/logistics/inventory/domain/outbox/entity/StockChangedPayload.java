package com.logistics.inventory.domain.outbox.entity;

import com.logistics.inventory.domain.inventory.entity.StockChangeType;

public record StockChangedPayload(
    Long stockTransactionId,
    String companyCode,
    String productCode,
    StockChangeType type,
    long quantity,
    long beforeQuantity,
    long afterQuantity,
    String occurredAt
) {
}
