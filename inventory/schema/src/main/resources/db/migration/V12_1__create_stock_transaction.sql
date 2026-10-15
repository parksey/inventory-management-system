CREATE TABLE stock_transaction (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id       BIGINT      NOT NULL REFERENCES product (id),
    type             VARCHAR(20) NOT NULL,
    quantity         BIGINT      NOT NULL,
    before_quantity  BIGINT      NOT NULL,
    after_quantity   BIGINT      NOT NULL,
    requester_id     VARCHAR(64) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_stock_transaction_type CHECK (type IN ('INBOUND', 'OUTBOUND')),
    CONSTRAINT ck_stock_transaction_quantity_positive CHECK (quantity > 0),
    CONSTRAINT ck_stock_transaction_snapshot_non_negative CHECK (before_quantity >= 0 AND after_quantity >= 0),
    CONSTRAINT ck_stock_transaction_balance CHECK (
        (type = 'INBOUND' AND after_quantity = before_quantity + quantity)
        OR (type = 'OUTBOUND' AND after_quantity = before_quantity - quantity)
    )
);
