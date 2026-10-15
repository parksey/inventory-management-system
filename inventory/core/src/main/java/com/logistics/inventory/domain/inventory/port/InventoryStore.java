package com.logistics.inventory.domain.inventory.port;

import com.logistics.inventory.domain.inventory.entity.Inventory;
import com.logistics.inventory.domain.inventory.model.StockResult;
import com.logistics.inventory.domain.product.entity.ProductCode;
import java.util.Optional;

public interface InventoryStore {

    Optional<StockResult> findStockByProductCode(ProductCode productCode);

    /** 재고 변경용 조회. 커밋까지 다른 트랜잭션의 변경을 막는 행 잠금을 건다. */
    Optional<Inventory> findByProductIdForUpdate(Long productId);

    /** 새 재고를 저장한다. 이미 저장된 재고의 변경은 트랜잭션 커밋 때 변경 감지가 저장한다. */
    void save(Inventory inventory);
}
