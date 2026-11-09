package com.logistics.inventory.api.workflow;

import com.logistics.inventory.domain.erp.ErpSyncSender;
import com.logistics.inventory.domain.erp.ErpSyncService;
import com.logistics.inventory.domain.outbox.OutboxRelayer;
import java.lang.management.ManagementFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Slf4j
@Configuration
@EnableScheduling
@RequiredArgsConstructor
public class WorkflowScheduler {

    // 어느 Pod가 선점했는지 (pid@호스트명)
    private static final String WORKER_ID = ManagementFactory.getRuntimeMXBean().getName();

    private final OutboxRelayer outboxRelayer;
    private final ErpSyncSender erpSyncSender;
    private final ErpSyncService erpSyncService;

    // 중계: outbox의 새 메시지를 20건씩 선점해 한 건씩 erp_sync로 넘긴다
    @Scheduled(initialDelayString = "${inventory.outbox.initial-delay:0s}", fixedDelayString = "${inventory.outbox.interval:1s}")
    public void relay() {
        try {
            outboxRelayer.relayDue(WORKER_ID);
        } catch (final RuntimeException e) {
            log.error("outbox 중계 실패", e);
        }
    }

    // ERP 전송과 확인: 한 건씩 선점해 없을 때까지 반복한다
    @Scheduled(initialDelayString = "${inventory.outbox.initial-delay:0s}", fixedDelayString = "${inventory.outbox.interval:1s}")
    public void deliverToErp() {
        try {
            erpSyncSender.sendDue(WORKER_ID);
            erpSyncSender.checkUnknown(WORKER_ID);
        } catch (final RuntimeException e) {
            log.error("ERP 전달 배치 실패", e);
        }
    }

    // 복구: 보내던 배치가 죽어 선점이 만료된 건을 UNKNOWN으로 넘겨 조회로 확인하게 한다. 60초가 지나야 대상이라 드물게 돈다
    @Scheduled(initialDelayString = "${inventory.outbox.initial-delay:0s}", fixedDelayString = "${inventory.outbox.recovery-interval:10s}")
    public void recoverExpiredClaims() {
        try {
            final int recovered = erpSyncService.markExpiredClaimsUnknown();
            if (recovered > 0) {
                log.warn("선점이 만료된 ERP 전달 {}건을 UNKNOWN으로 넘김 (보내던 배치가 결과를 남기지 못함)", recovered);
            }
        } catch (final RuntimeException e) {
            log.error("선점 만료 정리 실패", e);
        }
    }
}
