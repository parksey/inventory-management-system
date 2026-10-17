package com.logistics.inventory.api.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

// 회사 코드: 헤더 검증, 회사마다 따로 쌓이는 상품과 재고, 다른 회사 상품 접근, 회사별 멱등성 키
class InventoryCompanyApiTest extends InventoryApiTestSupport {

    private static final String KEY = "6f1c2a3e-9b7d-4c1a-8e2f-0a5b3c7d9e11";

    @Test
    @DisplayName("TC-40-05 X-Company-Code 헤더가 없으면 조회, 이력, 입고, 출고, 상품명 수정 모두 400 BAD_REQUEST이고 아무것도 바뀌지 않는다")
    void 회사_코드_없음() {
        final List<ResponseEntity<String>> responses = List.of(
                request(HttpMethod.GET, "/api/v1/inventory/" + PRODUCT_CODE, null, null),
                request(HttpMethod.GET, "/api/v1/inventory/" + PRODUCT_CODE + "/history", null, null),
                request(HttpMethod.POST, "/api/v1/inventory/" + PRODUCT_CODE + "/inbound", null, "{\"quantity\": 3}"),
                request(HttpMethod.POST, "/api/v1/inventory/" + PRODUCT_CODE + "/outbound", null, "{\"quantity\": 3}"),
                request(HttpMethod.PATCH, "/api/v1/products/" + PRODUCT_CODE, null, "{\"name\": \"새 이름\"}")
        );

        assertThat(responses).allSatisfy(response -> {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("BAD_REQUEST");
        });
        assertThat(quantityOf(PRODUCT_CODE)).isEqualTo(5);
        assertThat(countTransactions()).isZero();
        assertThat(nameOf(COMPANY_CODE, PRODUCT_CODE)).isEqualTo("베이직 반팔 티셔츠 검정 M");
    }

    @ParameterizedTest
    @ValueSource(strings = {"company-a", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})   // 소문자, 65자
    @DisplayName("TC-40-06 X-Company-Code 형식이 틀리면 조회와 입고가 400 INVALID_COMPANY_CODE이고 재고는 그대로다")
    void 잘못된_회사_코드(final String companyCode) {
        final ResponseEntity<String> stock = getStock(companyCode, PRODUCT_CODE);
        final ResponseEntity<String> inbound = inbound(companyCode, PRODUCT_CODE, "worker-01", newIdempotencyKey(), "{\"quantity\": 3}");

        assertThat(List.of(stock, inbound)).allSatisfy(response -> {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("INVALID_COMPANY_CODE");
        });
        assertThat(quantityOf(PRODUCT_CODE)).isEqualTo(5);
        assertThat(countTransactions()).isZero();
    }

    @Test
    @DisplayName("TC-40-07 다른 회사가 같은 상품 코드로 입고하면 상품, 재고, 이력이 회사마다 따로 쌓인다")
    void 같은_코드_다른_회사_입고() {
        final ResponseEntity<String> response = inbound(OTHER_COMPANY_CODE, PRODUCT_CODE, "worker-01", newIdempotencyKey(), "{\"quantity\": 3}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(quantityOf(COMPANY_CODE, PRODUCT_CODE)).isEqualTo(5);
        assertThat(quantityOf(OTHER_COMPANY_CODE, PRODUCT_CODE)).isEqualTo(3);
        assertThat(countProducts(PRODUCT_CODE)).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(getStock(COMPANY_CODE, PRODUCT_CODE).getBody(), "$.data.quantity")).isEqualTo(5);
        assertThat(JsonPath.<Integer>read(getStock(OTHER_COMPANY_CODE, PRODUCT_CODE).getBody(), "$.data.quantity")).isEqualTo(3);
        assertThat(JsonPath.<List<Object>>read(history(COMPANY_CODE, PRODUCT_CODE).getBody(), "$.data.items")).isEmpty();
        assertThat(JsonPath.<List<Integer>>read(history(OTHER_COMPANY_CODE, PRODUCT_CODE).getBody(), "$.data.items[*].quantity"))
                .containsExactly(3);
    }

    @Test
    @DisplayName("TC-40-08 다른 회사의 상품을 조회하면 없는 상품과 같은 404 PRODUCT_NOT_FOUND다")
    void 다른_회사_상품_조회() {
        final ResponseEntity<String> otherCompany = getStock(OTHER_COMPANY_CODE, PRODUCT_CODE);
        final ResponseEntity<String> notExist = getStock(OTHER_COMPANY_CODE, "NOT-EXIST");

        assertThat(otherCompany.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(otherCompany.getBody()).isEqualTo(notExist.getBody().replace("NOT-EXIST", PRODUCT_CODE));
        assertThat(JsonPath.<String>read(otherCompany.getBody(), "$.code")).isEqualTo("PRODUCT_NOT_FOUND");
        assertThat(JsonPath.<String>read(otherCompany.getBody(), "$.message")).isEqualTo("상품을 찾을 수 없습니다. productCode=" + PRODUCT_CODE);
    }

    @Test
    @DisplayName("TC-40-09 다른 회사의 상품은 이력 조회, 상품명 수정, 출고가 404 PRODUCT_NOT_FOUND이고 그 상품은 바뀌지 않는다")
    void 다른_회사_상품_변경() {
        final ResponseEntity<String> history = history(OTHER_COMPANY_CODE, PRODUCT_CODE);
        final ResponseEntity<String> rename = request(
                HttpMethod.PATCH, "/api/v1/products/" + PRODUCT_CODE, OTHER_COMPANY_CODE, "{\"name\": \"새 이름\"}");
        final ResponseEntity<String> outbound = outbound(OTHER_COMPANY_CODE, PRODUCT_CODE, "worker-01", newIdempotencyKey(), "{\"quantity\": 1}");

        assertThat(List.of(history, rename, outbound)).allSatisfy(response -> {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("PRODUCT_NOT_FOUND");
        });
        assertThat(quantityOf(COMPANY_CODE, PRODUCT_CODE)).isEqualTo(5);
        assertThat(nameOf(COMPANY_CODE, PRODUCT_CODE)).isEqualTo("베이직 반팔 티셔츠 검정 M");
        assertThat(countProducts(PRODUCT_CODE)).isEqualTo(1);
        assertThat(countTransactions()).isZero();
    }

    @Test
    @DisplayName("TC-40-10 두 회사가 같은 멱등성 키로 입고하면 각각 한 번씩 반영된다")
    void 같은_키_다른_회사() {
        insertOtherCompanyStock(5);

        final ResponseEntity<String> first = inbound(COMPANY_CODE, PRODUCT_CODE, "worker-01", KEY, "{\"quantity\": 3}");
        final ResponseEntity<String> second = inbound(OTHER_COMPANY_CODE, PRODUCT_CODE, "worker-01", KEY, "{\"quantity\": 3}");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(quantityOf(COMPANY_CODE, PRODUCT_CODE)).isEqualTo(8);
        assertThat(quantityOf(OTHER_COMPANY_CODE, PRODUCT_CODE)).isEqualTo(8);
        assertThat(countTransactions()).isEqualTo(2);
        assertThat(countIdempotencyRecords()).isEqualTo(2);
    }

    @Test
    @DisplayName("TC-40-11 다른 회사가 쓴 키로 다른 내용을 보내도 422가 아니라 처음 요청으로 처리된다")
    void 다른_회사_키_다른_내용() {
        inbound(COMPANY_CODE, PRODUCT_CODE, "worker-01", KEY, "{\"quantity\": 3}");

        final ResponseEntity<String> response = outbound(OTHER_COMPANY_CODE, "OTHER-01", "worker-02", KEY, "{\"quantity\": 2}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("PRODUCT_NOT_FOUND");
        assertThat(quantityOf(COMPANY_CODE, PRODUCT_CODE)).isEqualTo(8);
    }

    private void insertOtherCompanyStock(final long quantity) {
        jdbcTemplate.update("INSERT INTO product (company_code, product_code, name) VALUES (?, ?, ?)", OTHER_COMPANY_CODE, PRODUCT_CODE, PRODUCT_CODE);
        jdbcTemplate.update("INSERT INTO inventory (product_id, quantity) SELECT id, ? FROM product WHERE company_code = ? AND product_code = ?",
                quantity, OTHER_COMPANY_CODE, PRODUCT_CODE);
    }

    private String nameOf(final String companyCode, final String productCode) {
        return jdbcTemplate.queryForObject("SELECT name FROM product WHERE company_code = ? AND product_code = ?",
                String.class, companyCode, productCode);
    }

    private ResponseEntity<String> getStock(final String companyCode, final String productCode) {
        return request(HttpMethod.GET, "/api/v1/inventory/" + productCode, companyCode, null);
    }

    private ResponseEntity<String> history(final String companyCode, final String productCode) {
        return request(HttpMethod.GET, "/api/v1/inventory/" + productCode + "/history", companyCode, null);
    }

    // companyCode가 null이면 X-Company-Code를 붙이지 않는다. 다른 헤더는 모두 붙여 회사 코드만 빠진 요청이 되게 한다
    private ResponseEntity<String> request(final HttpMethod method, final String uri, final String companyCode, final String body) {
        final var spec = client().method(method).uri(uri)
                .header("X-User-Id", "worker-01")
                .header("Idempotency-Key", newIdempotencyKey());
        if (companyCode != null) {
            spec.header("X-Company-Code", companyCode);
        }
        if (body != null) {
            spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.retrieve().toEntity(String.class);
    }
}
