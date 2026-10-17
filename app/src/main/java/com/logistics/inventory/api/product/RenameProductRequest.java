package com.logistics.inventory.api.product;

import com.logistics.inventory.domain.product.entity.ProductCode;
import com.logistics.inventory.domain.product.entity.ProductName;
import com.logistics.inventory.domain.product.model.RenameProductCommand;

public record RenameProductRequest(
    String name
) {

    public RenameProductCommand toCommand(final String productCode) {
        return new RenameProductCommand(new ProductCode(productCode), new ProductName(name));
    }
}
