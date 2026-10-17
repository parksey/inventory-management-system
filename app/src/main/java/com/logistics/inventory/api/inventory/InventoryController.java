package com.logistics.inventory.api.inventory;

import com.logistics.inventory.api.common.ApiResponse;
import com.logistics.inventory.api.common.CursorResponse;
import com.logistics.inventory.domain.common.CursorQuery;
import com.logistics.inventory.domain.inventory.InventoryService;
import com.logistics.inventory.domain.product.entity.ProductCode;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @GetMapping("/{productCode}")
    public ApiResponse<StockResponse> getStock(@PathVariable final String productCode) {
        return ApiResponse.ok(StockResponse.from(inventoryService.getStock(new ProductCode(productCode))));
    }

    @GetMapping("/{productCode}/history")
    public ApiResponse<CursorResponse<StockHistoryResponse>> getHistory(
            @PathVariable final String productCode,
            @RequestParam(required = false) @Min(1) final Long cursor,
            @RequestParam(defaultValue = "20") @Min(1) final int size
    ) {
        return ApiResponse.ok(CursorResponse.of(
            inventoryService.getHistory(new ProductCode(productCode), new CursorQuery(cursor, size)),
            StockHistoryResponse::from
        ));
    }
}
