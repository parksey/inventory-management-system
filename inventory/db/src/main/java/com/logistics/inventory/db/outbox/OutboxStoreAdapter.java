package com.logistics.inventory.db.outbox;

import com.logistics.inventory.domain.outbox.entity.OutboxMessage;
import com.logistics.inventory.domain.outbox.port.OutboxStore;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class OutboxStoreAdapter implements OutboxStore {

    private final OutboxMessageJpaRepository outboxMessageJpaRepository;

    @Override
    public void save(final OutboxMessage message) {
        outboxMessageJpaRepository.save(message);
    }

    @Override
    public List<Long> findDueIdsForUpdate(final int limit, final Duration lockTimeout) {
        return outboxMessageJpaRepository.findDueIdsForUpdate(limit, lockTimeout.toSeconds());
    }

    @Override
    public void markClaimed(final List<Long> ids, final String workerId) {
        outboxMessageJpaRepository.markClaimed(ids, workerId);
    }

    @Override
    public Optional<OutboxMessage> findByIdForUpdate(final Long id) {
        return outboxMessageJpaRepository.findByIdForUpdate(id);
    }

    @Override
    public void delay(final Long id, final Duration backoff) {
        outboxMessageJpaRepository.delay(id, backoff.toMillis() / 1000.0);
    }
}
