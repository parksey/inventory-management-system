package com.logistics.inventory.api.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.logistics.inventory.api.support.TestcontainersConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.web.client.RestClient;

// 상품 등록 API가 없어 테스트 데이터는 SQL로 넣는다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
@Sql(statements = {
        "TRUNCATE inventory, product RESTART IDENTITY",
        "INSERT INTO product (product_code, name) VALUES ('TS-BASIC-BLK-M', '베이직 반팔 티셔츠 검정 M')",
        "INSERT INTO inventory (product_id, quantity) SELECT id, 5 FROM product WHERE product_code = 'TS-BASIC-BLK-M'"
})
class InventoryQueryApiTest {

    @LocalServerPort
    private int port;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { })   // 4xx도 응답으로 받아 검증
                .build();
    }

    @Test
    @DisplayName("TC-7-08 등록된 상품 코드로 조회하면 200과 상품 코드, 상품명, 수량을 돌려준다")
    void 등록된_상품_조회() {
        final ResponseEntity<String> response = get("/api/v1/inventory/TS-BASIC-BLK-M");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<String>read(response.getBody(), "$.productCode")).isEqualTo("TS-BASIC-BLK-M");
        assertThat(JsonPath.<String>read(response.getBody(), "$.name")).isEqualTo("베이직 반팔 티셔츠 검정 M");
        assertThat(JsonPath.<Integer>read(response.getBody(), "$.quantity")).isEqualTo(5);
    }

    @Test
    @DisplayName("TC-7-09 등록되지 않은 상품 코드로 조회하면 404와 PRODUCT_NOT_FOUND")
    void 없는_상품_조회() {
        final ResponseEntity<String> response = get("/api/v1/inventory/NOT-EXIST");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("PRODUCT_NOT_FOUND");
    }

    @Test
    @DisplayName("TC-7-10 형식이 틀린 상품 코드로 조회하면 400과 INVALID_PRODUCT_CODE")
    void 잘못된_형식_조회() {
        final ResponseEntity<String> response = get("/api/v1/inventory/ts-basic");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("INVALID_PRODUCT_CODE");
    }

    private ResponseEntity<String> get(final String uri) {
        return client.get().uri(uri).retrieve().toEntity(String.class);
    }
}
