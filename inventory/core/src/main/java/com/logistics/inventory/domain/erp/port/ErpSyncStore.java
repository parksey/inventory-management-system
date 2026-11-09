package com.logistics.inventory.domain.erp.port;

import com.logistics.inventory.domain.erp.entity.ErpSync;
import com.logistics.inventory.domain.erp.model.ErpDelivery;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface ErpSyncStore {

    void createIfAbsent(Long outboxMessageId, String idempotencyKey);

    List<Long> findDueIdsForUpdate(int limit);

    List<Long> findCheckDueIdsForUpdate(int limit, Duration lockTimeout);

    int markExpiredClaimsUnknown(Duration lockTimeout);

    void markClaimed(List<Long> ids, String workerId);

    List<ErpDelivery> findDeliveries(List<Long> ids);

    Optional<ErpSync> findByIdForUpdate(Long id);

    OffsetDateTime currentTime();
}
