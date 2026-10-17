package com.logistics.inventory.api.inventory;

import com.logistics.inventory.api.common.ApiHeaders;
import com.logistics.inventory.api.common.ApiResponse;
import com.logistics.inventory.domain.idempotency.entity.IdempotencyKey;
import com.logistics.inventory.domain.inventory.InventoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/inventory")
@RequiredArgsConstructor
public class OutboundController {

    private final InventoryService inventoryService;

    @PostMapping("/{productCode}/outbound")
    public ApiResponse<Void> outbound(
            @PathVariable final String productCode,
            @RequestHeader(ApiHeaders.REQUESTER_ID) final String requesterId,   // 형식은 RequesterId가 검증
            @RequestHeader(ApiHeaders.IDEMPOTENCY_KEY) final String idempotencyKey,   // 형식은 IdempotencyKey가 검증
            @Valid @RequestBody final OutboundRequest request
    ) {
        inventoryService.outbound(new IdempotencyKey(idempotencyKey), request.toCommand(productCode, requesterId));
        return ApiResponse.ok(null);
    }
}
