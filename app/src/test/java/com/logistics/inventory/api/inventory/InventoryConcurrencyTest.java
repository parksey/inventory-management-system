package com.logistics.inventory.api.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

// 여러 요청이나 다른 트랜잭션과 겹칠 때의 입고, 출고: 동시 요청, 등록 경합, 잠금 대기 초과, 같은 키 동시 요청
class InventoryConcurrencyTest extends InventoryApiTestSupport {

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("TC-12-13 같은 상품에 동시에 입고해도 최종 재고는 입고 수량의 합이고 원장 합계와 같다")
    void 같은_상품_동시_입고() throws Exception {
        jdbcTemplate.update("UPDATE inventory SET quantity = 0");

        final List<ResponseEntity<String>> responses = concurrently(20, i -> inbound(PRODUCT_CODE, "worker-" + i, "{\"quantity\": " + i + "}"));

        assertThat(responses).allSatisfy(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK));
        assertThat(quantityOf(PRODUCT_CODE)).isEqualTo(210);   // 1 + 2 + ... + 20
        assertThat(countTransactions()).isEqualTo(20);
        assertThat(jdbcTemplate.queryForObject("SELECT sum(quantity) FROM stock_transaction", Long.class)).isEqualTo(210);
    }

    @Test
    @DisplayName("TC-16-01 같은 상품에 입고와 출고가 섞여 동시에 와도 재고는 시작 재고 - 출고 합 + 입고 합이고 원장과 같다")
    void 같은_상품_입출고_동시() throws Exception {
        jdbcTemplate.update("UPDATE inventory SET quantity = 100");

        // 1 ~ 30번은 출고, 31 ~ 70번은 입고. 스레드가 한 번에 출발해 순서가 섞인다
        final List<ResponseEntity<String>> responses = concurrently(70, i -> i <= 30
                ? outbound(PRODUCT_CODE, "worker-" + i, "{\"quantity\": 1}")
                : inbound(PRODUCT_CODE, "worker-" + i, "{\"quantity\": 1}"));

        assertThat(responses).allSatisfy(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK));
        assertThat(quantityOf(PRODUCT_CODE)).isEqualTo(110);   // 100 - 30 + 40
        assertThat(countTransactions()).isEqualTo(70);
        assertThat(jdbcTemplate.queryForObject("SELECT sum(quantity) FROM stock_transaction WHERE type = 'INBOUND'", Long.class)).isEqualTo(40);
        assertThat(jdbcTemplate.queryForObject("SELECT sum(quantity) FROM stock_transaction WHERE type = 'OUTBOUND'", Long.class)).isEqualTo(30);
    }

    @Test
    @DisplayName("TC-12-14 처음 보는 상품에 동시에 입고해도 상품은 하나만 생긴다")
    void 새_상품_동시_입고() throws Exception {
        final List<ResponseEntity<String>> responses = concurrently(20, i -> inbound(NEW_PRODUCT_CODE, "worker-" + i, "{\"quantity\": 1}"));

        assertThat(responses).allSatisfy(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK));
        assertThat(countProducts(NEW_PRODUCT_CODE)).isEqualTo(1);
        assertThat(quantityOf(NEW_PRODUCT_CODE)).isEqualTo(20);
        assertThat(countTransactions()).isEqualTo(20);
    }

    @Test
    @DisplayName("TC-12-15 다른 트랜잭션이 재고 행을 3초 넘게 잠그고 있으면 503이고 재고는 그대로다")
    void 잠금_대기_초과() throws Exception {
        final ResponseEntity<String> response;
        try (Connection holder = dataSource.getConnection(); Statement statement = holder.createStatement()) {
            holder.setAutoCommit(false);
            statement.execute("SELECT quantity FROM inventory FOR UPDATE");

            response = inbound(PRODUCT_CODE, "worker-01", "{\"quantity\": 3}");

            holder.rollback();
        }

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(quantityOf(PRODUCT_CODE)).isEqualTo(5);
        assertThat(countTransactions()).isZero();
    }

    @Test
    @DisplayName("TC-12-17 다른 트랜잭션이 같은 새 상품을 등록하는 중이면 커밋을 기다렸다가 그 상품과 재고에 입고한다")
    void 등록_중인_새_상품_입고() throws Exception {
        try (ExecutorService executor = Executors.newSingleThreadExecutor();
             Connection other = dataSource.getConnection();
             Statement statement = other.createStatement()) {
            // 다른 Pod가 같은 상품을 등록하는 중: 상품과 재고를 넣고 아직 커밋하지 않았다
            other.setAutoCommit(false);
            statement.execute("INSERT INTO product (company_code, product_code, name) VALUES ('COMPANY-A', '" + NEW_PRODUCT_CODE + "', '" + NEW_PRODUCT_CODE + "')");
            statement.execute("INSERT INTO inventory (product_id, quantity) SELECT id, 0 FROM product WHERE product_code = '" + NEW_PRODUCT_CODE + "'");

            final Future<ResponseEntity<String>> pending = executor.submit(() -> inbound(NEW_PRODUCT_CODE, "worker-01", "{\"quantity\": 3}"));

            Thread.sleep(500);
            assertThat(pending).isNotDone();   // 상품 INSERT가 유니크 인덱스에서 기다리는 중

            other.commit();

            assertThat(pending.get(5, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        assertThat(countProducts(NEW_PRODUCT_CODE)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM inventory i JOIN product p ON p.id = i.product_id WHERE p.product_code = ?",
                Long.class, NEW_PRODUCT_CODE)).isEqualTo(1);
        assertThat(quantityOf(NEW_PRODUCT_CODE)).isEqualTo(3);
        assertThat(countTransactions()).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-13-12 같은 상품에 동시에 출고해도 재고는 음수가 되지 않고 성공한 출고만큼만 줄어든다")
    void 같은_상품_동시_출고() throws Exception {
        jdbcTemplate.update("UPDATE inventory SET quantity = 10");

        final List<ResponseEntity<String>> responses = concurrently(20, i -> outbound(PRODUCT_CODE, "worker-" + i, "{\"quantity\": 1}"));

        assertThat(responses).filteredOn(response -> response.getStatusCode() == HttpStatus.OK).hasSize(10);
        assertThat(responses).filteredOn(response -> response.getStatusCode() == HttpStatus.CONFLICT)
                .hasSize(10)
                .allSatisfy(response -> assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("INSUFFICIENT_STOCK"));
        assertThat(quantityOf(PRODUCT_CODE)).isZero();
        assertThat(countTransactions()).isEqualTo(10);
        assertThat(jdbcTemplate.queryForObject("SELECT sum(quantity) FROM stock_transaction WHERE type = 'OUTBOUND'", Long.class)).isEqualTo(10);
    }

    @Test
    @DisplayName("TC-15-13 같은 키로 동시에 입고해도 재고와 원장에는 한 번만 반영되고 모든 응답은 200이다")
    void 같은_키_동시_입고() throws Exception {
        final String key = newIdempotencyKey();

        final List<ResponseEntity<String>> responses = concurrently(20, i -> inbound(PRODUCT_CODE, "worker-01", key, "{\"quantity\": 1}"));

        assertThat(responses).allSatisfy(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK));
        assertThat(quantityOf(PRODUCT_CODE)).isEqualTo(6);
        assertThat(countTransactions()).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-15-14 같은 키로 동시에 출고해도 재고와 원장에는 한 번만 반영되고 모든 응답은 200이다")
    void 같은_키_동시_출고() throws Exception {
        final String key = newIdempotencyKey();

        final List<ResponseEntity<String>> responses = concurrently(20, i -> outbound(PRODUCT_CODE, "worker-01", key, "{\"quantity\": 1}"));

        assertThat(responses).allSatisfy(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK));
        assertThat(quantityOf(PRODUCT_CODE)).isEqualTo(4);
        assertThat(countTransactions()).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-40-12 두 회사가 같은 상품 코드에 동시에 출고해도 회사마다 재고는 음수가 되지 않고 원장 합계와 같다")
    void 두_회사_동시_출고() throws Exception {
        jdbcTemplate.update("UPDATE inventory SET quantity = 10");
        jdbcTemplate.update("INSERT INTO product (company_code, product_code, name) VALUES (?, ?, ?)", OTHER_COMPANY_CODE, PRODUCT_CODE, PRODUCT_CODE);
        jdbcTemplate.update("INSERT INTO inventory (product_id, quantity) SELECT id, 10 FROM product WHERE company_code = ?", OTHER_COMPANY_CODE);

        // 홀수는 COMPANY-A, 짝수는 COMPANY-B. 회사마다 15건
        final List<ResponseEntity<String>> responses = concurrently(30, i -> outbound(
                i % 2 == 1 ? COMPANY_CODE : OTHER_COMPANY_CODE, PRODUCT_CODE, "worker-" + i, newIdempotencyKey(), "{\"quantity\": 1}"));

        assertThat(responses).filteredOn(response -> response.getStatusCode() == HttpStatus.OK).hasSize(20);
        assertThat(responses).filteredOn(response -> response.getStatusCode() == HttpStatus.CONFLICT).hasSize(10);
        for (final String companyCode : List.of(COMPANY_CODE, OTHER_COMPANY_CODE)) {
            assertThat(quantityOf(companyCode, PRODUCT_CODE)).isZero();
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT sum(t.quantity) FROM stock_transaction t JOIN product p ON p.id = t.product_id
                    WHERE p.company_code = ? AND t.type = 'OUTBOUND'""", Long.class, companyCode)).isEqualTo(10);
        }
    }

    // 스레드를 모두 준비시킨 뒤 한 번에 출발시켜 요청이 실제로 겹치게 한다. i는 1부터
    private static List<ResponseEntity<String>> concurrently(
            final int count,
            final IntFunction<ResponseEntity<String>> request
    ) throws Exception {
        final CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(count)) {
            final List<Future<ResponseEntity<String>>> futures = IntStream.rangeClosed(1, count)
                    .mapToObj(i -> executor.submit(() -> {
                        start.await();
                        return request.apply(i);
                    }))
                    .toList();
            start.countDown();
            final List<ResponseEntity<String>> responses = new ArrayList<>();
            for (final Future<ResponseEntity<String>> future : futures) {
                responses.add(future.get());
            }
            return responses;
        }
    }
}
