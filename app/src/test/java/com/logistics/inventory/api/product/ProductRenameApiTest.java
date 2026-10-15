package com.logistics.inventory.api.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.logistics.inventory.api.support.TestcontainersConfig;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.web.client.RestClient;

// 입고로 처음 생긴 상품처럼 이름이 상품 코드인 상품으로 시작한다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
@Sql(statements = {
        "TRUNCATE stock_transaction, inventory, product RESTART IDENTITY",
        "INSERT INTO product (product_code, name) VALUES ('TS-BASIC-BLK-M', 'TS-BASIC-BLK-M')",
        "INSERT INTO inventory (product_id, quantity) SELECT id, 5 FROM product WHERE product_code = 'TS-BASIC-BLK-M'"
})
class ProductRenameApiTest {

    private static final String PRODUCT_CODE = "TS-BASIC-BLK-M";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { })   // 4xx도 응답으로 받아 검증
                .build();
    }

    @Test
    @DisplayName("TC-14-04 상품명을 바꾸면 200과 빈 data를 돌려주고, 이후 재고 조회에 바뀐 이름이 나온다")
    void 이름_수정() {
        final ResponseEntity<String> response = rename(PRODUCT_CODE, "{\"name\": \"베이직 반팔 티셔츠 검정 M\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("SUCCESS");
        assertThat(JsonPath.<Object>read(response.getBody(), "$.data")).isNull();

        final ResponseEntity<String> stock = client.get()
                .uri("/api/v1/inventory/{code}", PRODUCT_CODE)
                .retrieve().toEntity(String.class);
        assertThat(JsonPath.<String>read(stock.getBody(), "$.data.name")).isEqualTo("베이직 반팔 티셔츠 검정 M");
    }

    @Test
    @DisplayName("TC-14-05 등록되지 않은 상품 코드면 404 PRODUCT_NOT_FOUND이고 상품을 만들지 않는다")
    void 없는_상품() {
        final ResponseEntity<String> response = rename("NOT-EXIST", "{\"name\": \"새 이름\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("PRODUCT_NOT_FOUND");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM product", Long.class)).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("invalidNameBodies")
    @DisplayName("TC-14-06 이름이 없거나 비었거나 201자면 400 INVALID_PRODUCT_NAME이고 이름은 그대로다")
    void 잘못된_이름(final String body) {
        final ResponseEntity<String> response = rename(PRODUCT_CODE, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("INVALID_PRODUCT_NAME");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT name FROM product WHERE product_code = ?", String.class, PRODUCT_CODE)).isEqualTo(PRODUCT_CODE);
    }

    @Test
    @DisplayName("TC-14-07 상품 코드 형식이 틀리면 400 INVALID_PRODUCT_CODE")
    void 잘못된_상품_코드() {
        final ResponseEntity<String> response = rename("ts-basic", "{\"name\": \"새 이름\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("INVALID_PRODUCT_CODE");
    }

    private static Stream<String> invalidNameBodies() {
        return Stream.of(
                "{}",
                "{\"name\": null}",
                "{\"name\": \"\"}",
                "{\"name\": \"   \"}",
                "{\"name\": \"" + "가".repeat(201) + "\"}"
        );
    }

    private ResponseEntity<String> rename(final String productCode, final String body) {
        return client.patch()
                .uri("/api/v1/products/{code}", productCode)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().toEntity(String.class);
    }
}
