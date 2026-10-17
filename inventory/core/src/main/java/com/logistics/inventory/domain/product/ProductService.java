package com.logistics.inventory.domain.product;

import static com.logistics.inventory.domain.common.ErrorCode.PRODUCT_NOT_FOUND;

import com.logistics.inventory.domain.common.BusinessException;
import com.logistics.inventory.domain.product.entity.Product;
import com.logistics.inventory.domain.product.model.RenameProductCommand;
import com.logistics.inventory.domain.product.port.ProductStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductStore productStore;

    @Transactional
    public void rename(final RenameProductCommand command) {
        final Product product = productStore.findByProductCode(command.companyCode(), command.productCode())
            .orElseThrow(() -> BusinessException.of(PRODUCT_NOT_FOUND, "productCode=" + command.productCode().value()));
        product.rename(command.name());
    }
}
