package com.logistics.inventory.domain.inventory;

import com.logistics.inventory.domain.inventory.entity.Inventory;
import com.logistics.inventory.domain.inventory.port.InventoryStore;
import com.logistics.inventory.domain.product.entity.Product;
import com.logistics.inventory.domain.product.entity.ProductCode;
import com.logistics.inventory.domain.product.port.ProductStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 상품을 찾고, 없으면 등록한다. 상품이 생기면 수량 0인 재고도 반드시 함께 생긴다. */
@Component
@RequiredArgsConstructor
public class ProductRegistrar {

    private final ProductStore productStore;
    private final InventoryStore inventoryStore;

    public Product getOrRegister(final ProductCode productCode) {
        return productStore.findByProductCode(productCode).orElseGet(() -> register(productCode));
    }

    private Product register(final ProductCode productCode) {
        final boolean created = productStore.saveIfAbsent(Product.register(productCode));
        final Product product = productStore.findByProductCode(productCode).orElseThrow();
        if (created) {
            inventoryStore.save(Inventory.create(product.getId()));
        }
        return product;
    }
}
