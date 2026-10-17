package com.logistics.inventory.domain.product.port;

import com.logistics.inventory.domain.common.CompanyCode;
import com.logistics.inventory.domain.product.entity.Product;
import com.logistics.inventory.domain.product.entity.ProductCode;
import java.util.Optional;

public interface ProductStore {

    Optional<Product> findByProductCode(CompanyCode companyCode, ProductCode productCode);

    boolean saveIfAbsent(Product product);
}
