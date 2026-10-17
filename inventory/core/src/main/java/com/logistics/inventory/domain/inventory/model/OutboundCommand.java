package com.logistics.inventory.domain.inventory.model;

import com.logistics.inventory.domain.common.CompanyCode;
import com.logistics.inventory.domain.inventory.entity.Quantity;
import com.logistics.inventory.domain.product.entity.ProductCode;
import com.logistics.inventory.domain.transaction.entity.RequesterId;

public record OutboundCommand(
    CompanyCode companyCode,
    ProductCode productCode,
    Quantity quantity,
    RequesterId requesterId
) {
}
