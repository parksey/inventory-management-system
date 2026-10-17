package com.logistics.inventory.domain.product.model;

import com.logistics.inventory.domain.common.CompanyCode;
import com.logistics.inventory.domain.product.entity.ProductCode;
import com.logistics.inventory.domain.product.entity.ProductName;

public record RenameProductCommand(
    CompanyCode companyCode,
    ProductCode productCode,
    ProductName name
) {
}
