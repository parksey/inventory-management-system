package com.logistics.inventory.domain.idempotency.entity;

import java.io.Serializable;

// 멱등성 기록의 PK. 키는 회사마다 따로다
public record IdempotencyRecordId(
    String companyCode,
    String idempotencyKey
) implements Serializable {
}
