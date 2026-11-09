package com.logistics.inventory.db.outbox;

import com.logistics.inventory.domain.outbox.entity.OutboxMessage;
import com.logistics.inventory.domain.outbox.port.OutboxStore;
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
}
