package com.logistics.inventory.db.inventory;

import com.logistics.inventory.domain.inventory.model.StockResult;
import com.logistics.inventory.domain.inventory.entity.Inventory;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface InventoryJpaRepository extends JpaRepository<Inventory, Long> {

    @Query("""
            select new com.logistics.inventory.domain.inventory.model.StockResult(p.productCode.value, p.name, i.quantity.value)
            from Product p
            join Inventory i on i.productId = p.id
            where p.productCode.value = :productCode
            """)
    Optional<StockResult> findStockByProductCode(@Param("productCode") String productCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Inventory i where i.productId = :productId")
    Optional<Inventory> findByProductIdForUpdate(@Param("productId") Long productId);
}
