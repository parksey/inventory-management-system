package com.logistics.inventory.api.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

// 출고 요청 하나의 결과: 재고 감소, 원장, 재고 부족, 없는 상품, 검증 오류
class InventoryOutboundApiTest extends InventoryApiTestSupport {

    @Test
    @DisplayName("TC-13-05 출고하면 200과 빈 data를 돌려주고, 재고가 줄고 원장 한 건이 남는다")
    void 출고() {
        final ResponseEntity<String> response = outbound(PRODUCT_CODE, "worker-01", "{\"quantity\": 3}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("SUCCESS");
        assertThat(JsonPath.<Object>read(response.getBody(), "$.data")).isNull();
        assertThat(quantityOf(PRODUCT_CODE)).isEqualTo(2);

        final Map<String, Object> transaction = jdbcTemplate.queryForMap(
                "SELECT type, quantity, before_quantity, after_quantity, requester_id FROM stock_transaction");
        assertThat(transaction)
                .containsEntry("type", "OUTBOUND")
                .containsEntry("quantity", 3L)
                .containsEntry("before_quantity", 5L)
                .containsEntry("after_quantity", 2L)
                .containsEntry("requester_id", "worker-01");
    }

    @Test
    @DisplayName("TC-13-06 재고와 같은 수량을 출고하면 재고는 0이 된다")
    void 전량_출고() {
        final ResponseEntity<String> response = outbound(PRODUCT_CODE, "worker-01", "{\"quantity\": 5}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(quantityOf(PRODUCT_CODE)).isZero();
    }

    @Test
    @DisplayName("TC-13-07 재고보다 많이 출고하면 409 INSUFFICIENT_STOCK이고 재고와 원장은 그대로다")
    void 재고_초과_출고() {
        final ResponseEntity<String> response = outbound(PRODUCT_CODE, "worker-01", "{\"quantity\": 6}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(quantityOf(PRODUCT_CODE)).isEqualTo(5);
        assertThat(countTransactions()).isZero();
    }

    @Test
    @DisplayName("TC-13-08 등록되지 않은 상품 코드로 출고하면 404 PRODUCT_NOT_FOUND이고 상품을 만들지 않는다")
    void 없는_상품_출고() {
        final ResponseEntity<String> response = outbound("NOT-EXIST", "worker-01", "{\"quantity\": 1}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("PRODUCT_NOT_FOUND");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM product", Long.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-13-09 X-User-Id 헤더가 없으면 400이고 재고는 그대로다")
    void 요청자_없음() {
        final ResponseEntity<String> response = client().post()
                .uri("/api/v1/inventory/{code}/outbound", PRODUCT_CODE)
                .header("X-Company-Code", COMPANY_CODE)
                .header("Idempotency-Key", newIdempotencyKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"quantity\": 3}")
                .retrieve().toEntity(String.class);

        assertBadRequestAndUnchanged(response, "BAD_REQUEST");
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})   // 공백, 65자
    @DisplayName("TC-13-09 X-User-Id가 공백이거나 65자면 400 INVALID_REQUESTER_ID이고 재고는 그대로다")
    void 잘못된_요청자(final String requesterId) {
        assertBadRequestAndUnchanged(outbound(PRODUCT_CODE, requesterId, "{\"quantity\": 3}"), "INVALID_REQUESTER_ID");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"quantity\": 0}",
            "{\"quantity\": -1}",
            "{\"quantity\": 1000001}",
            "{}",
            "{\"quantity\": 1.5}",
            "{\"quantity\": \"abc\"}"
    })
    @DisplayName("TC-13-10 수량이 없거나 1 미만, 상한 초과, 정수가 아니면 400이고 재고는 그대로다")
    void 잘못된_수량(final String body) {
        assertBadRequestAndUnchanged(outbound(PRODUCT_CODE, "worker-01", body), "BAD_REQUEST");
    }

    @Test
    @DisplayName("TC-13-11 형식이 틀린 상품 코드로 출고하면 400 INVALID_PRODUCT_CODE")
    void 잘못된_상품_코드() {
        final ResponseEntity<String> response = outbound("ts-basic", "worker-01", "{\"quantity\": 3}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("INVALID_PRODUCT_CODE");
    }

    private void assertBadRequestAndUnchanged(final ResponseEntity<String> response, final String code) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo(code);
        assertThat(quantityOf(PRODUCT_CODE)).isEqualTo(5);
        assertThat(countTransactions()).isZero();
    }
}
