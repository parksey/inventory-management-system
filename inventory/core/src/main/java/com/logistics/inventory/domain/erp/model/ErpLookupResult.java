package com.logistics.inventory.domain.erp.model;

import com.logistics.inventory.domain.inventory.entity.StockChangeType;
import com.logistics.inventory.domain.outbox.entity.StockChangedPayload;

// 참조번호(멱등키)로 ERP를 조회한 결과. 조회는 ERP를 바꾸지 않으니 몇 번이든 다시 해도 된다
public record ErpLookupResult(
    Outcome outcome,
    String documentId,
    Recorded recorded,
    String error
) {

    public enum Outcome {
        FOUND, // ERP에 반영돼 있음. 들어간 내용은 recorded
        NOT_FOUND, // 조회는 됐고 없음. 아직 안 보였을 수도 있으니 다시 보내지 않는다
        ERROR // 조회 자체가 실패. 아무것도 모른다
    }

    // ERP에 실제로 들어간 재고 변동
    public record Recorded(String companyCode, String productCode, StockChangeType type, long quantity) {
    }

    public static ErpLookupResult found(final String documentId, final Recorded recorded) {
        return new ErpLookupResult(Outcome.FOUND, documentId, recorded, null);
    }

    public static ErpLookupResult notFound() {
        return new ErpLookupResult(Outcome.NOT_FOUND, null, null, "ERP에 참조번호가 없음");
    }

    public static ErpLookupResult error(final String error) {
        return new ErpLookupResult(Outcome.ERROR, null, null, error);
    }

    // ERP에 들어간 내용이 우리가 보낸 것과 같은지
    public boolean matches(final StockChangedPayload sent) {
        return recorded != null
            && sent.companyCode().equals(recorded.companyCode())
            && sent.productCode().equals(recorded.productCode())
            && sent.type() == recorded.type()
            && sent.quantity() == recorded.quantity();
    }
}
