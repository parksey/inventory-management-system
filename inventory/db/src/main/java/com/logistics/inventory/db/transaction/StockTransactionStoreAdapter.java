package com.logistics.inventory.db.transaction;

import com.logistics.inventory.domain.common.CursorQuery;
import com.logistics.inventory.domain.common.CursorResult;
import com.logistics.inventory.domain.transaction.entity.StockTransaction;
import com.logistics.inventory.domain.transaction.port.StockTransactionStore;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class StockTransactionStoreAdapter implements StockTransactionStore {

    private final StockTransactionJpaRepository stockTransactionJpaRepository;

    @Override
    public void save(final StockTransaction transaction) {
        stockTransactionJpaRepository.save(transaction);
    }

    @Override
    public CursorResult<StockTransaction> findHistoryBy(final Long productId, final CursorQuery cursorQuery) {
        final int size = cursorQuery.size();
        final Limit limit = Limit.of(size + 1);
        final List<StockTransaction> histories = stockTransactionJpaRepository.findHistoryBy(productId, cursorQuery.cursor(), limit);

        final boolean hasNext = histories.size() > size;
        final List<StockTransaction> items = hasNext ? histories.subList(0, size) : histories;
        final Long nextCursor = hasNext ? items.getLast().getId() : null;
        return new CursorResult<>(items, size, hasNext, nextCursor);
    }
}
