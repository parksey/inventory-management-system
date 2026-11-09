package com.logistics.inventory.domain.outbox.entity;

// outbox 메시지가 외부로 넘어갔는지
public enum OutboxStatus {
    PENDING,
    PUBLISHED,
    REQUIRES_REVIEW
}
