package com.logistics.inventory.domain.erp.model;

public record ErpStockMovementOutcome(
    Long erpSyncId,
    Long outboxMessageId,
    String outcome,
    int attempts,
    String lastError
) {
}
