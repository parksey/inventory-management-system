package com.logistics.inventory.api.erp;

import com.logistics.inventory.domain.erp.model.ErpStockMovementOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// 입출고를 ERP로 보낸 결과를 커밋 뒤에 센다.
@Slf4j
@Component
@RequiredArgsConstructor
public class ErpStockMovementMetrics {

    private static final Set<String> NEEDS_REVIEW = Set.of("REJECTED", "MISMATCH", "STOPPED");

    private final MeterRegistry registry;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(final ErpStockMovementOutcome outcome) {
        registry.counter("inventory.erp.stock-movement", "outcome", outcome.outcome()).increment();

        if (NEEDS_REVIEW.contains(outcome.outcome())) {
            log.warn("ERP 전달 사람 확인 필요: outcome={}, erpSyncId={}, outboxMessageId={}, attempts={}, lastError={}",
                    outcome.outcome(), outcome.erpSyncId(), outcome.outboxMessageId(), outcome.attempts(), outcome.lastError());
        }
    }
}
