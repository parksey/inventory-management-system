package com.logistics.inventory.domain.inventory;

import static com.logistics.inventory.domain.common.ErrorCode.PRODUCT_NOT_FOUND;

import com.logistics.inventory.domain.common.BusinessException;
import com.logistics.inventory.domain.inventory.model.StockResult;
import com.logistics.inventory.domain.inventory.port.InventoryStore;
import com.logistics.inventory.domain.product.entity.ProductCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryStore inventoryStore;

    @Transactional(readOnly = true)
    public StockResult getStock(final ProductCode productCode) {
        return inventoryStore.findStockByProductCode(productCode).orElseThrow(() ->
            BusinessException.of(PRODUCT_NOT_FOUND, "productCode=" + productCode.value())
        );
    }
}
