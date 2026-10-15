package com.logistics.inventory.domain.common;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    private BusinessException(final ErrorCode errorCode, final String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public static BusinessException of(final ErrorCode errorCode, final String detail) {
        return new BusinessException(errorCode, errorCode.getMessage() + " " + detail);
    }
}
