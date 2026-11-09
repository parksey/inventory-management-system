package com.logistics.inventory.domain.erp.model;

import com.logistics.inventory.domain.outbox.entity.StockChangedPayload;

// 전송 배치가 선점한 한 건: 어느 erp_sync 행인지와 보낼 내용
public record ErpDelivery(
    Long erpSyncId,
    ErpStockMovement movement
) {

    // 조인 조회 결과를 바로 담기 위한 생성자 (erp_sync의 id와 키, outbox의 payload)
    public ErpDelivery(final Long erpSyncId, final String idempotencyKey, final StockChangedPayload payload) {
        this(erpSyncId, new ErpStockMovement(idempotencyKey, payload));
    }
}
