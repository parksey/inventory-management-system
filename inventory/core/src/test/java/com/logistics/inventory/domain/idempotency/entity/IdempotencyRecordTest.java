package com.logistics.inventory.domain.idempotency.entity;

import static com.logistics.inventory.domain.common.ErrorCode.IDEMPOTENCY_KEY_REUSED;
import static com.logistics.inventory.domain.inventory.entity.StockChangeType.INBOUND;
import static com.logistics.inventory.domain.inventory.entity.StockChangeType.OUTBOUND;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.logistics.inventory.domain.common.BusinessException;
import com.logistics.inventory.domain.common.CompanyCode;
import com.logistics.inventory.domain.inventory.entity.Quantity;
import com.logistics.inventory.domain.product.entity.ProductCode;
import com.logistics.inventory.domain.transaction.entity.RequesterId;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class IdempotencyRecordTest {

    private static final CompanyCode COMPANY = new CompanyCode("COMPANY-A");
    private static final IdempotencyKey KEY = new IdempotencyKey("K1");

    private final IdempotencyRecord stored = IdempotencyRecord.of(
            COMPANY, KEY, OUTBOUND, new ProductCode("TS-BASIC-BLK-M"), new Quantity(3), new RequesterId("worker-01"));

    @Test
    @DisplayName("TC-15-02 같은 키로 같은 내용이 오면 재전송이라 예외가 없다")
    void 같은_내용() {
        final IdempotencyRecord request = IdempotencyRecord.of(
                COMPANY, KEY, OUTBOUND, new ProductCode("TS-BASIC-BLK-M"), new Quantity(3), new RequesterId("worker-01"));

        assertThatCode(() -> stored.verifySameRequest(request)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("differentRequests")
    @DisplayName("TC-15-03 같은 키로 구분, 상품 코드, 수량, 요청자 중 하나라도 다르면 IDEMPOTENCY_KEY_REUSED")
    void 다른_내용(final String field, final IdempotencyRecord request) {
        assertThatThrownBy(() -> stored.verifySameRequest(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(field)
                .extracting("errorCode").isEqualTo(IDEMPOTENCY_KEY_REUSED);
    }

    static Stream<Arguments> differentRequests() {
        final ProductCode code = new ProductCode("TS-BASIC-BLK-M");
        final Quantity three = new Quantity(3);
        final RequesterId worker = new RequesterId("worker-01");
        return Stream.of(
                Arguments.of("type", IdempotencyRecord.of(COMPANY, KEY, INBOUND, code, three, worker)),
                Arguments.of("productCode", IdempotencyRecord.of(COMPANY, KEY, OUTBOUND, new ProductCode("TS-BASIC-WHT-M"), three, worker)),
                Arguments.of("quantity", IdempotencyRecord.of(COMPANY, KEY, OUTBOUND, code, new Quantity(5), worker)),
                Arguments.of("requesterId", IdempotencyRecord.of(COMPANY, KEY, OUTBOUND, code, three, new RequesterId("worker-02")))
        );
    }
}
