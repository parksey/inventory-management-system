package com.logistics.inventory.domain.product.entity;

import static com.logistics.inventory.domain.common.ErrorCode.INVALID_PRODUCT_CODE;

import com.logistics.inventory.domain.common.BusinessException;
import jakarta.persistence.Embeddable;
import java.util.regex.Pattern;

@Embeddable
public record ProductCode(
    String value
) {

    private static final Pattern FORMAT = Pattern.compile("[A-Z0-9-]{1,64}");

    public ProductCode {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw BusinessException.of(INVALID_PRODUCT_CODE, "value=" + value);
        }
    }
}
