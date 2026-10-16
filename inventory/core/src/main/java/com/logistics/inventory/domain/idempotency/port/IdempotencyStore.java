package com.logistics.inventory.domain.idempotency.port;

import com.logistics.inventory.domain.idempotency.entity.IdempotencyRecord;
import java.util.Optional;

public interface IdempotencyStore {

    Optional<IdempotencyRecord> saveOrGetProcessed(IdempotencyRecord record);
}
