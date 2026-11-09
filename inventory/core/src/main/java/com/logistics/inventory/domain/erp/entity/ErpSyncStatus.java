package com.logistics.inventory.domain.erp.entity;

// ERP 전달 상태. 사람이 봐야 하는 건: FAILED, 또는 자동 재시도를 멈춘(next_retry_at 없음) PENDING, UNKNOWN
public enum ErpSyncStatus {
    PENDING,    // 아직 안 보냈거나, 확실히 안 나간 요청(연결 실패)
    UNKNOWN,    // 보냈는지 모름(응답 타임아웃, 5xx, 보내던 배치가 죽음). 다시 보내지 않고 참조번호로 조회해 확인한다
    SENT,       // ERP가 받음
    FAILED      // ERP가 확실히 거절함
}
