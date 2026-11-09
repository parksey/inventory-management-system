package com.logistics.inventory.domain.common;

import java.time.Duration;

// 중계(outbox)와 ERP 전달(erp_sync)이 같이 쓰는 재시도 규칙: 1, 2, 4, 8초 기다리고 5번째 시도에서 멈춘다
public final class RetryPolicy {

    public static final int MAX_ATTEMPTS = 5;
    private static final Duration BASE_BACKOFF = Duration.ofSeconds(1);

    private RetryPolicy() {
    }

    // n번째 시도가 결론 없이 끝났을 때 다음 시도까지 기다리는 시간
    public static Duration backoff(final int attempts) {
        return BASE_BACKOFF.multipliedBy(1L << (attempts - 1));
    }

    public static boolean isExhausted(final int attempts) {
        return attempts >= MAX_ATTEMPTS;
    }
}
