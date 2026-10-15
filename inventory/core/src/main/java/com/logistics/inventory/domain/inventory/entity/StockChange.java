package com.logistics.inventory.domain.inventory.entity;

// 재고가 한 번 바뀐 결과. 원장이 이 값을 그대로 기록한다
public record StockChange(
    Long productId,
    StockChangeType type,
    Quantity amount,
    Quantity before,
    Quantity after
) {
}
