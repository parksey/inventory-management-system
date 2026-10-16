package com.logistics.inventory.api.inventory;

import static com.logistics.inventory.api.common.TraceIdFilter.TRACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.util.concurrent.CompletableFuture;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

// 지표는 테스트끼리 같은 컨텍스트를 공유해 앞 테스트 값이 남아 있으므로, 요청 전후 차이로 검증한다
@ExtendWith(OutputCaptureExtension.class)
class InventoryMonitoringTest extends InventoryApiTestSupport {

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("TC-22-02 관리 포트의 liveness가 200, UP으로 응답한다")
    void liveness() {
        final ResponseEntity<String> response = management().get().uri("/actuator/health/liveness").retrieve().toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    @DisplayName("TC-22-03 관리 포트의 readiness가 200, UP으로 응답한다")
    void readiness() {
        final ResponseEntity<String> response = management().get().uri("/actuator/health/readiness").retrieve().toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    @DisplayName("TC-22-04 서비스 포트에는 actuator가 없다")
    void 서비스_포트_actuator_없음() {
        final ResponseEntity<String> response = client().get().uri("/api/actuator/health").retrieve().toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("TC-22-05 입고 뒤 관리 포트의 /actuator/prometheus에 재고 변경 건수와 요청 시간 구간이 나온다")
    void prometheus_노출() {
        inbound(PRODUCT_CODE, "user-1", "{\"quantity\": 3}");

        final String body = management().get().uri("/actuator/prometheus").retrieve().body(String.class);

        assertThat(body)
                .contains("inventory_stock_changes_total{type=\"INBOUND\"}")
                .contains("http_server_requests_seconds_bucket");
    }

    @Test
    @DisplayName("TC-22-06 입고하면 INBOUND 변경 건수가 1, 수량 합계가 입고 수량만큼 늘고 재고 변경 로그가 남는다")
    void 입고_지표(final CapturedOutput output) {
        final double countBefore = changes("INBOUND");
        final double amountBefore = quantityTotal("INBOUND");

        inbound(PRODUCT_CODE, "user-1", "{\"quantity\": 3}");

        assertThat(changes("INBOUND")).isEqualTo(countBefore + 1);
        assertThat(quantityTotal("INBOUND")).isEqualTo(amountBefore + 3);
        assertThat(output).contains("재고 변경: type=INBOUND, productId=1, amount=3, before=5, after=8");
    }

    @Test
    @DisplayName("TC-22-07 출고하면 OUTBOUND 변경 건수가 1, 수량 합계가 출고 수량만큼 는다")
    void 출고_지표() {
        final double countBefore = changes("OUTBOUND");
        final double amountBefore = quantityTotal("OUTBOUND");

        outbound(PRODUCT_CODE, "user-1", "{\"quantity\": 2}");

        assertThat(changes("OUTBOUND")).isEqualTo(countBefore + 1);
        assertThat(quantityTotal("OUTBOUND")).isEqualTo(amountBefore + 2);
    }

    @Test
    @DisplayName("TC-22-08 같은 멱등성 키로 다시 보낸 입고는 변경 건수를 늘리지 않는다")
    void 재요청_미집계() {
        final String key = newIdempotencyKey();
        inbound(PRODUCT_CODE, "user-1", key, "{\"quantity\": 3}");
        final double countBefore = changes("INBOUND");

        final ResponseEntity<String> retry = inbound(PRODUCT_CODE, "user-1", key, "{\"quantity\": 3}");

        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(changes("INBOUND")).isEqualTo(countBefore);
    }

    @Test
    @DisplayName("TC-22-09 재고보다 많이 출고하면 INSUFFICIENT_STOCK 거절 건수가 1 늘고 OUTBOUND 변경 건수는 그대로다")
    void 재고_부족_지표() {
        final double rejectedBefore = rejected("INSUFFICIENT_STOCK");
        final double changesBefore = changes("OUTBOUND");

        outbound(PRODUCT_CODE, "user-1", "{\"quantity\": 6}");

        assertThat(rejected("INSUFFICIENT_STOCK")).isEqualTo(rejectedBefore + 1);
        assertThat(changes("OUTBOUND")).isEqualTo(changesBefore);
    }

    @Test
    @DisplayName("TC-22-10 행 잠금을 기준 시간보다 오래 기다린 쿼리는 그 요청의 traceId와 함께 슬로 쿼리 로그로 남는다")
    void 슬로_쿼리_로그(final CapturedOutput output) throws Exception {
        // 다른 트랜잭션이 재고 행을 1.5초 잡고 있다가 커밋한다 (기준 1000ms)
        final Connection holder = dataSource.getConnection();
        holder.setAutoCommit(false);
        holder.createStatement().execute("SELECT * FROM inventory FOR UPDATE");
        final CompletableFuture<Void> release = CompletableFuture.runAsync(() -> {
            try (holder) {
                Thread.sleep(1500);
                holder.commit();
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        });

        final ResponseEntity<String> response = client().post()
                .uri("/api/v1/inventory/{code}/outbound", PRODUCT_CODE)
                .header("X-User-Id", "user-1")
                .header("Idempotency-Key", newIdempotencyKey())
                .header(TRACE_HEADER, "trace-22-10")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"quantity\": 1}")
                .retrieve().toEntity(String.class);
        release.join();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // 로그 형식(평문, JSON)은 같은 JVM에서 먼저 뜬 컨텍스트의 프로파일을 따르므로 형식과 무관하게 내용만 본다
        assertThat(output.getOut().lines().filter(line -> line.contains("trace-22-10")))
                .anySatisfy(line -> assertThat(line).contains("Slow query took").contains("for no key update"));
    }

    private RestClient management() {
        return RestClient.builder()
                .baseUrl("http://localhost:" + managementPort)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { })
                .build();
    }

    private double changes(final String type) {
        final Counter counter = registry.find("inventory.stock.changes").tag("type", type).counter();
        return counter == null ? 0 : counter.count();
    }

    private double quantityTotal(final String type) {
        final DistributionSummary summary = registry.find("inventory.stock.quantity").tag("type", type).summary();
        return summary == null ? 0 : summary.totalAmount();
    }

    private double rejected(final String code) {
        final Counter counter = registry.find("inventory.business.error").tag("code", code).counter();
        return counter == null ? 0 : counter.count();
    }
}
