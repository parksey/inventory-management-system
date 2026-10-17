package com.logistics.inventory.api.inventory;

import com.logistics.inventory.api.support.TestcontainersConfig;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.web.client.RestClient;

// 입고, 출고 API 테스트의 공통 준비: 실제 HTTP 요청과, 재고와 원장을 SQL로 직접 읽는 헬퍼
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
@Sql(statements = {
        "TRUNCATE idempotency_record, stock_transaction, inventory, product RESTART IDENTITY",
        "INSERT INTO product (company_code, product_code, name) VALUES ('COMPANY-A', 'TS-BASIC-BLK-M', '베이직 반팔 티셔츠 검정 M')",
        "INSERT INTO inventory (product_id, quantity) SELECT id, 5 FROM product WHERE product_code = 'TS-BASIC-BLK-M'"
})
abstract class InventoryApiTestSupport {

    protected static final String COMPANY_CODE = "COMPANY-A";
    protected static final String OTHER_COMPANY_CODE = "COMPANY-B";
    protected static final String PRODUCT_CODE = "TS-BASIC-BLK-M";
    protected static final String NEW_PRODUCT_CODE = "NEW-ITEM-01";

    @LocalServerPort
    private int port;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    private RestClient client;

    @BeforeEach
    void setUpClient() {
        client = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { })   // 4xx, 5xx도 응답으로 받아 검증
                .build();
    }

    protected RestClient client() {
        return client;
    }

    // 멱등성 키를 정하지 않으면 요청마다 새 키를 붙인다 (서로 다른 사용자 행동)
    protected ResponseEntity<String> inbound(final String productCode, final String requesterId, final String body) {
        return inbound(productCode, requesterId, newIdempotencyKey(), body);
    }

    protected ResponseEntity<String> inbound(final String productCode, final String requesterId, final String idempotencyKey, final String body) {
        return inbound(COMPANY_CODE, productCode, requesterId, idempotencyKey, body);
    }

    protected ResponseEntity<String> inbound(
            final String companyCode,
            final String productCode,
            final String requesterId,
            final String idempotencyKey,
            final String body
    ) {
        return post("inbound", companyCode, productCode, requesterId, idempotencyKey, body);
    }

    protected ResponseEntity<String> outbound(final String productCode, final String requesterId, final String body) {
        return outbound(productCode, requesterId, newIdempotencyKey(), body);
    }

    protected ResponseEntity<String> outbound(final String productCode, final String requesterId, final String idempotencyKey, final String body) {
        return outbound(COMPANY_CODE, productCode, requesterId, idempotencyKey, body);
    }

    protected ResponseEntity<String> outbound(
            final String companyCode,
            final String productCode,
            final String requesterId,
            final String idempotencyKey,
            final String body
    ) {
        return post("outbound", companyCode, productCode, requesterId, idempotencyKey, body);
    }

    protected static String newIdempotencyKey() {
        return UUID.randomUUID().toString();
    }

    private ResponseEntity<String> post(
            final String action,
            final String companyCode,
            final String productCode,
            final String requesterId,
            final String idempotencyKey,
            final String body
    ) {
        return client.post()
                .uri("/api/v1/inventory/{code}/{action}", productCode, action)
                .header("X-Company-Code", companyCode)
                .header("X-User-Id", requesterId)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().toEntity(String.class);
    }

    protected long quantityOf(final String productCode) {
        return quantityOf(COMPANY_CODE, productCode);
    }

    protected long quantityOf(final String companyCode, final String productCode) {
        return jdbcTemplate.queryForObject("""
                SELECT i.quantity FROM inventory i JOIN product p ON p.id = i.product_id
                WHERE p.company_code = ? AND p.product_code = ?""", Long.class, companyCode, productCode);
    }

    protected long countProducts(final String productCode) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM product WHERE product_code = ?", Long.class, productCode);
    }

    protected long countIdempotencyRecords() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM idempotency_record", Long.class);
    }

    protected long countTransactions() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM stock_transaction", Long.class);
    }
}
