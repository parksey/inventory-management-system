package com.logistics.inventory.api.common;

// 여러 API가 함께 쓰는 요청 헤더 이름
public final class ApiHeaders {

    // 요청자 ID. 인증을 붙이면 앞단이 인증 후 넣어 준다
    public static final String REQUESTER_ID = "X-User-Id";

    private ApiHeaders() {
    }
}
