package com.logistics.inventory.api.common;

// 여러 API가 함께 쓰는 요청 헤더 이름
public final class ApiHeaders {

    // 요청자 ID. 인증을 붙이면 앞단이 인증 후 넣어 준다
    public static final String REQUESTER_ID = "X-User-Id";

    // 멱등성 키. 클라이언트가 사용자 행동 하나마다 만들고, 같은 행동을 다시 보낼 때 같은 키를 쓴다
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private ApiHeaders() {
    }
}
