package com.logistics.inventory.api.inventory;

import com.logistics.inventory.api.support.TestcontainersConfig;
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
// 테스트마다 TS-BASIC-BLK-M 상품과 재고 5로 시작한다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
@Sql(statements = {
        "TRUNCATE stock_transaction, inventory, product RESTART IDENTITY",
        "INSERT INTO product (product_code, name) VALUES ('TS-BASIC-BLK-M', '베이직 반팔 티셔츠 검정 M')",
        "INSERT INTO inventory (product_id, quantity) SELECT id, 5 FROM product WHERE product_code = 'TS-BASIC-BLK-M'"
})
abstract class InventoryApiTestSupport {

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

    protected ResponseEntity<String> inbound(final String productCode, final String requesterId, final String body) {
        return post("inbound", productCode, requesterId, body);
    }

    protected ResponseEntity<String> outbound(final String productCode, final String requesterId, final String body) {
        return post("outbound", productCode, requesterId, body);
    }

    private ResponseEntity<String> post(final String action, final String productCode, final String requesterId, final String body) {
        return client.post()
                .uri("/api/v1/inventory/{code}/{action}", productCode, action)
                .header("X-User-Id", requesterId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().toEntity(String.class);
    }

    protected long quantityOf(final String productCode) {
        return jdbcTemplate.queryForObject(
                "SELECT i.quantity FROM inventory i JOIN product p ON p.id = i.product_id WHERE p.product_code = ?", Long.class, productCode);
    }

    protected long countProducts(final String productCode) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM product WHERE product_code = ?", Long.class, productCode);
    }

    protected long countTransactions() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM stock_transaction", Long.class);
    }
}
