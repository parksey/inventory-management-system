package com.logistics.inventory.api.inventory;

import com.logistics.inventory.domain.common.CompanyCode;
import com.logistics.inventory.domain.inventory.entity.Quantity;
import com.logistics.inventory.domain.inventory.model.OutboundCommand;
import com.logistics.inventory.domain.product.entity.ProductCode;
import com.logistics.inventory.domain.transaction.entity.RequesterId;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record OutboundRequest(
    @NotNull @Positive @Max(1_000_000) Long quantity
) {

    public OutboundCommand toCommand(final String companyCode, final String productCode, final String requesterId) {
        return new OutboundCommand(
            new CompanyCode(companyCode),
            new ProductCode(productCode),
            new Quantity(quantity),
            new RequesterId(requesterId)
        );
    }
}
