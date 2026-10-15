package com.logistics.inventory.domain.transaction.port;

import com.logistics.inventory.domain.common.CursorQuery;
import com.logistics.inventory.domain.common.CursorResult;
import com.logistics.inventory.domain.transaction.entity.StockTransaction;

public interface StockTransactionStore {

    void save(StockTransaction transaction);

    CursorResult<StockTransaction> findHistoryBy(Long productId, CursorQuery cursorQuery);
}
