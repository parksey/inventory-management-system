package com.logistics.inventory.api.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

// 입고 요청 하나의 결과: 재고 증가, 원장, 새 상품 등록, 검증 오류
class InventoryInboundApiTest extends InventoryApiTestSupport {

    @Test
    @DisplayName("TC-12-07 기존 상품에 입고하면 200과 빈 data를 돌려주고, 재고가 늘고 원장 한 건이 남는다")
    void 기존_상품_입고() {
        final ResponseEntity<String> response = inbound(PRODUCT_CODE, "worker-01", "{\"quantity\": 3}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("SUCCESS");
        assertThat(JsonPath.<Object>read(response.getBody(), "$.data")).isNull();
        assertThat(quantityOf(PRODUCT_CODE)).isEqualTo(8);
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM product WHERE product_code = ?", String.class, PRODUCT_CODE))
                .isEqualTo("베이직 반팔 티셔츠 검정 M");   // 입고가 상품명을 덮어쓰지 않는다

        final List<Map<String, Object>> transactions = jdbcTemplate.queryForList(
                "SELECT type, quantity, before_quantity, after_quantity, requester_id, created_at FROM stock_transaction");
        assertThat(transactions).hasSize(1);
        assertThat(transactions.getFirst())
                .containsEntry("type", "INBOUND")
                .containsEntry("quantity", 3L)
                .containsEntry("before_quantity", 5L)
                .containsEntry("after_quantity", 8L)
                .containsEntry("requester_id", "worker-01");
        assertThat(transactions.getFirst().get("created_at")).isNotNull();
    }

    @Test
    @DisplayName("TC-12-08 없는 상품 코드로 입고하면 이름이 상품 코드인 상품을 만들고 입고한다")
    void 새_상품_입고() {
        final ResponseEntity<String> response = inbound(NEW_PRODUCT_CODE, "worker-01", "{\"quantity\": 10}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM product WHERE product_code = ?", String.class, NEW_PRODUCT_CODE))
                .isEqualTo(NEW_PRODUCT_CODE);
        assertThat(quantityOf(NEW_PRODUCT_CODE)).isEqualTo(10);
        assertThat(jdbcTemplate.queryForMap("SELECT before_quantity, after_quantity FROM stock_transaction"))
                .containsEntry("before_quantity", 0L)
                .containsEntry("after_quantity", 10L);
    }

    @Test
    @DisplayName("TC-12-09 X-User-Id 헤더가 없으면 400이고 재고는 그대로다")
    void 요청자_없음() {
        final ResponseEntity<String> response = client().post()
                .uri("/api/v1/inventory/{code}/inbound", PRODUCT_CODE)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"quantity\": 3}")
                .retrieve().toEntity(String.class);

        assertBadRequestAndUnchanged(response, "BAD_REQUEST");
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})   // 공백, 65자
    @DisplayName("TC-12-09 X-User-Id가 공백이거나 65자면 400 INVALID_REQUESTER_ID이고 재고는 그대로다")
    void 잘못된_요청자(final String requesterId) {
        assertBadRequestAndUnchanged(inbound(PRODUCT_CODE, requesterId, "{\"quantity\": 3}"), "INVALID_REQUESTER_ID");
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
    @DisplayName("TC-12-10 수량이 없거나 1 미만, 상한 초과, 정수가 아니면 400이고 재고는 그대로다")
    void 잘못된_수량(final String body) {
        assertBadRequestAndUnchanged(inbound(PRODUCT_CODE, "worker-01", body), "BAD_REQUEST");
    }

    @Test
    @DisplayName("TC-12-10 수량 검증 실패 응답의 틀린 항목 이름은 quantity다")
    void 잘못된_수량_항목() {
        final ResponseEntity<String> response = inbound(PRODUCT_CODE, "worker-01", "{\"quantity\": 0}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(JsonPath.<String>read(response.getBody(), "$.data[0].field")).isEqualTo("quantity");
    }

    @Test
    @DisplayName("TC-12-11 수량 상한 1000000은 입고된다")
    void 상한_수량() {
        final ResponseEntity<String> response = inbound(PRODUCT_CODE, "worker-01", "{\"quantity\": 1000000}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(quantityOf(PRODUCT_CODE)).isEqualTo(1_000_005);
    }

    @Test
    @DisplayName("TC-12-12 형식이 틀린 상품 코드로 입고하면 400 INVALID_PRODUCT_CODE이고 상품이 생기지 않는다")
    void 잘못된_상품_코드() {
        final ResponseEntity<String> response = inbound("ts-basic", "worker-01", "{\"quantity\": 3}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("INVALID_PRODUCT_CODE");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM product", Long.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-12-16 상품은 있는데 재고 행이 없으면 500이고 재고 행과 원장을 만들지 않는다")
    void 재고_없는_상품() {
        jdbcTemplate.update("INSERT INTO product (product_code, name) VALUES ('NO-STOCK-01', '재고 행 없는 상품')");

        final ResponseEntity<String> response = inbound("NO-STOCK-01", "worker-01", "{\"quantity\": 3}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM inventory", Long.class)).isEqualTo(1);
        assertThat(countTransactions()).isZero();
    }

    private void assertBadRequestAndUnchanged(final ResponseEntity<String> response, final String code) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo(code);
        assertThat(quantityOf(PRODUCT_CODE)).isEqualTo(5);
        assertThat(countTransactions()).isZero();
    }
}
