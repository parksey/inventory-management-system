-- 커밋된 업무 변경을 외부로 알리기 위한 메시지. 업무 변경과 같은 트랜잭션에서 넣는다
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
    CONSTRAINT ck_outbox_message_status CHECK (status IN ('PENDING', 'PUBLISHED')),
    -- 업무 객체 하나에 같은 종류의 메시지는 하나뿐이다 (원장 한 건에 STOCK_CHANGED 하나)
    CONSTRAINT uk_outbox_message_event UNIQUE (aggregate_type, aggregate_id, event_type)
);
-- 전달 배치가 매번 찾는 조건: 아직 안 보냈고 지금 보낼 수 있는 것, 오래된 것부터
CREATE INDEX ix_outbox_message_pending ON outbox_message (available_at, id) WHERE status = 'PENDING';
