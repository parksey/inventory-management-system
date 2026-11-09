-- 재고 관리 시스템 전체 DDL (PostgreSQL 16)
--
-- 실제로 적용되는 원본은 inventory/schema/src/main/resources/db/migration/ 의 Flyway 마이그레이션이다.
-- 이 파일은 마이그레이션을 모두 적용한 뒤의 최종 스키마를 테이블별로 정리한 것이다. 마이그레이션을 추가하면 함께 고친다.

-- 상품. 회사마다 상품 코드가 따로다 (같은 상품 코드도 회사가 다르면 다른 상품)
CREATE TABLE product (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_code  VARCHAR(64)  NOT NULL,
    name          VARCHAR(200) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ,
    company_code  VARCHAR(64)  NOT NULL
);
-- 회사 + 상품 코드로 상품을 찾고 등록한다(ON CONFLICT). 동시에 처음 입고해도 상품이 하나만 생긴다
CREATE UNIQUE INDEX uk_product_company_code_product_code ON product (company_code, product_code);
ALTER TABLE product ADD CONSTRAINT uk_product_company_code_product_code UNIQUE USING INDEX uk_product_company_code_product_code;

-- 상품별 현재 재고. 입고, 출고는 이 행을 잠그고(SELECT ... FOR UPDATE) 바꾼다
CREATE TABLE inventory (
    product_id  BIGINT      PRIMARY KEY REFERENCES product (id),
    quantity    BIGINT      NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ,
    CONSTRAINT ck_inventory_quantity_non_negative CHECK (quantity >= 0)
);

-- 원장: 입고, 출고마다 한 행. 지우거나 고치지 않는다
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
-- 상품별 이력을 id 역순으로 읽는 조회용
CREATE INDEX ix_stock_transaction_product_id_id ON stock_transaction (product_id, id);

-- 처리한 멱등성 키와 요청 내용. 회사마다 키가 따로다
CREATE TABLE idempotency_record (
    idempotency_key  VARCHAR(64) NOT NULL,
    type             VARCHAR(20) NOT NULL,
    product_code     VARCHAR(64) NOT NULL,
    quantity         BIGINT      NOT NULL,
    requester_id     VARCHAR(64) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    company_code     VARCHAR(64) NOT NULL,
    CONSTRAINT ck_idempotency_record_type CHECK (type IN ('INBOUND', 'OUTBOUND'))
);
-- 회사 + 키로 넣고(ON CONFLICT) 다시 읽는다. 같은 키는 한 번만 처리된다
CREATE UNIQUE INDEX pk_idempotency_record ON idempotency_record (company_code, idempotency_key);
ALTER TABLE idempotency_record ADD CONSTRAINT pk_idempotency_record PRIMARY KEY USING INDEX pk_idempotency_record;

-- 커밋된 업무 변경을 외부로 알리기 위한 메시지(outbox). 업무 변경과 같은 트랜잭션에서 넣고, 전달 배치가 가져가 외부로 보낸다
CREATE TABLE outbox_message (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_code    VARCHAR(64) NOT NULL,
    aggregate_type  VARCHAR(40) NOT NULL,
    aggregate_id    VARCHAR(64) NOT NULL,
    event_type      VARCHAR(40) NOT NULL,
    payload         JSONB       NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts        INT         NOT NULL DEFAULT 0,
    available_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_at       TIMESTAMPTZ,
    locked_by       VARCHAR(64),
    published_at    TIMESTAMPTZ,
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_outbox_message_status CHECK (status IN ('PENDING', 'PUBLISHED', 'REQUIRES_REVIEW')),
    -- 업무 객체 하나에 같은 종류의 메시지는 하나뿐이다 (원장 한 건에 STOCK_CHANGED 하나)
    CONSTRAINT uk_outbox_message_event UNIQUE (aggregate_type, aggregate_id, event_type)
);
-- 전달 배치가 매번 찾는 조건: 아직 안 보냈고 지금 보낼 수 있는 것, 오래된 것부터
CREATE INDEX ix_outbox_message_pending ON outbox_message (available_at, id) WHERE status = 'PENDING';

-- 원장 한 건(outbox 메시지 하나)의 ERP 전달 상태. 중계 배치가 만들고 전송 배치가 바꾼다. 사람 확인 대상: FAILED이거나 next_retry_at이 NULL인 PENDING, UNKNOWN
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
    CONSTRAINT uk_erp_sync_outbox_message UNIQUE (outbox_message_id),
    CONSTRAINT uk_erp_sync_idempotency_key UNIQUE (erp_idempotency_key)
);
-- 전송 배치가 매번 찾는 조건: 보낼 차례가 된 것. next_retry_at이 NULL이면 자동 재시도를 멈춘 것(사람 확인)
CREATE INDEX ix_erp_sync_due ON erp_sync (next_retry_at, id) WHERE status IN ('PENDING', 'UNKNOWN');
