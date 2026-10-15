package com.logistics.inventory.db.inventory;

import com.logistics.inventory.domain.inventory.model.StockResult;
import com.logistics.inventory.domain.inventory.port.InventoryStore;
import com.logistics.inventory.domain.product.entity.ProductCode;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class InventoryStoreAdapter implements InventoryStore {

    private final InventoryJpaRepository inventoryJpaRepository;

    @Override
    public Optional<StockResult> findStockByProductCode(final ProductCode productCode) {
        return inventoryJpaRepository.findStockByProductCode(productCode.value());
    }
}
