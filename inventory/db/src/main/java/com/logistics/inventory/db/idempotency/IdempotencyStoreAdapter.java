package com.logistics.inventory.db.idempotency;

import com.logistics.inventory.domain.idempotency.entity.IdempotencyRecord;
import com.logistics.inventory.domain.idempotency.entity.IdempotencyRecordId;
import com.logistics.inventory.domain.idempotency.port.IdempotencyStore;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class IdempotencyStoreAdapter implements IdempotencyStore {

    private static final int INSERTED = 1;

    private final IdempotencyRecordJpaRepository idempotencyRecordJpaRepository;

    @Override
    public Optional<IdempotencyRecord> saveOrGetProcessed(final IdempotencyRecord record) {
        final String companyCode = record.getCompanyCode().value();
        final String idempotencyKey = record.getIdempotencyKey().value();
        final int inserted = idempotencyRecordJpaRepository.insertIfAbsent(
            companyCode,
            idempotencyKey,
            record.getType().name(),
            record.getProductCode().value(),
            record.getQuantity().value(),
            record.getRequesterId().value()
        );

        if (inserted == INSERTED) {
            return Optional.empty();
        }
        return Optional.of(idempotencyRecordJpaRepository.findById(new IdempotencyRecordId(companyCode, idempotencyKey))
            .orElseThrow(() -> new IllegalStateException(
                "키 저장이 충돌했는데 기록이 없습니다. companyCode=" + companyCode + ", idempotencyKey=" + idempotencyKey)));
    }
}
