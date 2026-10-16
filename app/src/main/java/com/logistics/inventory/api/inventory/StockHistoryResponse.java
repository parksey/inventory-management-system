package com.logistics.inventory.api.inventory;

import com.logistics.inventory.domain.inventory.entity.StockChangeType;
import com.logistics.inventory.domain.transaction.entity.StockTransaction;
import java.time.OffsetDateTime;

public record StockHistoryResponse(
    StockChangeType type,
    long quantity,
    long beforeQuantity,
    long afterQuantity,
    String requesterId,
    OffsetDateTime createdAt
) {

    public static StockHistoryResponse from(final StockTransaction transaction) {
        return new StockHistoryResponse(
            transaction.getType(),
            transaction.getQuantity().value(),
            transaction.getBeforeQuantity().value(),
            transaction.getAfterQuantity().value(),
            transaction.getRequesterId().value(),
            transaction.getCreatedAt()
        );
    }
}
