package com.logistics.inventory.api.common;

import com.logistics.inventory.domain.common.ErrorKind;
import org.springframework.http.HttpStatus;

final class ErrorStatusMapper {

    private ErrorStatusMapper() {
    }

    static HttpStatus toStatus(final ErrorKind kind) {
        return switch (kind) {
            case INVALID -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case UNPROCESSABLE -> HttpStatus.UNPROCESSABLE_CONTENT;
        };
    }
}
