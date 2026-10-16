package com.logistics.inventory.api.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

// 출고 원장도 SQL로 넣는다. 원장 테이블과 출고 제약은 입고 이슈에서 이미 만들어져 있다
class InventoryHistoryApiTest extends InventoryApiTestSupport {

    @Test
    @DisplayName("TC-21-02 그 상품의 원장만 최신순으로, 각 건의 구분, 수량, 전후 재고, 요청자, 시각과 함께 나온다")
    void 이력_조회() {
        insertTransaction(PRODUCT_CODE, "INBOUND", 3, 5, 8, "worker-01");
        insertTransaction(PRODUCT_CODE, "OUTBOUND", 2, 8, 6, "worker-02");
        insertTransaction(PRODUCT_CODE, "INBOUND", 4, 6, 10, "worker-01");
        jdbcTemplate.update("INSERT INTO product (product_code, name) VALUES ('OTHER-01', '다른 상품')");
        insertTransaction("OTHER-01", "INBOUND", 7, 0, 7, "worker-03");

        final ResponseEntity<String> response = history(PRODUCT_CODE, "");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        final String body = response.getBody();
        assertThat(JsonPath.<String>read(body, "$.code")).isEqualTo("SUCCESS");
        assertThat(JsonPath.<List<String>>read(body, "$.data.items[*].type")).containsExactly("INBOUND", "OUTBOUND", "INBOUND");
        assertThat(JsonPath.<List<Integer>>read(body, "$.data.items[*].quantity")).containsExactly(4, 2, 3);
        assertThat(JsonPath.<List<Integer>>read(body, "$.data.items[*].beforeQuantity")).containsExactly(6, 8, 5);
        assertThat(JsonPath.<List<Integer>>read(body, "$.data.items[*].afterQuantity")).containsExactly(10, 6, 8);
        assertThat(JsonPath.<List<String>>read(body, "$.data.items[*].requesterId")).containsExactly("worker-01", "worker-02", "worker-01");
        assertThat(JsonPath.<List<String>>read(body, "$.data.items[*].createdAt")).hasSize(3).doesNotContainNull();
        assertThat(JsonPath.<Integer>read(body, "$.data.size")).isEqualTo(20);
        assertThat(JsonPath.<Boolean>read(body, "$.data.hasNext")).isFalse();
        assertThat(JsonPath.<Object>read(body, "$.data.nextCursor")).isNull();
    }

    @Test
    @DisplayName("TC-21-03 시각이 거꾸로 찍혀 있어도 원장 id 역순으로 나온다")
    void id_순서() {
        jdbcTemplate.update("""
                INSERT INTO stock_transaction (product_id, type, quantity, before_quantity, after_quantity, requester_id, created_at)
                SELECT id, 'INBOUND', 3, 5, 8, 'worker-01', now() FROM product WHERE product_code = ?""", PRODUCT_CODE);
        jdbcTemplate.update("""
                INSERT INTO stock_transaction (product_id, type, quantity, before_quantity, after_quantity, requester_id, created_at)
                SELECT id, 'OUTBOUND', 2, 8, 6, 'worker-02', now() - interval '1 second' FROM product WHERE product_code = ?""", PRODUCT_CODE);

        final ResponseEntity<String> response = history(PRODUCT_CODE, "");

        assertThat(JsonPath.<List<String>>read(response.getBody(), "$.data.items[*].type")).containsExactly("OUTBOUND", "INBOUND");
    }

    @Test
    @DisplayName("TC-21-04 입고 API가 남긴 원장이 이력에 그대로 나온다")
    void 입고_후_이력() {
        inbound(PRODUCT_CODE, "worker-01", "{\"quantity\": 3}");

        final ResponseEntity<String> response = history(PRODUCT_CODE, "");

        final String body = response.getBody();
        assertThat(JsonPath.<List<Object>>read(body, "$.data.items")).hasSize(1);
        assertThat(JsonPath.<String>read(body, "$.data.items[0].type")).isEqualTo("INBOUND");
        assertThat(JsonPath.<Integer>read(body, "$.data.items[0].quantity")).isEqualTo(3);
        assertThat(JsonPath.<Integer>read(body, "$.data.items[0].beforeQuantity")).isEqualTo(5);
        assertThat(JsonPath.<Integer>read(body, "$.data.items[0].afterQuantity")).isEqualTo(8);
        assertThat(JsonPath.<String>read(body, "$.data.items[0].requesterId")).isEqualTo("worker-01");
        assertThat(JsonPath.<String>read(body, "$.data.items[0].createdAt")).isNotBlank();
    }

    @Test
    @DisplayName("TC-21-05 nextCursor로 이어서 조회하면 앞에서 본 다음 이력부터 나오고, 끝나면 hasNext false와 nextCursor null")
    void 이어서_조회() {
        insertInbounds(25);   // 변경 후 재고가 1 ~ 25

        final ResponseEntity<String> first = history(PRODUCT_CODE, "?size=20");
        assertPage(first, 20, true);
        assertThat(afterQuantities(first)).first().isEqualTo(25);
        assertThat(afterQuantities(first)).last().isEqualTo(6);

        final Number cursor = JsonPath.read(first.getBody(), "$.data.nextCursor");
        final ResponseEntity<String> second = history(PRODUCT_CODE, "?size=20&cursor=" + cursor);
        assertPage(second, 5, false);
        assertThat(afterQuantities(second)).containsExactly(5, 4, 3, 2, 1);
        assertThat(JsonPath.<Object>read(second.getBody(), "$.data.nextCursor")).isNull();
    }

    @Test
    @DisplayName("TC-21-06 size가 없으면 20, 100을 넘으면 100으로 조회한다")
    void 크기_기본값과_상한() {
        insertInbounds(101);

        final ResponseEntity<String> byDefault = history(PRODUCT_CODE, "");
        assertPage(byDefault, 20, true);
        assertThat(JsonPath.<Integer>read(byDefault.getBody(), "$.data.size")).isEqualTo(20);

        final ResponseEntity<String> overMax = history(PRODUCT_CODE, "?size=1000");
        assertThat(overMax.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertPage(overMax, 100, true);
        assertThat(JsonPath.<Integer>read(overMax.getBody(), "$.data.size")).isEqualTo(100);
    }

    @Test
    @DisplayName("TC-21-07 cursor 0, size 0, 숫자가 아닌 cursor는 400 BAD_REQUEST")
    void 잘못된_커서() {
        final ResponseEntity<String> zeroCursor = history(PRODUCT_CODE, "?cursor=0");
        assertThat(zeroCursor.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(JsonPath.<String>read(zeroCursor.getBody(), "$.code")).isEqualTo("BAD_REQUEST");
        assertThat(JsonPath.<String>read(zeroCursor.getBody(), "$.data[0].field")).isEqualTo("cursor");

        final ResponseEntity<String> zeroSize = history(PRODUCT_CODE, "?size=0");
        assertThat(zeroSize.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(JsonPath.<String>read(zeroSize.getBody(), "$.data[0].field")).isEqualTo("size");

        final ResponseEntity<String> notNumber = history(PRODUCT_CODE, "?cursor=abc");
        assertThat(notNumber.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(JsonPath.<String>read(notNumber.getBody(), "$.code")).isEqualTo("BAD_REQUEST");
    }

    @Test
    @DisplayName("TC-21-08 없는 상품은 404 PRODUCT_NOT_FOUND, 형식이 틀린 상품 코드는 400 INVALID_PRODUCT_CODE")
    void 없는_상품() {
        final ResponseEntity<String> notFound = history("NOPE-01", "");
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(JsonPath.<String>read(notFound.getBody(), "$.code")).isEqualTo("PRODUCT_NOT_FOUND");

        final ResponseEntity<String> invalid = history("ts-basic", "");
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(JsonPath.<String>read(invalid.getBody(), "$.code")).isEqualTo("INVALID_PRODUCT_CODE");
    }

    @Test
    @DisplayName("TC-21-09 상품은 있는데 이력이 없으면 200과 빈 목록")
    void 이력_없음() {
        final ResponseEntity<String> response = history(PRODUCT_CODE, "");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertPage(response, 0, false);
    }

    @Test
    @DisplayName("TC-21-10 이어서 보는 도중 새 이력이 생겨도 앞에서 본 이력이 다시 나오지 않는다")
    void 조회_중_새_이력() {
        insertInbounds(25);
        final ResponseEntity<String> first = history(PRODUCT_CODE, "?size=20");
        insertTransaction(PRODUCT_CODE, "INBOUND", 1, 25, 26, "worker-01");

        final Number cursor = JsonPath.read(first.getBody(), "$.data.nextCursor");
        final ResponseEntity<String> second = history(PRODUCT_CODE, "?size=20&cursor=" + cursor);

        assertThat(afterQuantities(second)).containsExactly(5, 4, 3, 2, 1);
    }

    private static List<Integer> afterQuantities(final ResponseEntity<String> response) {
        return JsonPath.read(response.getBody(), "$.data.items[*].afterQuantity");
    }

    private ResponseEntity<String> history(final String productCode, final String query) {
        return client().get().uri("/api/v1/inventory/" + productCode + "/history" + query).retrieve().toEntity(String.class);
    }

    private void insertTransaction(
            final String productCode,
            final String type,
            final long quantity,
            final long before,
            final long after,
            final String requesterId
    ) {
        jdbcTemplate.update("""
                INSERT INTO stock_transaction (product_id, type, quantity, before_quantity, after_quantity, requester_id)
                SELECT id, ?, ?, ?, ?, ? FROM product WHERE product_code = ?""",
                type, quantity, before, after, requesterId, productCode);
    }

    // 재고 0에서 1씩 count번 입고한 원장
    private void insertInbounds(final int count) {
        jdbcTemplate.update("""
                INSERT INTO stock_transaction (product_id, type, quantity, before_quantity, after_quantity, requester_id)
                SELECT p.id, 'INBOUND', 1, g - 1, g, 'worker-01' FROM product p, generate_series(1, ?) g WHERE p.product_code = ?""",
                count, PRODUCT_CODE);
    }

    private static void assertPage(final ResponseEntity<String> response, final int itemCount, final boolean hasNext) {
        assertThat(JsonPath.<List<Object>>read(response.getBody(), "$.data.items")).hasSize(itemCount);
        assertThat(JsonPath.<Boolean>read(response.getBody(), "$.data.hasNext")).isEqualTo(hasNext);
    }
}
