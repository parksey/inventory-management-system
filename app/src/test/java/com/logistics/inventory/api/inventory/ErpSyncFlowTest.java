package com.logistics.inventory.api.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.logistics.inventory.domain.erp.ErpSyncSender;
import com.logistics.inventory.domain.erp.ErpSyncService;
import com.logistics.inventory.domain.erp.model.ErpLookupResult;
import com.logistics.inventory.domain.erp.model.ErpSendResult;
import com.logistics.inventory.domain.erp.model.ErpStockMovement;
import com.logistics.inventory.domain.erp.port.ErpClient;
import com.logistics.inventory.domain.inventory.entity.StockChangeType;
import com.logistics.inventory.domain.outbox.OutboxRelayService;
import com.logistics.inventory.domain.outbox.OutboxRelayer;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.function.IntConsumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

// 외부 전달: outbox → 중계 → erp_sync → ERP 전송. 배치는 스케줄러를 끄고(test 프로필) 직접 불러 순서와 시점을 정한다
class ErpSyncFlowTest extends InventoryApiTestSupport {

    @Autowired
    private OutboxRelayer outboxRelayer;

    @Autowired
    private OutboxRelayService outboxRelayService;

    @Autowired
    private ErpSyncService erpSyncService;

    @Autowired
    private ErpSyncSender erpSyncSender;

    @Autowired
    private FakeErpClient erp;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void resetErp() {
        erp.reset();
    }

    @Test
    @DisplayName("TC-48-08 중계하면 PENDING 메시지마다 erp_sync가 PENDING으로 생기고 메시지는 PUBLISHED가 된다")
    void 중계() {
        inbound(PRODUCT_CODE, "worker-01", "{\"quantity\": 1}");
        outbound(PRODUCT_CODE, "worker-01", "{\"quantity\": 1}");

        outboxRelayer.relayDue("R");

        assertThat(countErpSyncs()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM erp_sync s
                JOIN outbox_message o ON o.id = s.outbox_message_id
                WHERE s.status = 'PENDING' AND s.next_retry_at IS NOT NULL
                  AND s.erp_idempotency_key = 'stx-' || o.aggregate_id
                  AND o.status = 'PUBLISHED' AND o.published_at IS NOT NULL""", Long.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("TC-48-09 중계를 다시 실행해도 erp_sync는 늘지 않는다")
    void 중계_재실행() {
        inbound(PRODUCT_CODE, "worker-01", "{\"quantity\": 1}");
        inbound(PRODUCT_CODE, "worker-01", "{\"quantity\": 1}");
        outboxRelayer.relayDue("R");

        outboxRelayer.relayDue("R");
        assertThat(countErpSyncs()).isEqualTo(2);
    }

    @Test
    @DisplayName("TC-48-10 중계 4개를 동시에 실행해도 메시지마다 erp_sync는 하나이고 모두 PUBLISHED가 된다")
    void 중계_동시_실행() throws Exception {
        for (int i = 0; i < 20; i++) {
            inbound(PRODUCT_CODE, "worker-01", "{\"quantity\": 1}");
        }

        runConcurrently(4, worker -> outboxRelayer.relayDue("relay-" + worker));
        outboxRelayer.relayDue("R");   // 동시 실행이 나눠 가져가고 남은 게 있으면 마저

        assertThat(countErpSyncs()).isEqualTo(20);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_message WHERE status = 'PUBLISHED'", Long.class)).isEqualTo(20);
    }

    @Test
    @DisplayName("TC-48-12 ERP가 받으면 SENT, 전표 번호, 선점 해제. ERP는 stx-<원장 id> 키와 outbox payload를 받는다")
    void 전송_성공() {
        inbound(PRODUCT_CODE, "worker-01", "{\"quantity\": 3}");
        outboxRelayer.relayDue("R");

        erpSyncSender.sendDue("A");

        final Map<String, Object> sync = singleSync();
        assertThat(sync.get("status")).isEqualTo("SENT");
        assertThat(sync.get("erp_document_id")).isEqualTo("DOC-" + sync.get("erp_idempotency_key"));
        assertThat(sync.get("sent_at")).isNotNull();
        assertThat(sync.get("locked_by")).isNull();

        final long transactionId = jdbcTemplate.queryForObject("SELECT id FROM stock_transaction", Long.class);
        assertThat(erp.received()).singleElement().satisfies(movement -> {
            assertThat(movement.idempotencyKey()).isEqualTo("stx-" + transactionId);
            assertThat(movement.payload().stockTransactionId()).isEqualTo(transactionId);
            assertThat(movement.payload().quantity()).isEqualTo(3);
            assertThat(movement.payload().afterQuantity()).isEqualTo(8);
        });
    }

    @Test
    @DisplayName("TC-48-13 ERP가 거절하면 FAILED이고 다시 보내지 않는다")
    void 전송_거절() {
        inbound(PRODUCT_CODE, "worker-01", "{\"quantity\": 1}");
        outboxRelayer.relayDue("R");
        erp.next(ErpSendResult.rejected("400 invalid product"));

        erpSyncSender.sendDue("A");
        makeDue();
        erpSyncSender.sendDue("A");

        final Map<String, Object> sync = singleSync();
        assertThat(sync.get("status")).isEqualTo("FAILED");
        assertThat(sync.get("failed_at")).isNotNull();
        assertThat(sync.get("last_error")).isEqualTo("400 invalid product");
        assertThat(erp.received()).hasSize(1);
    }

    @Test
    @DisplayName("TC-48-14 연결 실패면 PENDING으로 두고 1초 뒤에 다시 보낸다")
    void 연결_실패() {
        inbound(PRODUCT_CODE, "worker-01", "{\"quantity\": 1}");
        outboxRelayer.relayDue("R");
        erp.next(ErpSendResult.notSent("connection refused"));

        erpSyncSender.sendDue("A");

        final Map<String, Object> sync = singleSync();
        assertThat(sync.get("status")).isEqualTo("PENDING");
        assertThat(sync.get("attempts")).isEqualTo(1);
        assertThat(sync.get("locked_by")).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT next_retry_at BETWEEN now() + interval '500 milliseconds' AND now() + interval '2 seconds' FROM erp_sync",
                Boolean.class)).isTrue();
    }

    @Test
    @DisplayName("TC-48-15 결과를 모르면 UNKNOWN으로 두고 다시 보내지 않는다. 참조번호로 조회해 ERP에 있으면 SENT가 된다")
    void 결과_모름은_조회로_확인() {
        inbound(PRODUCT_CODE, "worker-01", "{\"quantity\": 1}");
        outboxRelayer.relayDue("R");
        erp.next(ErpSendResult.unknown("read timeout"));

        erpSyncSender.sendDue("A");
        assertThat(singleSync().get("status")).isEqualTo("UNKNOWN");
        makeDue();
        erpSyncSender.sendDue("A");
        assertThat(erp.received()).as("UNKNOWN은 전송 대상이 아니다").hasSize(1);

        erp.nextLookup(foundAsSent("DOC-FOUND"));
        erpSyncSender.checkUnknown("A");

        final Map<String, Object> sync = singleSync();
        assertThat(sync.get("status")).isEqualTo("SENT");
        assertThat(sync.get("erp_document_id")).isEqualTo("DOC-FOUND");
        assertThat(erp.received()).as("한 번만 보냈다").hasSize(1);
        assertThat(erp.lookedUp()).containsExactly(erp.received().getFirst().idempotencyKey());
    }

    @Test
    @DisplayName("TC-48-26 조회해서 ERP에 없으면 UNKNOWN 그대로 두고 다시 보내지 않는다. 다음 조회는 백오프 뒤에 한다")
    void 조회_결과_없음() {
        inbound(PRODUCT_CODE, "worker-01", "{\"quantity\": 1}");
        outboxRelayer.relayDue("R");
        erp.next(ErpSendResult.unknown("read timeout"));
        erpSyncSender.sendDue("A");
        makeDue();

        erp.nextLookup(ErpLookupResult.notFound());
        erpSyncSender.checkUnknown("A");

        final Map<String, Object> sync = singleSync();
        assertThat(sync.get("status")).isEqualTo("UNKNOWN");
        assertThat(sync.get("locked_by")).isNull();
        assertThat(sync.get("next_retry_at")).isNotNull();
        erpSyncSender.checkUnknown("A");
        erpSyncSender.sendDue("A");
        assertThat(erp.lookedUp()).as("백오프 시각 전에는 다시 조회하지 않는다").hasSize(1);
        assertThat(erp.received()).as("다시 보내지 않는다").hasSize(1);
    }

    @Test
    @DisplayName("TC-48-16 결과를 모르는 건은 정해 둔 간격만큼(5번) 조회해도 못 찾으면 자동 처리를 멈춘다. 다시 보내지 않는다")
    void 자동_처리_중단() {
        inbound(PRODUCT_CODE, "worker-01", "{\"quantity\": 1}");
        outboxRelayer.relayDue("R");
        erp.next(ErpSendResult.unknown("read timeout"));
        erpSyncSender.sendDue("A");

        for (int lookup = 1; lookup <= 5; lookup++) {
            makeDue();
            erpSyncSender.checkUnknown("A");
        }

        final Map<String, Object> sync = singleSync();
        assertThat(sync.get("status")).isEqualTo("UNKNOWN");
        assertThat(sync.get("lookups")).isEqualTo(5);
        assertThat(sync.get("next_retry_at")).isNull();
        assertThat(sync.get("failed_at")).isNotNull();
        makeDue();
        erpSyncSender.checkUnknown("A");
        assertThat(erp.lookedUp()).as("멈춘 뒤에는 조회하지 않는다").hasSize(5);
        assertThat(erp.received()).as("한 번만 보냈다").hasSize(1);
    }

    @Test
    @DisplayName("TC-48-17 전송 4개를 동시에 실행해도 한 건은 한 번만 보내고 모두 SENT가 된다")
    void 전송_동시_실행() throws Exception {
        for (int i = 0; i < 20; i++) {
            inbound(PRODUCT_CODE, "worker-01", "{\"quantity\": 1}");
        }
        outboxRelayer.relayDue("R");

        runConcurrently(4, worker -> erpSyncSender.sendDue("W" + worker));
        erpSyncSender.sendDue("W-last");   // 동시 실행이 나눠 가져가고 남은 게 있으면 마저

        assertThat(erp.received()).hasSize(20);
        assertThat(erp.received().stream().map(ErpStockMovement::idempotencyKey).distinct()).hasSize(20);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM erp_sync WHERE status = 'SENT'", Long.class)).isEqualTo(20);
    }

    @Test
    @DisplayName("TC-48-18 보내던 배치가 죽어 선점이 만료되면 다시 보내지 않고 UNKNOWN으로 넘겨 조회로 확인한다")
    void 선점_만료는_조회로() {
        inbound(PRODUCT_CODE, "worker-01", "{\"quantity\": 1}");
        outboxRelayer.relayDue("R");
        assertThat(erpSyncService.claimNext("A")).isPresent();   // A가 선점하고 보냈는지 모르는 채로 죽음
        assertThat(erpSyncService.markExpiredClaimsUnknown()).as("선점이 살아 있으면 건드리지 않는다").isZero();

        expireLock();
        assertThat(erpSyncService.markExpiredClaimsUnknown()).isEqualTo(1);

        Map<String, Object> sync = singleSync();
        assertThat(sync.get("status")).isEqualTo("UNKNOWN");
        assertThat(sync.get("locked_by")).isNull();
        erpSyncSender.sendDue("B");
        assertThat(erp.received()).as("다시 보내지 않는다").isEmpty();

        erp.nextLookup(foundAsSent("DOC-A"));
        erpSyncSender.checkUnknown("B");

        sync = singleSync();
        assertThat(sync.get("status")).isEqualTo("SENT");
        assertThat(erp.received()).isEmpty();
    }

    @Test
    @DisplayName("TC-48-19 선점이 만료돼 UNKNOWN으로 넘어간 뒤에는 앞 배치의 늦은 결과가 저장되지 않는다")
    void 늦은_결과_무시() {
        inbound(PRODUCT_CODE, "worker-01", "{\"quantity\": 1}");
        outboxRelayer.relayDue("R");
        final Long id = erpSyncService.claimNext("A").orElseThrow().erpSyncId();
        expireLock();
        erpSyncService.markExpiredClaimsUnknown();

        erpSyncService.record(id, "A", ErpSendResult.rejected("늦게 도착한 A의 결과"));

        final Map<String, Object> sync = singleSync();
        assertThat(sync.get("status")).isEqualTo("UNKNOWN");
        assertThat(sync.get("failed_at")).isNull();
    }

    @Test
    @DisplayName("TC-48-20 ERP 호출이 예외로 끝나도 입고는 정상 응답하고, 전달은 결과 모름(UNKNOWN)으로 남는다")
    void ERP_장애와_입고() {
        erp.always(movement -> {
            throw new IllegalStateException("ERP down");
        });

        final ResponseEntity<String> response = inbound(PRODUCT_CODE, "worker-01", "{\"quantity\": 3}");
        outboxRelayer.relayDue("R");
        erpSyncSender.sendDue("A");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(quantityOf(PRODUCT_CODE)).isEqualTo(8);
        final Map<String, Object> sync = singleSync();
        assertThat(sync.get("status")).isEqualTo("UNKNOWN");
        assertThat((String) sync.get("last_error")).contains("ERP down");
    }

    @Test
    @DisplayName("TC-48-23 보낸 뒤 결과 저장이 실패하면 ERP에 반영됐을 수 있으니 바로 UNKNOWN으로 두고, 나머지 건은 계속 보낸다")
    void 결과_저장_실패는_결과_모름() {
        inbound(PRODUCT_CODE, "worker-01", "{\"quantity\": 1}");
        inbound(PRODUCT_CODE, "worker-01", "{\"quantity\": 1}");
        outboxRelayer.relayDue("R");
        erp.next(new ErpSendResult(null, null, null));   // 결과를 저장할 수 없는 응답 (record가 NullPointerException으로 끝남)

        erpSyncSender.sendDue("A");

        final Long failedId = jdbcTemplate.queryForObject("SELECT id FROM erp_sync WHERE status <> 'SENT'", Long.class);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM erp_sync WHERE status = 'SENT'", Long.class)).isEqualTo(1);
        final Map<String, Object> failed = jdbcTemplate.queryForMap("SELECT * FROM erp_sync WHERE id = ?", failedId);
        assertThat(failed.get("status")).isEqualTo("UNKNOWN");
        assertThat(failed.get("next_retry_at")).as("백오프 뒤 조회").isNotNull();
        assertThat(failed.get("locked_by")).isNull();
        assertThat((String) failed.get("last_error")).startsWith("결과 저장 실패").contains("NullPointerException");

        makeDue();
        erpSyncSender.sendDue("B");
        assertThat(erp.received()).as("다시 보내지 않는다").hasSize(2);
    }

    @Test
    @DisplayName("TC-48-25 결과 저장도 UNKNOWN 표시도 못 하면(행 잠금 대기 초과) 선점이 남고, 만료 뒤 다시 보내지 않고 조회로 확인한다")
    void 기다리면_될_실패() throws Exception {
        inbound(PRODUCT_CODE, "worker-01", "{\"quantity\": 1}");
        outboxRelayer.relayDue("R");
        final Connection blocker = dataSource.getConnection();
        try {
            blocker.setAutoCommit(false);
            // ERP는 받았는데, 그 사이 다른 트랜잭션이 그 행을 잡고 놓지 않아 결과 저장과 UNKNOWN 표시가 모두 잠금 대기 상한(3초)을 넘긴다
            erp.always(movement -> {
                lockRows(blocker);
                return ErpSendResult.success("DOC-" + movement.idempotencyKey());
            });

            assertThatThrownBy(() -> erpSyncSender.sendDue("A")).as("스케줄러까지 올라가 로그로 남는다").isInstanceOf(RuntimeException.class);
        } finally {
            blocker.rollback();
            blocker.close();
        }
        assertThat(singleSync().get("locked_by")).as("선점이 남는다").isEqualTo("A");

        expireLock();
        erpSyncService.markExpiredClaimsUnknown();
        erpSyncSender.sendDue("B");
        assertThat(erp.received()).as("ERP가 받았을 수도 있으니 다시 보내지 않는다").hasSize(1);
        erp.nextLookup(foundAsSent("DOC-A"));
        erpSyncSender.checkUnknown("B");

        assertThat(singleSync().get("status")).isEqualTo("SENT");
        assertThat(erp.received()).as("한 번만 보냈다").hasSize(1);
    }

    @Test
    @DisplayName("TC-48-27 넘기지 못하는 메시지가 있어도 나머지는 넘기고, 실패한 건은 횟수와 원인을 남기고 백오프만큼 미룬다")
    void 중계_한_건_실패() {
        inbound(PRODUCT_CODE, "worker-01", "{\"quantity\": 1}");
        final long broken = insertBrokenMessage();

        outboxRelayer.relayDue("R");

        assertThat(countErpSyncs()).as("뒤의 정상 메시지는 넘어간다").isEqualTo(1);
        final Map<String, Object> row = outboxRow(broken);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat((String) row.get("last_error")).isNotBlank();
        assertThat(row.get("delayed")).as("백오프만큼 미뤘다").isEqualTo(true);
        assertThat(row.get("locked_by")).as("선점을 풀었다").isNull();
        outboxRelayer.relayDue("R");
        assertThat(outboxRow(broken).get("attempts")).as("백오프 전에는 다시 가져가지 않는다").isEqualTo(1);
    }

    @Test
    @DisplayName("TC-48-28 중계가 5번 실패하면 사람 확인(REQUIRES_REVIEW)으로 멈추고 더 가져가지 않는다")
    void 중계_5번_실패() {
        final long broken = insertBrokenMessage();
        for (int attempt = 1; attempt <= 4; attempt++) {
            outboxRelayer.relayDue("R");
            makeOutboxDue();
        }
        assertThat(outboxRow(broken).get("status")).isEqualTo("PENDING");
        assertThat(outboxRow(broken).get("attempts")).isEqualTo(4);

        outboxRelayer.relayDue("R");

        assertThat(outboxRow(broken).get("status")).isEqualTo("REQUIRES_REVIEW");
        assertThat(outboxRow(broken).get("attempts")).isEqualTo(5);
        makeOutboxDue();
        outboxRelayer.relayDue("R");
        assertThat(outboxRow(broken).get("attempts")).as("사람 확인 대상은 다시 가져가지 않는다").isEqualTo(5);
        assertThat(countErpSyncs()).isZero();
    }

    @Test
    @DisplayName("TC-48-30 선점한 중계가 죽으면 30초 동안 다른 중계는 건너뛰고, 만료 뒤에 가져가 넘긴다. 죽었던 중계의 늦은 처리는 무시된다")
    void 중계_선점_만료() {
        inbound(PRODUCT_CODE, "worker-01", "{\"quantity\": 1}");
        final Long id = outboxRelayService.claimDue("A").getFirst();   // A가 선점하고 넘기기 전에 죽음

        outboxRelayer.relayDue("B");
        assertThat(countErpSyncs()).as("선점 중에는 건너뛴다").isZero();

        jdbcTemplate.update("UPDATE outbox_message SET locked_at = now() - interval '31 seconds' WHERE locked_at IS NOT NULL");
        outboxRelayer.relayDue("B");
        assertThat(countErpSyncs()).as("만료 뒤 B가 넘긴다").isEqualTo(1);
        assertThat(outboxRelayService.relay(id, "A")).as("A의 늦은 처리는 무시").isFalse();
        assertThat(countErpSyncs()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap("SELECT status, locked_by FROM outbox_message WHERE id = ?", id))
            .containsEntry("status", "PUBLISHED").containsEntry("locked_by", null);
    }

    @Test
    @DisplayName("TC-48-32 조회해서 찾았는데 ERP에 들어간 수량이 보낸 것과 다르면 SENT로 두지 않고 사람 확인으로 멈춘다")
    void 조회_내용_불일치() {
        inbound(PRODUCT_CODE, "worker-01", "{\"quantity\": 3}");
        outboxRelayer.relayDue("R");
        erp.next(ErpSendResult.unknown("read timeout"));
        erpSyncSender.sendDue("A");
        makeDue();
        final ErpLookupResult.Recorded sent = recordedAsSent();
        erp.nextLookup(ErpLookupResult.found("DOC-X",
            new ErpLookupResult.Recorded(sent.companyCode(), sent.productCode(), sent.type(), sent.quantity() + 1)));

        erpSyncSender.checkUnknown("A");

        final Map<String, Object> sync = singleSync();
        assertThat(sync.get("status")).as("SENT로 두지 않는다").isEqualTo("UNKNOWN");
        assertThat(sync.get("erp_document_id")).isEqualTo("DOC-X");
        assertThat(sync.get("next_retry_at")).as("자동 처리 중단").isNull();
        assertThat(sync.get("failed_at")).isNotNull();
        assertThat((String) sync.get("last_error")).contains("불일치");
        makeDue();
        erpSyncSender.sendDue("A");
        erpSyncSender.checkUnknown("A");
        assertThat(erp.received()).as("다시 보내지 않는다").hasSize(1);
        assertThat(erp.lookedUp()).as("다시 조회하지 않는다").hasSize(1);
    }

    @Test
    @DisplayName("TC-48-33 결과를 모르면 첫 조회는 정해 둔 첫 간격(10초) 뒤이고, 그 시각은 DB 시계로 정한다")
    void 첫_조회_간격() {
        inbound(PRODUCT_CODE, "worker-01", "{\"quantity\": 1}");
        outboxRelayer.relayDue("R");
        erp.next(ErpSendResult.unknown("read timeout"));

        erpSyncSender.sendDue("A");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT next_retry_at BETWEEN now() + interval '9 seconds' AND now() + interval '11 seconds' FROM erp_sync",
                Boolean.class)).isTrue();
        erpSyncSender.checkUnknown("A");
        assertThat(erp.lookedUp()).as("간격 전에는 조회하지 않는다").isEmpty();
    }

    // ERP에 우리가 보낸 내용 그대로 들어가 있다
    private ErpLookupResult foundAsSent(final String documentId) {
        return ErpLookupResult.found(documentId, recordedAsSent());
    }

    private ErpLookupResult.Recorded recordedAsSent() {
        return jdbcTemplate.queryForObject("""
                SELECT payload->>'companyCode' AS company, payload->>'productCode' AS product,
                       payload->>'type' AS type, (payload->>'quantity')::bigint AS quantity
                FROM outbox_message""",
                (rs, row) -> new ErpLookupResult.Recorded(
                        rs.getString("company"), rs.getString("product"),
                        StockChangeType.valueOf(rs.getString("type")), rs.getLong("quantity")));
    }

    // 넘길 때 실패하는 메시지: 참조번호(stx- + 64자)가 erp_sync 키 길이(64)를 넘는다. 정상 메시지보다 오래된 것으로 넣는다
    private long insertBrokenMessage() {
        return jdbcTemplate.queryForObject("""
                INSERT INTO outbox_message (company_code, aggregate_type, aggregate_id, event_type, payload, available_at)
                VALUES ('COMPANY-A', 'STOCK_TRANSACTION', repeat('9', 64), 'STOCK_CHANGED', '{}', now() - interval '1 minute')
                RETURNING id""", Long.class);
    }

    private Map<String, Object> outboxRow(final long id) {
        return jdbcTemplate.queryForMap(
                "SELECT status, attempts, last_error, locked_by, available_at > now() AS delayed FROM outbox_message WHERE id = ?", id);
    }

    // 중계 백오프를 기다리지 않고 다시 넘길 차례로 만든다
    private void makeOutboxDue() {
        jdbcTemplate.update("UPDATE outbox_message SET available_at = now() - interval '1 second' WHERE status = 'PENDING'");
    }

    // 다른 연결이 erp_sync 행을 잠가 두고 놓지 않는다
    private static void lockRows(final Connection blocker) {
        try (Statement statement = blocker.createStatement()) {
            statement.execute("SELECT id FROM erp_sync FOR UPDATE");
        } catch (final SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private long countErpSyncs() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM erp_sync", Long.class);
    }

    private Map<String, Object> singleSync() {
        return jdbcTemplate.queryForMap("SELECT * FROM erp_sync");
    }

    // 백오프를 기다리지 않고 재시도 시각을 지난 것으로 만든다
    private void makeDue() {
        jdbcTemplate.update("UPDATE erp_sync SET next_retry_at = now() - interval '1 second' WHERE next_retry_at IS NOT NULL");
    }

    // 선점한 배치가 죽고 60초가 지난 것으로 만든다
    private void expireLock() {
        jdbcTemplate.update("UPDATE erp_sync SET locked_at = now() - interval '61 seconds' WHERE locked_at IS NOT NULL");
    }

    private static void runConcurrently(final int threads, final IntConsumer task) throws Exception {
        final ExecutorService executor = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        try {
            final List<Future<Void>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final int worker = i;
                final Callable<Void> call = () -> {
                    start.await();
                    task.accept(worker);
                    return null;
                };
                futures.add(executor.submit(call));
            }
            start.countDown();
            for (final Future<Void> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    // 결과를 정해 줄 수 있는 ERP. 받은 요청을 순서대로 기억한다
    static class FakeErpClient implements ErpClient {

        private final Queue<ErpSendResult> scripted = new ConcurrentLinkedQueue<>();
        private final List<ErpStockMovement> received = Collections.synchronizedList(new ArrayList<>());
        private final Queue<ErpLookupResult> scriptedLookups = new ConcurrentLinkedQueue<>();
        private final List<String> lookedUp = Collections.synchronizedList(new ArrayList<>());
        private volatile Function<ErpStockMovement, ErpSendResult> fallback = FakeErpClient::accept;

        @Override
        public ErpSendResult send(final ErpStockMovement movement) {
            received.add(movement);
            final ErpSendResult next = scripted.poll();
            return next != null ? next : fallback.apply(movement);
        }

        @Override
        public ErpLookupResult find(final String idempotencyKey) {
            lookedUp.add(idempotencyKey);
            final ErpLookupResult next = scriptedLookups.poll();
            return next != null ? next : ErpLookupResult.notFound();
        }

        void nextLookup(final ErpLookupResult result) {
            scriptedLookups.add(result);
        }

        List<String> lookedUp() {
            return List.copyOf(lookedUp);
        }

        void next(final ErpSendResult result) {
            scripted.add(result);
        }

        void always(final Function<ErpStockMovement, ErpSendResult> behavior) {
            fallback = behavior;
        }

        List<ErpStockMovement> received() {
            return List.copyOf(received);
        }

        void reset() {
            scripted.clear();
            received.clear();
            scriptedLookups.clear();
            lookedUp.clear();
            fallback = FakeErpClient::accept;
        }

        private static ErpSendResult accept(final ErpStockMovement movement) {
            return ErpSendResult.success("DOC-" + movement.idempotencyKey());
        }
    }

    @TestConfiguration
    static class FakeErpConfig {

        @Bean
        @Primary
        FakeErpClient fakeErpClient() {
            return new FakeErpClient();
        }
    }
}
