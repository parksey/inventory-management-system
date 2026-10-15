package com.logistics.inventory.db.transaction;

import com.logistics.inventory.domain.transaction.entity.StockTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

interface StockTransactionJpaRepository extends JpaRepository<StockTransaction, Long> {
}
