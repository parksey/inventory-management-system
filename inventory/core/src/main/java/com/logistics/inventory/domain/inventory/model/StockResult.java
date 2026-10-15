package com.logistics.inventory.domain.inventory.model;

public record StockResult(
    String productCode,
    String name,
    long quantity
) {
}
