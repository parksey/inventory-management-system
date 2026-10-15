package com.logistics.inventory.domain.product.model;

import com.logistics.inventory.domain.product.entity.ProductCode;
import com.logistics.inventory.domain.product.entity.ProductName;

public record RenameProductCommand(
    ProductCode productCode,
    ProductName name
) {
}
