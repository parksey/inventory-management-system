package com.logistics.inventory.domain.outbox;

import com.logistics.inventory.domain.erp.entity.ErpSync;
import com.logistics.inventory.domain.erp.port.ErpSyncStore;
import com.logistics.inventory.domain.outbox.entity.OutboxMessage;
import com.logistics.inventory.domain.outbox.entity.OutboxStatus;
import com.logistics.inventory.domain.outbox.port.OutboxStore;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OutboxRelayService {

    private static final int BATCH_SIZE = 20;

    static final Duration LOCK_TIMEOUT = Duration.ofSeconds(30);

    private final OutboxStore outboxStore;
    private final ErpSyncStore erpSyncStore;

    @Transactional
    public List<Long> claimDue(final String workerId) {
        final List<Long> ids = outboxStore.findDueIdsForUpdate(BATCH_SIZE, LOCK_TIMEOUT);
        if (!ids.isEmpty()) {
            outboxStore.markClaimed(ids, workerId);
        }
        return ids;
    }

    @Transactional
    public boolean relay(final Long outboxMessageId, final String workerId) {
        final OutboxMessage message = outboxStore.findByIdForUpdate(outboxMessageId)
            .orElseThrow(() -> new IllegalStateException("outbox 메시지가 없습니다. id=" + outboxMessageId));
        if (!message.isClaimedBy(workerId)) {
            return false;
        }
        erpSyncStore.createIfAbsent(message.getId(), ErpSync.idempotencyKeyOf(message.getAggregateId()));
        message.markPublished();
        return true;
    }

    @Transactional
    public OutboxStatus recordFailure(final Long outboxMessageId, final String workerId, final String error) {
        final OutboxMessage message = outboxStore.findByIdForUpdate(outboxMessageId)
            .orElseThrow(() -> new IllegalStateException("outbox 메시지가 없습니다. id=" + outboxMessageId));
        if (!message.isClaimedBy(workerId)) {
            return message.getStatus();
        }
        message.recordRelayFailure(error).ifPresent(backoff -> outboxStore.delay(outboxMessageId, backoff));
        return message.getStatus();
    }
}
