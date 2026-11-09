package com.logistics.inventory.db.erp;

import com.logistics.inventory.domain.erp.entity.ErpSync;
import com.logistics.inventory.domain.erp.model.ErpDelivery;
import com.logistics.inventory.domain.erp.port.ErpSyncStore;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class ErpSyncStoreAdapter implements ErpSyncStore {

    private final ErpSyncJpaRepository erpSyncJpaRepository;

    @Override
    public void createIfAbsent(final Long outboxMessageId, final String idempotencyKey) {
        erpSyncJpaRepository.createIfAbsent(outboxMessageId, idempotencyKey);
    }

    @Override
    public List<Long> findDueIdsForUpdate(final int limit) {
        return erpSyncJpaRepository.findDueIdsForUpdate(limit);
    }

    @Override
    public List<Long> findCheckDueIdsForUpdate(final int limit, final Duration lockTimeout) {
        return erpSyncJpaRepository.findCheckDueIdsForUpdate(limit, lockTimeout.toSeconds());
    }

    @Override
    public int markExpiredClaimsUnknown(final Duration lockTimeout) {
        return erpSyncJpaRepository.markExpiredClaimsUnknown(lockTimeout.toSeconds());
    }

    @Override
    public void markClaimed(final List<Long> ids, final String workerId) {
        erpSyncJpaRepository.markClaimed(ids, workerId);
    }

    @Override
    public List<ErpDelivery> findDeliveries(final List<Long> ids) {
        return erpSyncJpaRepository.findDeliveries(ids);
    }

    @Override
    public Optional<ErpSync> findByIdForUpdate(final Long id) {
        return erpSyncJpaRepository.findByIdForUpdate(id);
    }

    @Override
    public OffsetDateTime currentTime() {
        return erpSyncJpaRepository.currentTime().atOffset(ZoneOffset.UTC);
    }
}
