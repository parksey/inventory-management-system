package com.logistics.inventory.domain.outbox.port;

import com.logistics.inventory.domain.outbox.entity.OutboxMessage;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

public interface OutboxStore {

    void save(OutboxMessage message);

    List<Long> findDueIdsForUpdate(int limit, Duration lockTimeout);

    void markClaimed(List<Long> ids, String workerId);

    Optional<OutboxMessage> findByIdForUpdate(Long id);

    void delay(Long id, Duration backoff);
}
