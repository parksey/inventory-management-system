package com.logistics.inventory.domain.erp.port;

import com.logistics.inventory.domain.erp.model.ErpLookupResult;
import com.logistics.inventory.domain.erp.model.ErpSendResult;
import com.logistics.inventory.domain.erp.model.ErpStockMovement;

public interface ErpClient {

    // 응답을 성공 / 거절 / 안 나감 / 모름으로 나눠 돌려준다
    ErpSendResult send(ErpStockMovement movement);

    // 참조번호로 ERP에 반영됐는지 조회한다
    ErpLookupResult find(String idempotencyKey);
}
