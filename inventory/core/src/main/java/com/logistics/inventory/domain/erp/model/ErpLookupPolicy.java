package com.logistics.inventory.domain.erp.model;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

public record ErpLookupPolicy(List<Duration> delays) {

    public ErpLookupPolicy {
        if (delays == null || delays.isEmpty()) {
            throw new IllegalArgumentException("조회 간격이 하나 이상 있어야 합니다.");
        }
        delays = List.copyOf(delays);
    }

    // UNKNOWN이 된 뒤 첫 조회까지
    public Duration first() {
        return delays.getFirst();
    }

    // n번 조회해서 못 찾았을 때 다음 조회까지. 다 썼으면 빈 값(멈춤)
    public Optional<Duration> afterLookups(final int lookups) {
        return lookups < delays.size() ? Optional.of(delays.get(lookups)) : Optional.empty();
    }
}
