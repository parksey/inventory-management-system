package com.logistics.inventory.domain.erp.model;

public enum ErpFailureKind {
    RETRYABLE, // (DB 연결 끊김, 잠금 대기 초과, 직렬화 충돌)
    PERMANENT, // (데이터가 깨짐, 잘못된 상태 전이)
    REQUIRES_REVIEW // 무엇인지 모른다.
}
