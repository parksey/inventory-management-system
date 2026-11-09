-- 원장 한 건(outbox 메시지 하나)의 ERP 전달 상태. 중계 배치가 만들고 전송 배치가 바꾼다
CREATE TABLE erp_sync (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    outbox_message_id    BIGINT      NOT NULL REFERENCES outbox_message (id),
    erp_idempotency_key  VARCHAR(64) NOT NULL,
    status               VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts             INT         NOT NULL DEFAULT 0,
    lookups              INT         NOT NULL DEFAULT 0,
    next_retry_at        TIMESTAMPTZ,
    locked_by            VARCHAR(64),
    locked_at            TIMESTAMPTZ,
    erp_document_id      VARCHAR(64),
    last_error           TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at              TIMESTAMPTZ,
    failed_at            TIMESTAMPTZ,
    CONSTRAINT ck_erp_sync_status CHECK (status IN ('PENDING', 'UNKNOWN', 'SENT', 'FAILED')),
    -- 메시지 하나에 ERP 전달은 하나뿐이다. 중계가 여러 번, 여러 서버에서 돌아도 같다
    CONSTRAINT uk_erp_sync_outbox_message UNIQUE (outbox_message_id),
    -- ERP에 참조번호('stx-' || 원장 id)로 함께 보내고, 결과를 모를 때 이 값으로 조회한다
    CONSTRAINT uk_erp_sync_idempotency_key UNIQUE (erp_idempotency_key)
);
-- 전송 배치가 매번 찾는 조건: 보낼 차례가 된 것. next_retry_at이 NULL이면 자동 재시도를 멈춘 것(사람 확인)
CREATE INDEX ix_erp_sync_due ON erp_sync (next_retry_at, id) WHERE status IN ('PENDING', 'UNKNOWN');
