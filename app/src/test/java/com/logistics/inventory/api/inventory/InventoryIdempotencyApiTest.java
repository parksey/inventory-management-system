package com.logistics.inventory.api.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

// 멱등성 키: 키 검증, 같은 키 재전송, 다른 요청에 키 재사용, 실패한 요청의 키
class InventoryIdempotencyApiTest extends InventoryApiTestSupport {

    private static final String KEY = "6f1c2a3e-9b7d-4c1a-8e2f-0a5b3c7d9e11";

    @ParameterizedTest
    @ValueSource(strings = {"inbound", "outbound"})
    @DisplayName("TC-15-05 Idempotency-Key 헤더가 없으면 400이고 재고는 그대로다")
    void 키_없음(final String action) {
        final ResponseEntity<String> response = client().post()
                .uri("/api/v1/inventory/{code}/{action}", PRODUCT_CODE, action)
                .header("X-User-Id", "worker-01")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"quantity\": 3}")
                .retrieve().toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("BAD_REQUEST");
        assertThat(quantityOf(PRODUCT_CODE)).isEqualTo(5);
        assertThat(countTransactions()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})   // 공백, 65자
    @DisplayName("TC-15-06 Idempotency-Key가 공백이거나 65자면 400 INVALID_IDEMPOTENCY_KEY이고 재고는 그대로다")
    void 잘못된_키(final String idempotencyKey) {
        final ResponseEntity<String> response = inbound(PRODUCT_CODE, "worker-01", idempotencyKey, "{\"quantity\": 3}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("INVALID_IDEMPOTENCY_KEY");
        assertThat(quantityOf(PRODUCT_CODE)).isEqualTo(5);
        assertThat(countIdempotencyRecords()).isZero();
    }

    @Test
    @DisplayName("TC-15-07 같은 키로 두 번 입고하면 둘 다 같은 200 응답이고 재고와 원장은 한 번만 바뀐다")
    void 같은_키_입고_재전송() {
        final ResponseEntity<String> first = inbound(PRODUCT_CODE, "worker-01", KEY, "{\"quantity\": 3}");
        final ResponseEntity<String> second = inbound(PRODUCT_CODE, "worker-01", KEY, "{\"quantity\": 3}");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(quantityOf(PRODUCT_CODE)).isEqualTo(8);
        assertThat(countTransactions()).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-15-08 같은 키로 두 번 출고하면 둘 다 200이고 재고와 원장은 한 번만 바뀐다")
    void 같은_키_출고_재전송() {
        final ResponseEntity<String> first = outbound(PRODUCT_CODE, "worker-01", KEY, "{\"quantity\": 3}");
        final ResponseEntity<String> second = outbound(PRODUCT_CODE, "worker-01", KEY, "{\"quantity\": 3}");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(quantityOf(PRODUCT_CODE)).isEqualTo(2);
        assertThat(countTransactions()).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-15-09 처음 보는 상품에 같은 키로 두 번 입고해도 상품은 하나이고 재고는 한 번만 늘어난다")
    void 같은_키_새_상품_입고_재전송() {
        final ResponseEntity<String> first = inbound(NEW_PRODUCT_CODE, "worker-01", KEY, "{\"quantity\": 3}");
        final ResponseEntity<String> second = inbound(NEW_PRODUCT_CODE, "worker-01", KEY, "{\"quantity\": 3}");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(countProducts(NEW_PRODUCT_CODE)).isEqualTo(1);
        assertThat(quantityOf(NEW_PRODUCT_CODE)).isEqualTo(3);
    }

    @Test
    @DisplayName("TC-15-10 입고에 쓴 키로 다른 수량을 입고하면 422 IDEMPOTENCY_KEY_REUSED이고 재고와 원장은 그대로다")
    void 같은_키_다른_수량() {
        inbound(PRODUCT_CODE, "worker-01", KEY, "{\"quantity\": 3}");

        final ResponseEntity<String> response = inbound(PRODUCT_CODE, "worker-01", KEY, "{\"quantity\": 5}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(quantityOf(PRODUCT_CODE)).isEqualTo(8);
        assertThat(countTransactions()).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-15-11 입고에 쓴 키로 출고하면 422 IDEMPOTENCY_KEY_REUSED이고 재고는 그대로다")
    void 입고_키로_출고() {
        inbound(PRODUCT_CODE, "worker-01", KEY, "{\"quantity\": 3}");

        final ResponseEntity<String> response = outbound(PRODUCT_CODE, "worker-01", KEY, "{\"quantity\": 3}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(quantityOf(PRODUCT_CODE)).isEqualTo(8);
    }

    @Test
    @DisplayName("TC-15-12 실패한 요청은 키를 남기지 않아 같은 키로 다시 보내면 처음처럼 처리한다")
    void 실패한_요청_재전송() {
        final ResponseEntity<String> failed = outbound(PRODUCT_CODE, "worker-01", KEY, "{\"quantity\": 6}");
        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(countIdempotencyRecords()).isZero();

        inbound(PRODUCT_CODE, "worker-02", "{\"quantity\": 10}");
        final ResponseEntity<String> retried = outbound(PRODUCT_CODE, "worker-01", KEY, "{\"quantity\": 6}");

        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(quantityOf(PRODUCT_CODE)).isEqualTo(9);
        assertThat(countTransactions()).isEqualTo(2);
    }
}
