package com.logistics.inventory.api.product;

import com.logistics.inventory.api.common.ApiHeaders;
import com.logistics.inventory.api.common.ApiResponse;
import com.logistics.inventory.domain.product.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @PatchMapping("/{productCode}")
    public ApiResponse<Void> rename(
            @PathVariable final String productCode,
            @RequestHeader(ApiHeaders.COMPANY_CODE) final String companyCode,
            @RequestBody final RenameProductRequest request
    ) {
        productService.rename(request.toCommand(companyCode, productCode));
        return ApiResponse.ok(null);
    }
}
