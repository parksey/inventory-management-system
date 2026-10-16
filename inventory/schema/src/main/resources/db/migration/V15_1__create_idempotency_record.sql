CREATE TABLE idempotency_record (
    idempotency_key  VARCHAR(64) PRIMARY KEY,
    type             VARCHAR(20) NOT NULL,
    product_code     VARCHAR(64) NOT NULL,
    quantity         BIGINT      NOT NULL,
    requester_id     VARCHAR(64) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_idempotency_record_type CHECK (type IN ('INBOUND', 'OUTBOUND'))
);
