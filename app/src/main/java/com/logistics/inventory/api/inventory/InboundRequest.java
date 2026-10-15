package com.logistics.inventory.api.inventory;

import com.logistics.inventory.domain.inventory.entity.Quantity;
import com.logistics.inventory.domain.inventory.model.InboundCommand;
import com.logistics.inventory.domain.product.entity.ProductCode;
import com.logistics.inventory.domain.transaction.entity.RequesterId;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record InboundRequest(
    @NotNull @Positive @Max(1_000_000) Long quantity
) {

    public InboundCommand toCommand(final String productCode, final String requesterId) {
        return new InboundCommand(
            new ProductCode(productCode),
            new Quantity(quantity),
            new RequesterId(requesterId)
        );
    }
}
