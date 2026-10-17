package com.logistics.inventory.db.transaction;

import com.logistics.inventory.domain.transaction.entity.StockTransaction;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface StockTransactionJpaRepository extends JpaRepository<StockTransaction, Long> {

    @Query("""
            select t from StockTransaction t
            where t.productId = :productId
              and (:cursor is null or t.id < :cursor)
            order by t.id desc
            """)
    List<StockTransaction> findHistoryBy(@Param("productId") Long productId, @Param("cursor") Long cursor, Limit limit);
}
