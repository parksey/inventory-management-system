package com.logistics.inventory.domain.idempotency.entity;

import static com.logistics.inventory.domain.common.ErrorCode.INVALID_IDEMPOTENCY_KEY;

import com.logistics.inventory.domain.common.BusinessException;

// 클라이언트가 사용자 행동 하나마다 만드는 키. UUID
public record IdempotencyKey(
    String value
) {

    private static final int MAX_LENGTH = 64;

    public IdempotencyKey {
        if (value == null || value.isBlank() || value.length() > MAX_LENGTH) {
            throw BusinessException.of(INVALID_IDEMPOTENCY_KEY, "value=" + value);
        }
    }
}
