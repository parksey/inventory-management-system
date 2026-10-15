package com.logistics.inventory.api.inventory;

import com.logistics.inventory.domain.inventory.InventoryService;
import com.logistics.inventory.domain.product.entity.ProductCode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @GetMapping("/{productCode}")
    public StockResponse getStock(@PathVariable final String productCode) {
        return StockResponse.from(inventoryService.getStock(new ProductCode(productCode)));
    }
}
