package com.logistics.inventory.api.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

// 외부 알림 메시지(outbox): 입고, 출고와 같은 트랜잭션에 남고, 롤백되거나 재전송이면 남지 않는다
class InventoryOutboxApiTest extends InventoryApiTestSupport {

    @Test
    @DisplayName("TC-48-01 입고하면 그 원장의 STOCK_CHANGED 메시지가 PENDING으로 하나 남고 payload가 원장 값과 같다")
    void 입고_메시지() {
        final ResponseEntity<String> response = inbound(NEW_PRODUCT_CODE, "worker-01", "{\"quantity\": 5}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(countOutboxMessages()).isEqualTo(1);
        assertMessageMatchesTransaction(NEW_PRODUCT_CODE, "INBOUND", 5, 0, 5);
    }

    @Test
    @DisplayName("TC-48-02 출고하면 그 원장의 STOCK_CHANGED 메시지가 남고 payload에 변동량과 전후 재고가 담긴다")
    void 출고_메시지() {
        final ResponseEntity<String> response = outbound(PRODUCT_CODE, "worker-01", "{\"quantity\": 3}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(countOutboxMessages()).isEqualTo(1);
        assertMessageMatchesTransaction(PRODUCT_CODE, "OUTBOUND", 3, 5, 2);
    }

    @Test
    @DisplayName("TC-48-03 재고 부족으로 출고가 롤백되면 메시지도 남지 않는다")
    void 롤백되면_메시지_없음() {
        final ResponseEntity<String> response = outbound(PRODUCT_CODE, "worker-01", "{\"quantity\": 6}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(countTransactions()).isZero();
        assertThat(countOutboxMessages()).isZero();
    }

    @Test
    @DisplayName("TC-48-04 같은 멱등성 키로 다시 입고해도 메시지는 하나 그대로다")
    void 재전송이면_메시지_그대로() {
        final String key = newIdempotencyKey();

        inbound(PRODUCT_CODE, "worker-01", key, "{\"quantity\": 3}");
        final ResponseEntity<String> retried = inbound(PRODUCT_CODE, "worker-01", key, "{\"quantity\": 3}");

        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(countOutboxMessages()).isEqualTo(1);
    }

    private long countOutboxMessages() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_message", Long.class);
    }

    private void assertMessageMatchesTransaction(
            final String productCode,
            final String type,
            final long quantity,
            final long before,
            final long after
    ) {
        final Map<String, Object> transaction = jdbcTemplate.queryForMap("SELECT id, created_at FROM stock_transaction");
        final Map<String, Object> message = jdbcTemplate.queryForMap("""
                SELECT company_code, aggregate_type, aggregate_id, event_type, status, attempts, payload::text AS payload
                FROM outbox_message""");
        final long transactionId = ((Number) transaction.get("id")).longValue();

        assertThat(message.get("company_code")).isEqualTo(COMPANY_CODE);
        assertThat(message.get("aggregate_type")).isEqualTo("STOCK_TRANSACTION");
        assertThat(message.get("aggregate_id")).isEqualTo(String.valueOf(transactionId));
        assertThat(message.get("event_type")).isEqualTo("STOCK_CHANGED");
        assertThat(message.get("status")).isEqualTo("PENDING");
        assertThat(message.get("attempts")).isEqualTo(0);

        final String payload = (String) message.get("payload");
        assertThat(JsonPath.<Number>read(payload, "$.stockTransactionId").longValue()).isEqualTo(transactionId);
        assertThat(JsonPath.<String>read(payload, "$.companyCode")).isEqualTo(COMPANY_CODE);
        assertThat(JsonPath.<String>read(payload, "$.productCode")).isEqualTo(productCode);
        assertThat(JsonPath.<String>read(payload, "$.type")).isEqualTo(type);
        assertThat(JsonPath.<Number>read(payload, "$.quantity").longValue()).isEqualTo(quantity);
        assertThat(JsonPath.<Number>read(payload, "$.beforeQuantity").longValue()).isEqualTo(before);
        assertThat(JsonPath.<Number>read(payload, "$.afterQuantity").longValue()).isEqualTo(after);
        // DB는 마이크로초까지 저장하므로 1ms 안에서 같은지 본다
        assertThat(OffsetDateTime.parse(JsonPath.<String>read(payload, "$.occurredAt")).toInstant())
                .isCloseTo(((Timestamp) transaction.get("created_at")).toInstant(), within(1, ChronoUnit.MILLIS));
    }
}
