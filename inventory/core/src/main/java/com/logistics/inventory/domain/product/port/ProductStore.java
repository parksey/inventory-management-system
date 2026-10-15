package com.logistics.inventory.domain.product.port;

import com.logistics.inventory.domain.product.entity.Product;
import com.logistics.inventory.domain.product.entity.ProductCode;
import java.util.Optional;

public interface ProductStore {

    Optional<Product> findByProductCode(ProductCode productCode);

    boolean saveIfAbsent(Product product);
}
