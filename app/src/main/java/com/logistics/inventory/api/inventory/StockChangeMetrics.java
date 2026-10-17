package com.logistics.inventory.api.inventory;

import com.logistics.inventory.domain.inventory.entity.StockChange;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class StockChangeMetrics {

    private final MeterRegistry registry;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(final StockChange change) {
        final String type = change.type().name();
        registry.counter("inventory.stock.changes", "type", type).increment();
        DistributionSummary.builder("inventory.stock.quantity")
                .tag("type", type)
                .register(registry)
                .record(change.amount().value());

        log.info("재고 변경: type={}, productId={}, amount={}, before={}, after={}",
                type, change.productId(), change.amount().value(), change.before().value(), change.after().value());
    }
}
