package com.logistics.inventory.domain.erp.model;

import static com.logistics.inventory.domain.inventory.entity.StockChangeType.INBOUND;
import static com.logistics.inventory.domain.inventory.entity.StockChangeType.OUTBOUND;
import static org.assertj.core.api.Assertions.assertThat;

import com.logistics.inventory.domain.outbox.entity.StockChangedPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ErpLookupResultTest {

    private static final StockChangedPayload SENT =
            new StockChangedPayload(101L, "COMPANY-A", "TS-BASIC-BLK-M", INBOUND, 3, 5, 8, "2026-11-09T13:00+09:00");

    @Test
    @DisplayName("TC-48-35 ERP에 들어간 회사, 상품, 구분, 수량이 보낸 것과 모두 같아야 같은 내용이다")
    void 내용_비교() {
        assertThat(found("COMPANY-A", "TS-BASIC-BLK-M", INBOUND, 3).matches(SENT)).isTrue();
        assertThat(found("COMPANY-B", "TS-BASIC-BLK-M", INBOUND, 3).matches(SENT)).isFalse();
        assertThat(found("COMPANY-A", "OTHER", INBOUND, 3).matches(SENT)).isFalse();
        assertThat(found("COMPANY-A", "TS-BASIC-BLK-M", OUTBOUND, 3).matches(SENT)).isFalse();
        assertThat(found("COMPANY-A", "TS-BASIC-BLK-M", INBOUND, 4).matches(SENT)).isFalse();
        assertThat(ErpLookupResult.notFound().matches(SENT)).isFalse();
    }

    private static ErpLookupResult found(final String company, final String product,
            final com.logistics.inventory.domain.inventory.entity.StockChangeType type, final long quantity) {
        return ErpLookupResult.found("DOC-1", new ErpLookupResult.Recorded(company, product, type, quantity));
    }
}
