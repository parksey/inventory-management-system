package com.logistics.inventory.domain.common;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    INVALID_COMPANY_CODE("회사 코드 형식이 올바르지 않습니다.", ErrorKind.INVALID),
    INVALID_PRODUCT_CODE("상품 코드 형식이 올바르지 않습니다.", ErrorKind.INVALID),
    INVALID_QUANTITY("수량이 올바르지 않습니다.", ErrorKind.INVALID),
    INVALID_PRODUCT_NAME("상품명이 올바르지 않습니다.", ErrorKind.INVALID),
    INVALID_REQUESTER_ID("요청자 ID가 올바르지 않습니다.", ErrorKind.INVALID),
    INVALID_IDEMPOTENCY_KEY("멱등성 키가 올바르지 않습니다.", ErrorKind.INVALID),
    PRODUCT_NOT_FOUND("상품을 찾을 수 없습니다.", ErrorKind.NOT_FOUND),
    INSUFFICIENT_STOCK("재고가 부족합니다.", ErrorKind.CONFLICT),
    IDEMPOTENCY_KEY_REUSED("멱등성 키가 다른 요청에 이미 쓰였습니다.", ErrorKind.UNPROCESSABLE);

    private final String message;
    private final ErrorKind kind;
}
