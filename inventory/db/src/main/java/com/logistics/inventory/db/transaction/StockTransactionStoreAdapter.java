package com.logistics.inventory.db.transaction;

import com.logistics.inventory.domain.transaction.entity.StockTransaction;
import com.logistics.inventory.domain.transaction.port.StockTransactionStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class StockTransactionStoreAdapter implements StockTransactionStore {

    private final StockTransactionJpaRepository stockTransactionJpaRepository;

    @Override
    public void save(final StockTransaction transaction) {
        stockTransactionJpaRepository.save(transaction);
    }
}
