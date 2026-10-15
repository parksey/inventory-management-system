package com.logistics.inventory.domain.transaction.port;

import com.logistics.inventory.domain.transaction.entity.StockTransaction;

public interface StockTransactionStore {

    void save(StockTransaction transaction);
}
