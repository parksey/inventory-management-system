package com.logistics.inventory.domain.inventory.port;

import com.logistics.inventory.domain.inventory.model.StockResult;
import com.logistics.inventory.domain.product.entity.ProductCode;
import java.util.Optional;

public interface InventoryStore {

    Optional<StockResult> findStockByProductCode(ProductCode productCode);
}
