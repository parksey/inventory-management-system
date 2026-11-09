package com.logistics.inventory.domain.erp.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ErpLookupPolicyTest {

    private final ErpLookupPolicy policy = new ErpLookupPolicy(List.of(
            Duration.ofSeconds(10), Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofHours(1)));

    @Test
    @DisplayName("TC-48-34 첫 조회는 첫 간격 뒤, n번 못 찾으면 다음 간격 뒤에 조회하고, 간격을 다 쓰면 멈춘다")
    void 조회_간격() {
        assertThat(policy.first()).isEqualTo(Duration.ofSeconds(10));
        assertThat(policy.afterLookups(1)).contains(Duration.ofMinutes(1));
        assertThat(policy.afterLookups(4)).contains(Duration.ofHours(1));
        assertThat(policy.afterLookups(5)).isEmpty();
    }

    @Test
    @DisplayName("TC-48-34 조회 간격이 비어 있으면 만들 수 없다")
    void 빈_간격() {
        assertThatThrownBy(() -> new ErpLookupPolicy(List.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
