package com.logistics.inventory.domain.transaction.entity;

import static com.logistics.inventory.domain.common.ErrorCode.INVALID_REQUESTER_ID;

import com.logistics.inventory.domain.common.BusinessException;
import jakarta.persistence.Embeddable;

@Embeddable
public record RequesterId(
    String value
) {

    private static final int MAX_LENGTH = 64;

    public RequesterId {
        if (value == null || value.isBlank() || value.length() > MAX_LENGTH) {
            throw BusinessException.of(INVALID_REQUESTER_ID, "value=" + value);
        }
    }
}
