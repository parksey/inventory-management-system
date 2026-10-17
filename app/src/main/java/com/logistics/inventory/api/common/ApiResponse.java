package com.logistics.inventory.api.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

// 성공과 에러가 같은 모양인 공통 응답. 클라이언트는 code로 분기한다
public record ApiResponse<T>(
    int status,
    String code,
    String message,
    T data
) {

    public static <T> ApiResponse<T> ok(final T data) {
        return new ApiResponse<>(200, "SUCCESS", "success", data);
    }

    static <T> ApiResponse<T> error(final HttpStatusCode status, final String code, final String message, final T data) {
        return new ApiResponse<>(status.value(), code, message, data);
    }

    static <T> ApiResponse<T> error(final HttpStatus status, final T data) {
        return error(status, status.name(), status.getReasonPhrase(), data);
    }
}
