package com.logistics.inventory.domain.erp.model;

// ERP 호출 결과
public record ErpSendResult(
    Outcome outcome,
    String documentId,
    String error
) {

    public enum Outcome {
        SUCCESS, // ERP가 받음
        REJECTED, // 확실히 거절 (4xx 검증 오류) — 다시 보내지 않는다
        NOT_SENT, // 요청이 나가지도 못함 (연결 실패) — 다시 보내도 안전하다
        UNKNOWN // 보냈는데 결과를 모름 (응답 타임아웃, 5xx) — 다시 보내지 않고 참조번호로 조회해 확인한다
    }

    public static ErpSendResult success(final String documentId) {
        return new ErpSendResult(Outcome.SUCCESS, documentId, null);
    }

    public static ErpSendResult rejected(final String error) {
        return new ErpSendResult(Outcome.REJECTED, null, error);
    }

    public static ErpSendResult notSent(final String error) {
        return new ErpSendResult(Outcome.NOT_SENT, null, error);
    }

    public static ErpSendResult unknown(final String error) {
        return new ErpSendResult(Outcome.UNKNOWN, null, error);
    }
}
