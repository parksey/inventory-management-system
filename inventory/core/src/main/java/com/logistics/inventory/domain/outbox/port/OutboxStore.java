package com.logistics.inventory.domain.outbox.port;

import com.logistics.inventory.domain.outbox.entity.OutboxMessage;

public interface OutboxStore {

    void save(OutboxMessage message);
}
