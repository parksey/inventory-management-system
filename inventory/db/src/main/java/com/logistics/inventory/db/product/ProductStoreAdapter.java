package com.logistics.inventory.db.product;

import com.logistics.inventory.domain.product.entity.Product;
import com.logistics.inventory.domain.product.entity.ProductCode;
import com.logistics.inventory.domain.product.port.ProductStore;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class ProductStoreAdapter implements ProductStore {

    private static final int INSERTED = 1;

    private final ProductJpaRepository productJpaRepository;

    @Override
    public Optional<Product> findByProductCode(final ProductCode productCode) {
        return productJpaRepository.findByProductCodeValue(productCode.value());
    }

    @Override
    public boolean saveIfAbsent(final Product product) {
        return productJpaRepository.insertIfAbsent(product.getProductCode().value(), product.getName().value()) == INSERTED;
    }
}
