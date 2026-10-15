package com.logistics.inventory.domain.inventory.model;

import com.logistics.inventory.domain.inventory.entity.Quantity;
import com.logistics.inventory.domain.product.entity.ProductCode;
import com.logistics.inventory.domain.transaction.entity.RequesterId;

public record InboundCommand(
    ProductCode productCode,
    Quantity quantity,
    RequesterId requesterId
) {
}
