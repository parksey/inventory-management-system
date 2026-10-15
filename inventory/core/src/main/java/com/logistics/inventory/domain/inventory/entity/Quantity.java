package com.logistics.inventory.domain.inventory.entity;

import static com.logistics.inventory.domain.common.ErrorCode.INVALID_QUANTITY;

import com.logistics.inventory.domain.common.BusinessException;
import jakarta.persistence.Embeddable;

@Embeddable
public record Quantity(
    long value
) {

    public Quantity {
        if (value < 0) {
            throw BusinessException.of(INVALID_QUANTITY, "value=" + value);
        }
    }

    public Quantity plus(final Quantity other) {
        return new Quantity(Math.addExact(value, other.value));
    }

    public Quantity minus(final Quantity other) {
        return new Quantity(value - other.value);
    }

    public boolean isLessThan(final Quantity other) {
        return value < other.value;
    }
}
