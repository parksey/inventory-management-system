package com.logistics.inventory.domain.erp.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ErpSyncTest {

    @ParameterizedTest(name = "{0}번째 실패 → {1}초 뒤")
    @CsvSource({"1, 1", "2, 2", "3, 4", "4, 8"})
    @DisplayName("TC-48-21 n번째 시도가 결론 없이 끝나면 1초 × 2^(n-1) 뒤에 다시 한다 (1, 2, 4, 8초)")
    void 백오프(final int attempts, final long seconds) {
        assertThat(ErpSync.backoff(attempts)).isEqualTo(Duration.ofSeconds(seconds));
    }

    @Test
    @DisplayName("TC-48-21 5번째 시도에서 결론이 안 나면 자동 처리를 멈춘다")
    void 최대_시도() {
        assertThat(ErpSync.MAX_ATTEMPTS).isEqualTo(5);
    }
}
