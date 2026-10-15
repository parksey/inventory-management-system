package com.logistics.inventory.domain.inventory;

import static com.logistics.inventory.domain.common.ErrorCode.PRODUCT_NOT_FOUND;

import com.logistics.inventory.domain.common.BusinessException;
import com.logistics.inventory.domain.inventory.entity.Inventory;
import com.logistics.inventory.domain.inventory.entity.StockChange;
import com.logistics.inventory.domain.inventory.model.InboundCommand;
import com.logistics.inventory.domain.inventory.model.StockResult;
import com.logistics.inventory.domain.inventory.port.InventoryStore;
import com.logistics.inventory.domain.product.entity.Product;
import com.logistics.inventory.domain.product.entity.ProductCode;
import com.logistics.inventory.domain.transaction.entity.StockTransaction;
import com.logistics.inventory.domain.transaction.port.StockTransactionStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InventoryService {

    private final ProductRegistrar productRegistrar;
    private final InventoryStore inventoryStore;
    private final StockTransactionStore stockTransactionStore;

    @Transactional(readOnly = true)
    public StockResult getStock(final ProductCode productCode) {
        return inventoryStore.findStockByProductCode(productCode).orElseThrow(() ->
            BusinessException.of(PRODUCT_NOT_FOUND, "productCode=" + productCode.value())
        );
    }

    @Transactional
    public void inbound(final InboundCommand command) {
        // 상품 (Product) — 없으면 수량 0인 재고와 함께 등록
        final Product product = productRegistrar.getOrRegister(command.productCode());
        // 재고 (Inventory) — 행 잠금 후 증가
        final Inventory inventory = inventoryStore.findByProductIdForUpdate(product.getId())
            .orElseThrow(() -> new IllegalStateException("상품은 있는데 재고 행이 없습니다. productId=" + product.getId()));
        final StockChange change = inventory.increase(command.quantity());
        // 원장 (StockTransaction)
        stockTransactionStore.save(StockTransaction.record(change, command.requesterId()));
    }
}
