package com.logistics.inventory.domain.common;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    INVALID_PRODUCT_CODE("상품 코드 형식이 올바르지 않습니다.", ErrorKind.INVALID),
    INVALID_QUANTITY("수량이 올바르지 않습니다.", ErrorKind.INVALID),
    INVALID_REQUESTER_ID("요청자 ID가 올바르지 않습니다.", ErrorKind.INVALID),
    PRODUCT_NOT_FOUND("상품을 찾을 수 없습니다.", ErrorKind.NOT_FOUND);

    private final String message;
    private final ErrorKind kind;
}
