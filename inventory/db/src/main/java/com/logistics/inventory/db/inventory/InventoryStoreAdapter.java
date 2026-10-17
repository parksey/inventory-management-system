package com.logistics.inventory.db.inventory;

import com.logistics.inventory.domain.inventory.entity.Inventory;
import com.logistics.inventory.domain.inventory.model.StockResult;
import com.logistics.inventory.domain.inventory.port.InventoryStore;
import com.logistics.inventory.domain.product.entity.ProductCode;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class InventoryStoreAdapter implements InventoryStore {

    private final InventoryJpaRepository inventoryJpaRepository;
    private final EntityManager entityManager;

    @Override
    public Optional<StockResult> findStockByProductCode(final ProductCode productCode) {
        return inventoryJpaRepository.findStockByProductCode(productCode.value());
    }

    @Override
    public Optional<Inventory> findByProductIdForUpdate(final Long productId) {
        return inventoryJpaRepository.findByProductIdForUpdate(productId);
    }

    // id(productId)를 직접 넣는 엔티티라 JpaRepository.save는 merge로 동작한다 (SELECT가 한 번 더 나가고, 넘긴 객체가 영속 상태가 되지 않는다)
    @Override
    public void save(final Inventory inventory) {
        entityManager.persist(inventory);
    }
}
