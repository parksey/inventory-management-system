package com.logistics.inventory.api.inventory;

import com.logistics.inventory.domain.inventory.model.StockResult;

public record StockResponse(
    String productCode,
    String name,
    long quantity
) {

    public static StockResponse from(final StockResult result) {
        return new StockResponse(result.productCode(), result.name(), result.quantity());
    }
}
