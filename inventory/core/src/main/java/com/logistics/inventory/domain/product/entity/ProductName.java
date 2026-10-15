package com.logistics.inventory.domain.product.entity;

import static com.logistics.inventory.domain.common.ErrorCode.INVALID_PRODUCT_NAME;

import com.logistics.inventory.domain.common.BusinessException;
import jakarta.persistence.Embeddable;

@Embeddable
public record ProductName(
    String value
) {

    private static final int MAX_LENGTH = 200;

    public ProductName {
        if (value == null || value.isBlank() || value.length() > MAX_LENGTH) {
            throw BusinessException.of(INVALID_PRODUCT_NAME, "value=" + value);
        }
    }
}
