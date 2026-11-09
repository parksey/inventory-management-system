package com.logistics.inventory.erp;

import com.logistics.inventory.domain.erp.model.ErpLookupResult;
import com.logistics.inventory.domain.erp.model.ErpSendResult;
import com.logistics.inventory.domain.erp.model.ErpStockMovement;
import com.logistics.inventory.domain.erp.port.ErpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class StubErpClient implements ErpClient {

    @Override
    public ErpSendResult send(final ErpStockMovement movement) {
        log.info("ERP 전송(대역, 실제로 보내지 않음): key={}, payload={}", movement.idempotencyKey(), movement.payload());
        return ErpSendResult.success("LOG-" + movement.idempotencyKey());
    }

    // 대역은 보낸 기록을 갖고 있지 않아 반영 여부를 알 수 없다
    @Override
    public ErpLookupResult find(final String idempotencyKey) {
        log.info("ERP 조회(대역, 실제로 조회하지 않음): key={}", idempotencyKey);
        return ErpLookupResult.notFound();
    }
}
