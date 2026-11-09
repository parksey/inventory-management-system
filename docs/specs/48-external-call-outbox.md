# 기능 명세 — 외부 시스템 호출 대비 (outbox) (#48)

### 요구사항 해석
- 입고, 출고가 반영되면 외부에 알릴 메시지를 **같은 트랜잭션**에 남긴다. 재고 변경이 커밋되면 메시지도 반드시 남고, 롤백되면 메시지도 남지 않는다. 메시지를 재고 변경과 따로 커밋하면 "재고는 바뀌었는데 메시지는 없음"(유실) 또는 "메시지는 있는데 재고는 롤백됨"(없는 변경 전달)이 생긴다.
- 외부 호출은 입고, 출고 요청 안에서 하지 않는다. 요청은 메시지를 남기고 커밋하면 끝이고, 실제 전달은 별도 배치가 한다(PR 2). 그래서 외부 시스템이 느리거나 죽어도 입출고 응답과 결과는 영향을 받지 않는다.
- outbox가 보장하는 것은 **잃지 않는 것(최소 한 번)**이다. 같은 메시지가 두 번 전달될 수 있으므로 받는 쪽이 메시지 id로 중복을 걸러야 한다. 한 번만 전달되는 것은 보장하지 않는다.
- 이 명세의 첫 PR은 메시지를 남기는 것까지다. 전달(선점, 재시도, 실패 상태)은 PR 2에서 한다.

**Open Questions에 대한 답**

| 질문 | 답 | 근거 |
|---|---|---|
| 어떤 변경을 전달하나 | 입고, 출고만. 상품명 수정은 범위 밖 | 외부가 알아야 하는 것은 재고 수량 변화다. 상품명은 필요해지면 이벤트 종류(`event_type`)를 하나 더 추가하면 된다 |
| 상품 단위 순서 보장 | PR 2에서 결정 (결정 필요 1) | 메시지를 남기는 쪽에는 영향이 없다. 전달 쪽 규칙이다 |
| 재시도 횟수, 간격, 실패 확인 방법 | PR 2에서 결정 (결정 필요 2) | 위와 같다 |

**용어**

| 용어 | 뜻 |
|---|---|
| outbox 메시지 (`OutboxMessage`) | 커밋된 업무 변경을 외부에 알리기 위해 같은 트랜잭션에 남기는 기록. 무엇에 대해(`aggregate_type`, `aggregate_id`), 무슨 일이(`event_type`), 무엇을 보낼지(`payload`) |
| 전달 배치 | outbox 메시지를 가져가 외부로 보내는 작업 (PR 2) |

### 설계

**데이터** — `inventory/schema`의 `V48_1__create_outbox_message.sql`

```sql
-- 커밋된 업무 변경을 외부로 알리기 위한 메시지. 업무 변경과 같은 트랜잭션에서 넣는다
CREATE TABLE outbox_message (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_code    VARCHAR(64) NOT NULL,
    aggregate_type  VARCHAR(40) NOT NULL,   -- 'STOCK_TRANSACTION'
    aggregate_id    VARCHAR(64) NOT NULL,   -- stock_transaction.id (여러 종류를 받으므로 FK 없음)
    event_type      VARCHAR(40) NOT NULL,   -- 'STOCK_CHANGED'
    payload         JSONB       NOT NULL,   -- 넣을 때 확정. 이후 바꾸지 않음
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts        INT         NOT NULL DEFAULT 0,
    available_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_at       TIMESTAMPTZ,
    locked_by       VARCHAR(64),
    published_at    TIMESTAMPTZ,
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_outbox_message_status CHECK (status IN ('PENDING', 'PUBLISHED', 'REQUIRES_REVIEW')),
    CONSTRAINT uk_outbox_message_event UNIQUE (aggregate_type, aggregate_id, event_type)
);
-- 전달 배치가 매번 찾는 조건: 아직 안 보냈고 지금 보낼 수 있는 것, 오래된 것부터
CREATE INDEX ix_outbox_message_pending ON outbox_message (available_at, id) WHERE status = 'PENDING';
```
- `UNIQUE (aggregate_type, aggregate_id, event_type)`: 원장 한 건에 STOCK_CHANGED 메시지는 하나뿐이다. 코드가 같은 원장으로 두 번 넣으려 하면 DB가 막는다.
- `aggregate_id`에 FK를 걸지 않는다. 다른 종류의 메시지(상품 등)를 같은 테이블에 받을 수 있게 하기 위해서다.
- `payload`는 넣는 순간 확정한다. 다시 전달할 때 현재 재고로 다시 만들면 그 사이의 다른 입출고가 섞여 내용이 달라진다.
- `attempts`, `available_at`, `locked_at`, `locked_by`, `published_at`, `last_error`는 PR 2의 중계 배치가 쓴다. 이 PR에서는 기본값으로만 들어간다. 컬럼을 미리 만드는 이유는 PR 2에서 테이블 변경 없이 배치만 추가하기 위해서다.
- `status`는 PENDING, PUBLISHED, REQUIRES_REVIEW다. REQUIRES_REVIEW는 중계를 5번 해도 넘기지 못해 자동으로는 그만둔 메시지다(사람 확인). 중계는 상대방이 없는 DB 작업이라 "거절" 같은 확정 실패가 없어서 FAILED라고 하지 않는다. `locked_at`, `locked_by`는 중계가 20건을 선점해 둔 동안 다른 중계가 가져가지 않게 하는 표시다(30초).
- 같은 내용을 `docs/ddl.sql`에 더한다.

**payload** (`STOCK_CHANGED`)

```json
{
  "stockTransactionId": 101,
  "companyCode": "COMPANY-A",
  "productCode": "TS-BASIC-BLK-M",
  "type": "OUTBOUND",
  "quantity": 3,
  "beforeQuantity": 10,
  "afterQuantity": 7,
  "occurredAt": "2026-11-09T13:00:00+09:00"
}
```
- 받는 쪽은 `stockTransactionId`로 중복을 거른다. 같은 원장 id가 다시 오면 이미 반영한 것이다.
- 변동량(`type`, `quantity`)이 본 내용이고, `beforeQuantity`, `afterQuantity`는 받는 쪽이 검증하거나 대사할 때 쓴다. 변동량을 기준으로 두면 늦게 도착한 메시지가 최신 재고를 덮어쓰지 않는다.
- `productId` 대신 `productCode`를 보낸다. 내부 id는 외부 계약이 되지 않게 한다.

**도메인** (`inventory/core`) — 새 애그리거트 패키지 `outbox`

| 패키지 | 클래스 | 역할 |
|---|---|---|
| `outbox.entity` | `OutboxMessage` | 엔티티. `OutboxMessage.stockChanged(CompanyCode, ProductCode, 원장 id, StockChange, 발생 시각)` 정적 팩토리가 aggregate, event, payload를 채운다. 원장 id가 없으면 `IllegalStateException`. setter 없음 |
| `outbox.entity` | `OutboxStatus` | `PENDING`, `PUBLISHED` |
| `outbox.entity` | `StockChangedPayload` | payload record. JSONB로 매핑. `occurredAt`은 ISO-8601 문자열 |
| `outbox.port` | `OutboxStore` | `save(OutboxMessage)` |
| `inventory` | `InventoryService.inbound`, `outbound` | 원장 저장 바로 다음에 outbox 메시지를 저장 |

- payload JSONB 매핑은 Hibernate의 `@JdbcTypeCode(SqlTypes.JSON)`을 쓴다. `domain`에 `compileOnly` hibernate-core를 추가한다 (아키텍처 규칙 1장이 "Hibernate 어노테이션이 필요하면 compileOnly hibernate-core"로 허용).
- Hibernate의 JSON 직렬화는 Boot 4(Hibernate 7.4)에서 그대로 동작한다. 다만 날짜를 숫자(epoch 초)로 쓰므로 `occurredAt`은 `OffsetDateTime.toString()`으로 만든 ISO-8601 문자열로 둔다. 도메인은 Jackson을 모르므로 `@JsonFormat`을 쓰지 않는다.
- 전달용 컬럼 중 `attempts`, `available_at`은 NOT NULL이라 지금 매핑한다(0, 저장 시각). `locked_at`, `locked_by`, `published_at`, `last_error`는 NULL 허용이라 PR 2에서 매핑한다.

**저장** (`inventory/db`의 `db.outbox`)
- `OutboxMessageJpaRepository`, `OutboxStoreAdapter`.
- id가 IDENTITY라 `save`가 곧 `persist`다.

**흐름** — `InventoryService.inbound`, `outbound`, 각각 `@Transactional` 하나 (순서만 추가)

```java
// 원장 (StockTransaction)
final StockTransaction transaction = StockTransaction.record(change, command.requesterId());
stockTransactionStore.save(transaction);
// 외부 알림 메시지 (OutboxMessage) — 같은 트랜잭션. 롤백되면 함께 사라지고, 커밋되면 반드시 남는다
outboxStore.save(OutboxMessage.stockChanged(
    command.companyCode(),
    command.productCode(),
    transaction.getId(),
    change,
    transaction.getCreatedAt()
));
// 커밋된 뒤 지표와 로그가 받는다. 롤백되면 전달되지 않는다
eventPublisher.publishEvent(change);
```
- 원장 id가 필요하므로 원장을 먼저 저장한다. IDENTITY라 `save` 직후 id가 채워진다.
- 같은 멱등성 키 재전송은 서비스 맨 앞에서 돌아가므로 outbox도 다시 남기지 않는다.
- 입출고 트랜잭션 안에서 외부를 부르지 않는다. 기존 `publishEvent`(지표, 로그)는 그대로 둔다. 커밋 후 리스너는 서버가 커밋 직후 죽으면 실행되지 않아서, 잃으면 안 되는 외부 전달에는 쓰지 않는다. 지표와 로그는 일부 빠져도 되므로 그대로 둔다.

**왜 `@TransactionalEventListener(AFTER_COMMIT)`로 외부를 부르지 않나** (대안)
- 이미 있는 이벤트에 리스너 하나만 더하면 되고, 테이블이 필요 없다.
- 하지만 커밋과 리스너 실행 사이에 서버가 죽으면 그 메시지는 사라진다. 재시도할 근거(무엇을 보내야 했는지)가 어디에도 남지 않는다. 이슈의 "전달할 내용이 사라지지 않아야 한다"를 지킬 수 없다.

### 설계 — 전달 (PR 2)

**전체 흐름** — Redis 없이 배치 두 개가 DB에서 직접 가져간다

```
① API 트랜잭션    재고 변경 + 원장 + outbox(PENDING)                          (PR 1)
② 중계 배치       outbox PENDING 선점 → erp_sync 생성(PENDING) → outbox PUBLISHED   (PR 2, 한 트랜잭션)
③ ERP 전송 배치   erp_sync 선점 → ERP 호출(트랜잭션 밖) → 결과 저장                 (PR 2)
```

- **outbox는 "무슨 일이 있었나"**, `erp_sync`는 **"ERP에 어디까지 보냈나"**를 기록한다. 나중에 받는 쪽(모니터링 등)이 늘면 outbox는 그대로 두고 대상별 테이블을 하나 더 만든다.
- outbox의 PUBLISHED는 "대상별 전달 기록(`erp_sync`)으로 넘겼다"는 뜻이다. 넘기는 일이 DB 트랜잭션 하나라서, outbox PUBLISHED와 `erp_sync` 생성은 항상 함께 커밋되거나 함께 롤백된다.
- **`processed_message`를 두지 않는다.** 중간에 메시지를 넘겨받는 큐가 없어서 "받았다" 기록이 필요 없다. 보냈는지는 `erp_sync.status`가, 동시 처리는 선점이 막는다. 처리량 때문에 큐를 넣게 되면 그때 큐를 받는 워커 쪽에 둔다.
- 배치는 모든 Pod에서 돈다. `FOR UPDATE SKIP LOCKED`가 Pod마다 다른 행을 나눠 가져가므로 한 대만 돌게 하는 장치(ShedLock 등)가 필요 없다.

**데이터** — `V48_2__create_erp_sync.sql`

```sql
-- 원장 한 건(outbox 메시지 하나)의 ERP 전달 상태. 중계 배치가 만든다
CREATE TABLE erp_sync (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    outbox_message_id    BIGINT      NOT NULL REFERENCES outbox_message (id),
    erp_idempotency_key  VARCHAR(64) NOT NULL,      -- 'stx-' || 원장 id. ERP에 참조번호로 함께 보내고, 결과를 모를 때 이 값으로 조회한다
    status               VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts             INT         NOT NULL DEFAULT 0,        -- 선점 횟수 (전송 + 조회)
    lookups              INT         NOT NULL DEFAULT 0,        -- 참조번호로 조회해서 못 찾은 횟수
    next_retry_at        TIMESTAMPTZ,               -- 다음 시도 시각(DB 시계). NULL이면 자동 재시도 중단(사람 확인)
    locked_by            VARCHAR(64),
    locked_at            TIMESTAMPTZ,
    erp_document_id      VARCHAR(64),               -- ERP가 돌려준 전표 번호
    last_error           TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at              TIMESTAMPTZ,
    failed_at            TIMESTAMPTZ,               -- 자동 처리를 멈춘 시각
    CONSTRAINT ck_erp_sync_status CHECK (status IN ('PENDING', 'UNKNOWN', 'SENT', 'FAILED')),
    CONSTRAINT uk_erp_sync_outbox_message UNIQUE (outbox_message_id),
    CONSTRAINT uk_erp_sync_idempotency_key UNIQUE (erp_idempotency_key)
);
-- 전송 배치가 매번 찾는 조건: 보낼 차례가 된 것
CREATE INDEX ix_erp_sync_due ON erp_sync (next_retry_at, id) WHERE status IN ('PENDING', 'UNKNOWN');
```

| 상태 | 뜻 | 다음 |
|---|---|---|
| PENDING | 아직 안 보냈거나, 확실히 안 나간 요청(연결 실패) | `next_retry_at`이 되면 전송 |
| UNKNOWN | 보냈는지 모름(응답 타임아웃, 5xx, 보내던 배치가 죽어 선점이 만료됨) | **다시 보내지 않는다.** `next_retry_at`이 되면 참조번호로 ERP를 조회한다. 있고 내용이 같으면 SENT, 내용이 다르면 사람 확인, 없으면 UNKNOWN 그대로 |
| SENT | ERP가 받음 | 끝 |
| FAILED | ERP가 확실히 거절(4xx 검증 오류) | 사람 확인 |

- **사람이 봐야 하는 건(DLQ)** = `status = 'FAILED'` 또는 (`status IN ('PENDING','UNKNOWN')` AND `next_retry_at IS NULL`). 따로 DLQ 테이블을 두지 않는다.
- 자동 처리를 멈추는 경우: ERP 거절(FAILED), 연결 실패 재시도 5회 초과, 조회 간격을 다 써도 못 찾음, 조회했는데 ERP 내용이 보낸 것과 다름(`last_error`가 `ERP 내용 불일치`로 시작), 우리 쪽의 다시 해도 안 되거나 원인 모를 실패(`last_error`가 `PERMANENT:` 또는 `REQUIRES_REVIEW:`로 시작).

**우리 쪽 실패 분류** (`ErpFailureClassifier`, dualwrite의 `classifyPayoutRequestedFailure`와 같은 기준)

| 종류 | 예외 | 처리 |
|---|---|---|
| RETRYABLE | `TransientDataAccessException`(잠금 대기 초과, 데드락, 직렬화 충돌, 쿼리 타임아웃), `RecoverableDataAccessException`, `DataAccessResourceFailureException`(연결 끊김), `CannotCreateTransactionException`(DB 다운) | 선점을 남긴다. 60초 뒤 선점이 만료되면 UNKNOWN으로 넘어가 조회로 확인한다 (ERP가 받았을 수 있으니 다시 보내지 않는다) |
| PERMANENT | `IllegalStateException`(선점한 행이나 outbox 메시지가 없음), `IllegalArgumentException`(허용하지 않는 상태 전이) | 그 건의 자동 처리를 멈춘다 |
| REQUIRES_REVIEW | 그 밖의 모든 예외 (기본값) | 추측해서 반복하지 않고 자동 처리를 멈춘다 |

ERP 응답의 분류(성공 / 거절 / 안 나감 / 모름)는 `ErpClient`가 하고, 이 표는 그 밖의 우리 쪽 처리(주로 결과 저장)에서 난 예외를 다룬다. ERP 구현이 분류하지 못한 예외는 보냈을 수도 있으므로 "모름"(UNKNOWN)으로 저장한다.

**② 중계 배치** (PR 2) — `OutboxRelayer.relayDue(workerId)`. dualwrite 발행 배치처럼 20건을 선점해 두고 한 건씩 넘긴다. 한 번 실행에 한 묶음이다. 선점, 한 건 넘기기, 실패 기록은 `OutboxRelayService`의 `@Transactional` 메서드다

```sql
-- ① 선점 (트랜잭션 하나). 커밋하면 행 잠금은 풀리고 선점 표시가 남는다
SELECT id FROM outbox_message
WHERE status = 'PENDING' AND available_at <= now()
  AND (locked_at IS NULL OR locked_at < now() - 30초)
ORDER BY available_at, id LIMIT 20
FOR UPDATE SKIP LOCKED;
UPDATE outbox_message SET locked_by = :pod, locked_at = now() WHERE id IN (:ids);
-- ② 한 건씩 넘김 (건마다 트랜잭션). 행을 잠그고 내 선점인지 확인한 뒤
INSERT INTO erp_sync (outbox_message_id, erp_idempotency_key, next_retry_at)
VALUES (:id, 'stx-' || :aggregateId, now())
ON CONFLICT (outbox_message_id) DO NOTHING;
UPDATE outbox_message SET status = 'PUBLISHED', published_at = ..., locked_by = NULL, locked_at = NULL WHERE id = :id;
-- ③ ②가 실패하면 (새 트랜잭션) 횟수와 원인을 남기고, 선점을 풀고, 백오프만큼 미룬다. 5번째 실패면 FAILED
UPDATE outbox_message SET attempts = attempts + 1, last_error = :error, locked_by = NULL, locked_at = NULL,
       available_at = now() + 백오프  -- 또는 status = 'REQUIRES_REVIEW'
WHERE id = :id;
```
- 선점 표시가 있어 20건을 들고 있는 동안 다른 Pod는 그 건들을 건너뛰고 다른 건을 가져간다. 중계는 DB 안에서만 하므로 20건이 30초 안에 끝난다.
- 중계가 죽으면 30초 뒤 선점이 만료되어 다른 중계가 다시 가져간다. 다시 넘겨도 `ON CONFLICT DO NOTHING`이라 `erp_sync`는 하나다. ERP 전송과 달리 결과를 모르는 상태가 없어서 복구 작업이 따로 필요 없다.
- 넘길 때와 실패를 기록할 때 내 선점인지(`locked_by`) 확인한다. 만료 뒤 다른 중계가 가져간 건을 늦게 끝난 중계가 바꾸지 않는다.
- 넘기지 못하는 메시지가 하나 있어도 그 건만 롤백되고 미뤄지므로 나머지를 막지 않는다.
- 실패하면 `erp_sync`와 같은 재시도 규칙(`RetryPolicy`: 1, 2, 4, 8초, 5번째에서 멈춤)을 따른다. `attempts`는 실패 횟수다. DB가 죽어 실패 기록마저 실패하면 예외가 스케줄러까지 올라가 로그로 남고, 선점한 건은 30초 뒤 다시 가져간다.

**③ ERP 전송, 확인 배치** (PR 2) — `ErpSyncSender.sendDue(workerId)`, `checkUnknown(workerId)`. 선점과 결과 저장은 `ErpSyncService`의 `@Transactional` 메서드이고, ERP 호출은 그 사이 트랜잭션 밖에서 한다

**ERP가 같은 키를 한 번만 반영한다고 가정하지 않는다.** 그래서 보냈는지 모르는 건은 다시 보내지 않고, 보낼 때 함께 넣은 참조번호(`erp_idempotency_key`)로 조회해서 확인한다. dualwrite에서 BROADCAST_UNKNOWN을 다시 보내지 않고 저장한 해시로 조회한 것과 같다. 자동으로 다시 보내는 건 요청이 나가지도 못한 연결 실패(PENDING)뿐이다.

```
복구 (10초마다, WorkflowScheduler.recoverExpiredClaims)
  선점 만료 정리          선점이 60초 넘게 묵은 PENDING → UNKNOWN (보내던 배치가 죽음, 보냈는지 모름)

중계 (1초마다, WorkflowScheduler.relay)
  중계                    outbox → erp_sync

ERP 전송·확인 (1초마다, WorkflowScheduler.deliverToErp)
  전송 (sendDue)          아무도 선점하지 않은 PENDING만. 한 건씩 선점해 보낼 게 없을 때까지 반복
    ① 선점: SELECT id ... WHERE status = 'PENDING' AND next_retry_at <= now() AND locked_at IS NULL
             ... LIMIT 1 FOR UPDATE SKIP LOCKED  →  UPDATE ... SET locked_by, locked_at = now(), attempts + 1 WHERE id IN (:ids)
    ② ERP 전송 (트랜잭션 밖)
    ③ 결과 저장 (건마다 짧은 트랜잭션, 선점 해제)
  확인 (checkUnknown)     UNKNOWN (조회는 ERP를 바꾸지 않으므로 선점이 만료된 건도 가져온다). 한 건씩, 없을 때까지 반복
    ① 선점 → ② 참조번호로 ERP 조회 (트랜잭션 밖) → ③ 결과 저장
```

| ERP 전송 결과 (`ErpClient.send`가 분류) | 저장 |
|---|---|
| 성공 | SENT, `erp_document_id`, `sent_at` |
| 거절 (4xx) | FAILED, `failed_at`, `last_error` |
| 연결 실패 (요청이 나가지 못함) | PENDING, `next_retry_at` = 백오프 → 다시 보낸다 |
| 결과 모름 (응답 타임아웃, 5xx, 분류하지 못한 예외) | UNKNOWN, `next_retry_at` = 첫 조회 간격(10초) → **조회로 넘긴다** |

| ERP 조회 결과 (`ErpClient.find`가 분류) | 저장 |
|---|---|
| 있음, 내용이 보낸 것과 같음 (회사, 상품, 구분, 수량) | SENT, `erp_document_id` |
| 있음, 내용이 다름 | UNKNOWN 그대로, `erp_document_id`, 자동 처리 중단(사람 확인). dualwrite가 receipt의 전송 내용을 기대와 비교하는 것과 같다 |
| 없음 | UNKNOWN 그대로, `lookups` + 1, `next_retry_at` = 다음 조회 간격. 반영이 늦게 보일 수 있어 다시 보내지 않는다 |
| 조회 실패 | 위와 같다 |

- 전송 재시도와 조회 간격은 따로다. 연결 실패는 `attempts`(선점할 때 1 늘어남) 기준 1, 2, 4, 8초 뒤 다시 보내고 5번째에서 멈춘다. 조회는 `inventory.erp.lookup-backoff`(기본 10초, 1분, 5분, 15분, 1시간)를 따른다. 첫 조회는 첫 간격 뒤, n번 못 찾으면 n+1번째 간격 뒤에 다시 조회하고, 간격을 다 쓰면(약 1시간 20분) 멈춘다. ERP가 늦게 반영할 수 있어 조회는 길게 기다린다.
- 멈출 때는 상태를 그대로 두고 `next_retry_at = NULL`, `failed_at`으로 표시한다(사람 확인).
- 시각(`next_retry_at`, `sent_at`, `failed_at`)은 결과를 저장하는 트랜잭션에서 읽은 DB 시계(`now()`)로 정한다. 배치가 `next_retry_at <= now()`로 DB 시계와 비교하기 때문이다.
- 백오프: `1초 × 2^(attempts − 1)` → 1, 2, 4, 8초. 5번째에서 멈추므로 16초는 쓰이지 않는다.
- 선점 만료 60초는 ERP 호출 타임아웃(구현체 몫, 연결 3초 + 읽기 10초)보다 충분히 길게 둔다. 만료 전에 앞 배치가 살아 있는데 다른 배치가 가져가는 일을 줄이기 위해서다.
- 선점이 만료된 PENDING은 다시 보내지 않는다. 앞 배치가 보냈는지 모르므로 UNKNOWN으로 바꿔 조회로 넘긴다.
- 결과 저장은 `WHERE id = :id AND locked_by = :pod` 조건으로 한다. 선점이 만료돼 다른 배치가 가져간 건을 늦게 끝난 앞 배치가 덮어쓰지 않게 한다.

**ERP 포트** (`domain/erp`)
- `ErpClient.send(ErpStockMovement)` → `ErpSendResult`(SUCCESS + 전표 번호 / REJECTED / NOT_SENT / UNKNOWN).
- `ErpClient.find(참조번호)` → `ErpLookupResult`(FOUND + 전표 번호 + 들어간 내용 / NOT_FOUND / ERROR). 대역(`StubErpClient`)은 보낸 기록이 없어 항상 NOT_FOUND다.
- 이번 이슈에서는 ERP API가 정해지지 않았으므로 **로그만 남기고 성공을 돌려주는 구현**(`StubErpClient`)을 둔다. ERP 호출은 밖으로 나가는 어댑터라 `db`처럼 새 모듈 `inventory/erp`(`:inventory:erp`)에 두고, `compileOnly(project(":inventory:core"))`로 포트를 구현한다. `app`은 `runtimeOnly`로만 넣어 ERP 구현을 import하지 못한다. 실제 HTTP 구현은 ERP API가 정해지면 별도 이슈로 한다.
- 테스트는 결과를 정해 줄 수 있는 테스트용 구현으로 바꿔 끼운다.

**실행기** (`app`)
- `@EnableScheduling`, `OutboxScheduler`: `@Scheduled(fixedDelayString = "${inventory.outbox.interval:1s}")`로 중계, 전송을 각각 부른다. `fixedDelay`라 한 Pod 안에서는 앞 실행이 끝나야 다음이 돈다.
- 테스트는 배치를 직접 부른다. 테스트 프로필에서 `inventory.outbox.initial-delay`, `interval`을 1시간으로 둬서 테스트 중에는 스케줄러가 돌지 않게 한다 (켜고 끄는 설정은 두지 않는다).

**확인 방법**
- 지표: 결과를 저장한 트랜잭션이 커밋되면 `ErpStockMovementOutcome` 이벤트를 `ErpStockMovementMetrics`가 받아 `inventory.erp.stock-movement{outcome=SENT|REJECTED|NOT_SENT|UNKNOWN|LOOKUP_NOT_FOUND|LOOKUP_ERROR|MISMATCH|STOPPED}` 카운터를 올린다. ERP 전달의 성공률, 오류율을 보는 용도다. REJECTED, MISMATCH, STOPPED는 WARN 로그도 남긴다. 실패 상태는 enum 값이라 태그로 쓸 수 있다.
- 로그: 자동 재시도를 멈출 때 `erp_sync` id, 원장 id, 마지막 오류를 WARN으로 남긴다.
- 조회 API는 범위 밖이다. 사람 확인 대상은 위 DLQ 조건의 SQL로 본다.

### 비즈니스 규칙
- R1. 입고, 출고가 커밋되면 그 원장 한 건에 대한 STOCK_CHANGED 메시지가 PENDING으로 하나 남는다.
- R2. 입고, 출고가 실패해 롤백되면 메시지도 남지 않는다.
- R3. 같은 멱등성 키로 다시 온 요청은 메시지를 다시 남기지 않는다.
- R4. 원장 한 건에 같은 종류의 메시지는 한 행만 있을 수 있다 (DB 제약).
- R5. payload는 원장에 기록된 값과 같다.
- R6. (PR 2) PENDING 메시지는 중계 배치가 `erp_sync`(PENDING, 키 `stx-<원장 id>`)로 넘기고 PUBLISHED가 된다. 둘은 함께 커밋된다.
- R7. (PR 2) 메시지 하나에 `erp_sync`는 하나뿐이다. 중계가 여러 번, 여러 서버에서 동시에 돌아도 같다.
- R13. (PR 2) 중계는 20건을 선점해 한 건씩 넘긴다. 선점한 중계가 30초 안에 끝내지 못하면 다른 중계가 가져간다. 넘기지 못한 메시지는 횟수와 원인을 남기고 백오프(1, 2, 4, 8초) 뒤 다시 넘기며, 5번째에서도 실패하면 사람 확인(REQUIRES_REVIEW)으로 멈춘다. 한 메시지의 실패가 다른 메시지를 막지 않는다.
- R8. (PR 2) ERP에는 참조번호(`erp_idempotency_key`)와 outbox payload를 보낸다. 보냈는지 모르는 건(UNKNOWN)은 다시 보내지 않고 참조번호로 조회해 확인한다. 찾았더라도 ERP에 들어간 내용(회사, 상품, 구분, 수량)이 보낸 것과 다르면 SENT로 두지 않고 사람이 확인한다.
- R9. (PR 2) 한 건은 한 번에 한 배치만 보낸다(선점). 선점한 배치가 60초 안에 끝내지 못하면 그 건은 UNKNOWN이 되어 조회로 넘어가고, 다시 보내지 않는다.
- R10. (PR 2) 연결 실패는 백오프(1, 2, 4, 8초) 뒤 다시 보내고 5번째 시도에서 멈춘다. 결과 모름은 설정한 조회 간격(기본 10초, 1분, 5분, 15분, 1시간)으로 조회하고, 간격을 다 써도 못 찾으면 멈춘다. 거절은 다시 보내지 않는다. 다음 시도 시각은 DB 시계로 정한다.
- R11. (PR 2) ERP가 응답하지 않아도 입고, 출고는 정상 응답한다.
- R12. (PR 2) ERP를 부른 뒤 결과 저장이 실패하면 원인과 상관없이 UNKNOWN으로 두고 조회로 확인한다. UNKNOWN 표시마저 실패하면 선점이 남아 만료 뒤 UNKNOWN이 된다. 조회 결과 저장이 실패하면 실패를 나눠, 기다리면 될 실패는 선점을 남기고 나머지는 그 건의 자동 처리를 멈춘다. 한 건의 실패가 나머지 건을 막지 않는다.

### 예외 케이스

| 상황 | 결과 |
|---|---|
| 재고 부족으로 출고 409 | 메시지 없음 |
| 없는 상품 출고 404 | 메시지 없음 |
| 같은 멱등성 키, 같은 내용 재전송 | 200, 메시지 그대로 1건 |
| 같은 원장으로 STOCK_CHANGED를 두 번 INSERT (SQL 직접) | 유니크 위반 |
| status에 정해지지 않은 값 (SQL 직접) | CHECK 위반 |
| (PR 2) 같은 메시지로 `erp_sync`를 두 번 INSERT (SQL 직접) | 유니크 위반 |
| (PR 2) 보낸 뒤 결과 저장 전에 배치가 죽음 | 60초 뒤 UNKNOWN으로 바뀌고, 다시 보내지 않고 참조번호로 조회해 확인 |
| (PR 2) 선점이 만료된 뒤 앞 배치가 늦게 결과를 저장 | `locked_by`가 달라 0행. 뒤 배치의 결과가 남음 |

### 테스트 케이스

| ID | 대상 규칙 | Given | When | Then | 종류 |
|---|---|---|---|---|---|
| TC-48-01 | R1, R5 | 처음 보는 상품 (재고 0) | 5 입고 | 200, 메시지 1건: PENDING, STOCK_TRANSACTION / 원장 id / STOCK_CHANGED, payload가 원장 값과 같음 | 통합 |
| TC-48-02 | R1, R5 | 재고 5 | 3 출고 | 200, 메시지 1건, payload의 type OUTBOUND, before 5, after 2 | 통합 |
| TC-48-03 | R2 | 재고 5 | 6 출고 | 409, 원장 0건, 메시지 0건 | 통합 |
| TC-48-04 | R3 | 키 K1로 입고 성공 | 같은 키, 같은 내용으로 다시 입고 | 200, 메시지 1건 그대로 | 통합 |
| TC-48-05 | R4 | 원장 1번의 STOCK_CHANGED 메시지 있음 | 같은 aggregate, event로 INSERT | 유니크 위반 | 스키마 |
| TC-48-06 | — | — | status에 'DONE' INSERT | CHECK 위반 | 스키마 |
| TC-48-07 | R5 | 원장 (OUTBOUND 3, 10 → 7) | `OutboxMessage.stockChanged` | aggregate, event, payload 필드가 원장 값과 같고 PENDING. 원장 id가 없으면 `IllegalStateException` | 단위 |
| TC-48-08 | R6 | PENDING 메시지 2건 | 중계 | `erp_sync` 2건 PENDING, 키 `stx-<원장 id>`, `next_retry_at` 있음, 메시지 2건 PUBLISHED | 통합 |
| TC-48-09 | R7 | 중계가 한 번 끝난 상태 | 중계를 다시 실행 | `erp_sync` 그대로 2건 | 통합 |
| TC-48-10 | R7 | PENDING 메시지 20건 | 중계 4개를 동시에 실행 | `erp_sync` 20건, 메시지 20건 모두 PUBLISHED | 통합 |
| TC-48-11 | R7 | `erp_sync`에 메시지 1번 행 있음 | 같은 메시지로 INSERT, status에 'DONE' INSERT | 유니크 위반, CHECK 위반 | 스키마 |
| TC-48-12 | R8 | `erp_sync` PENDING, ERP 성공 | 전송 | SENT, 전표 번호, `sent_at`, 선점 해제. ERP가 받은 키와 본문이 `stx-<원장 id>`, outbox payload | 통합 |
| TC-48-13 | R10 | ERP 거절 | 전송 | FAILED, `failed_at`. 다음 실행에서 다시 보내지 않음 | 통합 |
| TC-48-14 | R10 | ERP 연결 실패 | 전송 | PENDING, attempts 1, `next_retry_at` ≈ 1초 뒤 | 통합 |
| TC-48-15 | R8 | ERP 결과 모름, 조회하면 있음 | 전송 → 재시도 시각 경과 → 전송, 확인 | UNKNOWN, 전송 대상 아님, 조회로 SENT. ERP 전송은 1번 | 통합 |
| TC-48-16 | R10 | 결과 모름, 조회하면 계속 없음 | 전송 1번 + 조회 5번 | `lookups` 5, `next_retry_at` NULL, `failed_at`, 이후 조회하지 않음. ERP 전송은 1번 | 통합 |
| TC-48-17 | R9 | `erp_sync` PENDING 20건 | 전송 4개를 동시에 실행 | ERP 호출 정확히 20번, 모두 SENT | 통합 |
| TC-48-18 | R9 | 다른 배치가 선점하고 61초가 지남 | 선점 만료 정리, 전송, 확인 | UNKNOWN으로 바뀜, 다시 보내지 않음, 조회로 SENT | 통합 |
| TC-48-19 | R9 | 선점이 만료돼 UNKNOWN으로 넘어감 | 앞 배치가 늦게 결과 저장 | 무시, UNKNOWN 유지 | 통합 |
| TC-48-20 | R11 | ERP가 모든 요청에 결과 모름 | 입고 | 200, 재고 반영, `erp_sync`는 전송 후 UNKNOWN | 통합 |
| TC-48-21 | R10 | attempts 1~4 | 전송 백오프 계산 | 1, 2, 4, 8초. 최대 시도는 5번 | 단위 |
| TC-48-32 | R8 | 결과 모름, 조회하면 있는데 수량이 1 다름 | 확인 | UNKNOWN 유지, `erp_document_id`, `next_retry_at` NULL, `failed_at`, `last_error`에 불일치. 다시 보내지도 조회하지도 않음 | 통합 |
| TC-48-33 | R10 | ERP 결과 모름 | 전송 | `next_retry_at` ≈ DB 시계 + 10초, 그 전에는 조회하지 않음 | 통합 |
| TC-48-34 | R10 | 조회 간격 10초, 1분, 5분, 15분, 1시간 | 다음 조회 간격 계산 | 첫 조회 10초, 1번 못 찾으면 1분, 4번이면 1시간, 5번이면 멈춤. 빈 간격은 만들 수 없음 | 단위 |
| TC-48-35 | R8 | 보낸 payload와 ERP 조회 내용 | 내용 비교 | 회사, 상품, 구분, 수량이 모두 같을 때만 같음. 없음은 다름 | 단위 |
| TC-48-22 | — | 재고 변동 한 건 | 대역(`StubErpClient`)으로 전송, 조회 | 전송은 성공(전표 번호 `LOG-<키>`), 조회는 항상 없음 | 단위 |
| TC-48-23 | R12 | 2건 중 1건의 결과 저장이 예외로 끝남 | 전송 | 나머지 1건 SENT. 실패 1건은 바로 UNKNOWN(`last_error` "결과 저장 실패: ...", `next_retry_at` 있음, 선점 해제). 다시 보내지 않음 | 통합 |
| TC-48-24 | R12 | 잠금 대기 초과, 잠금 실패, 쿼리 타임아웃, 연결 끊김, 트랜잭션 시작 실패 / 상태 오류, 인자 오류 / NPE | 분류 | RETRYABLE / PERMANENT / REQUIRES_REVIEW | 단위 |
| TC-48-25 | R12 | ERP 호출 중 다른 트랜잭션이 그 행을 잠금 | 전송 | 결과 저장과 UNKNOWN 표시가 모두 잠금 대기 초과 → 예외가 밖으로, 선점 유지. 만료 뒤 UNKNOWN, 다시 보내지 않고 조회로 SENT. ERP 전송은 1번 | 통합 |
| TC-48-26 | R8, R10 | 결과 모름, 조회하면 없음 | 확인 | UNKNOWN 유지, 선점 해제, 백오프 전에는 다시 조회하지 않음, 다시 보내지 않음 | 통합 |
| TC-48-27 | R13 | 넘기지 못하는 메시지(키가 64자 초과)가 정상 메시지보다 앞에 있음 | 중계 | 정상 메시지는 넘어감. 실패한 건은 PENDING, attempts 1, `last_error`, `available_at`이 미래. 백오프 전에는 다시 가져가지 않음 | 통합 |
| TC-48-28 | R13 | 넘기지 못하는 메시지 | 중계 5번 | 4번째까지 PENDING, 5번째에 REQUIRES_REVIEW(attempts 5). 이후 가져가지 않음 | 통합 |
| TC-48-30 | R13 | 중계 A가 선점하고 죽음 | 중계 B, 31초 뒤 중계 B, A의 늦은 처리 | 선점 중에는 건너뜀, 만료 뒤 B가 넘김, A의 처리는 무시, `erp_sync` 1건, 선점 해제 | 통합 |
| TC-48-29 | R13 | PENDING 메시지 | 중계 실패 기록 5번 | 1, 2, 4, 8초를 돌려주고 5번째에 REQUIRES_REVIEW, 마지막 원인을 남김 | 단위 |

### PR 계획

| PR | 브랜치 | 범위 | 포함 테스트 케이스 |
|---|---|---|---|
| 1 | `feat/48-external-client-idempotency` | outbox 테이블, 입출고 트랜잭션에서 메시지 기록 (`Refs #48`) | TC-48-01 ~ TC-48-07 |
| 2 | `feat/48-outbox-relay` | `erp_sync` 테이블, 중계 배치(outbox → `erp_sync`), ERP 포트와 대역 구현(`StubErpClient`), 전송 배치(선점, 재시도, 자동 재시도 중단), 스케줄러, 지표 (`Closes #48`) | TC-48-08 ~ TC-48-35 |

### 결정 필요
1. **UNKNOWN을 조회로 확인하려면 ERP에 참조번호 조회가 필요하다.** ERP API가 정해지면, 보낼 때 참조번호를 어느 필드에 넣고 무엇으로 조회하는지 확인한다. 조회가 없으면 UNKNOWN은 조회 없이 바로 사람 확인으로 넘긴다. 조회 결과에 들어간 내용(회사, 상품, 구분, 수량)을 받을 수 있는지, 반영까지 얼마나 걸리는지도 확인해 `inventory.erp.lookup-backoff`를 정한다.
2. **상품 단위 순서.** 보장하지 않는다. 변동량을 보내서 합계는 순서와 상관없이 맞는다. ERP가 "중간에 음수가 되면 거절"하는 규칙을 가지면 다시 검토한다.
3. **받는 쪽이 늘 때.** 이번 이슈는 ERP 하나다. 모니터링 등이 생기면 대상별 테이블(`xxx_sync`)과 중계 배치의 INSERT를 하나씩 더한다. outbox와 입출고 트랜잭션은 바뀌지 않는다.

### 변경 이력
- 구현 중: `OutboxMessage.stockChanged`가 `StockTransaction` 대신 원장 id, `StockChange`, 발생 시각을 받는다. 원장 엔티티는 저장해야 id와 시각이 생겨서, 엔티티를 받으면 단위 테스트(TC-48-07)를 쓸 수 없다.
- 구현 중: payload의 `occurredAt`을 ISO-8601 문자열로 바꿨다. Hibernate JSON 변환이 `OffsetDateTime`을 숫자로 쓴다. native INSERT 대체안은 필요 없었다.
- 구현 중: TC-48-01~03의 Given을 테스트 공통 준비 데이터(상품 하나, 재고 5)에 맞췄다.
- PR 2 착수: 전달 설계(중계 배치, `erp_sync`, ERP 전송 배치) 추가. 받는 쪽이 하나라도 전달 상태를 outbox가 아니라 `erp_sync`에 두기로 했다 (대상이 늘 때 outbox를 바꾸지 않기 위해). PR 계획을 PR 2(중계), PR 3(전송)으로 나눴다. `processed_message`는 두지 않는다.
- PR 2 착수: 중계와 전송을 PR 하나(PR 2)로 합쳤다. 따로 머지하면 중계만 있는 동안 `erp_sync`가 쌓이기만 한다.
- 구현 중: 중계를 SELECT 후 건마다 INSERT하지 않고, 잠금(`FOR UPDATE SKIP LOCKED`), `erp_sync` INSERT(`ON CONFLICT DO NOTHING`), PUBLISHED UPDATE를 CTE 한 문장으로 했다. 왕복이 한 번이고 셋이 함께 반영된다. 넘기는 메시지는 `STOCK_TRANSACTION`/`STOCK_CHANGED`로 한정했다.
- 구현 중: 전송을 `ErpSyncSender`(흐름, 트랜잭션 없음)와 `ErpSyncService`(선점, 결과 저장 트랜잭션)로 나눴다. 같은 클래스 안 호출은 트랜잭션 프록시를 거치지 않아서, 호출을 트랜잭션 밖에 두려면 빈을 나눠야 한다.
- 구현 중: `ErpClient` 구현이 결과로 나누지 못한 예외는 결과 모름(UNKNOWN)으로 저장한다. 보냈을 수도 있으니 실패로 단정하지 않는다 (TC-48-20).
- 구현 중: 결과 저장 시 `erp_sync` 행을 `FOR UPDATE`로 잠근 뒤 `locked_by`를 확인한다. 확인과 변경 사이에 다른 배치가 선점하지 못하게 하기 위해서다.
- 구현 중: 지표는 결과 저장 트랜잭션이 커밋된 뒤 `ErpSyncRecorded` 이벤트를 app의 `ErpSyncMetrics`가 받아 센다(입출고 지표와 같은 방식). 거절과 자동 재시도 중단은 WARN 로그로 남긴다.
- 구현 중: ERP 호출 구현(`LoggingErpClient`)을 `app`에서 새 모듈 `inventory/erp`로 옮겼다. 밖으로 나가는 어댑터라 `db`와 같은 자리에 둔다. `adapter/db`, `adapter/schema`, `adapter/erp`로 모으는 재배치는 다른 PR에서 한다. 모듈 테스트로 TC-48-22를 더했다.
- 구현 중: 전송 선점을 `UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED) RETURNING` 한 문장에서, 같은 트랜잭션 안의 조회(`findDueIdsForUpdate`)와 선점 표시(`markClaimed`, `WHERE id IN (:ids)`) 두 쿼리로 나눴다. 흐름이 서비스에 보이게 하기 위해서다. 같은 트랜잭션이라 조회에서 잡은 행 잠금이 UPDATE까지 유지된다. `locked_at`은 만료 비교와 같은 DB 시계(`now()`)로 넣는다.
- 구현 중: `outbox_message.available_at`, `created_at`을 JVM 시계(`@PrePersist`)가 아니라 DB 기본값(`now()`)으로 넣게 바꿨다. 중계가 `available_at <= now()`로 DB 시계와 비교하는데, DB 시계가 JVM보다 수십 ms 늦으면 방금 넣은 메시지를 건너뛰었다(테스트가 간헐적으로 실패). 비교하는 시각은 모두 DB 시계로 맞춘다.
- 구현 중: 선점한 건의 보낼 내용을 건마다 `erp_sync`, `outbox_message`를 따로 읽지 않고(20건이면 40번) JPQL 조인 한 번(`findDeliveries`)으로 읽는다. 쓰는 곳이 없어진 `OutboxStore.findById`는 지웠다.
- 구현 중: 전송 배치가 건마다 예외를 잡아 실패한 건만 결과(`ErpSendSummary.failures`)에 남기고 다음 건을 계속 보낸다. 한 건의 결과 저장 실패로 나머지 건이 선점 만료(60초)까지 묶이지 않게 하기 위해서다. 실패한 건은 선점이 남아 있다가 만료 뒤 다시 처리된다. 실패 목록은 스케줄러가 ERROR 로그로 남긴다 (TC-48-23).
- 구현 중: 결과 저장 실패를 한 종류로 다루지 않고 dualwrite처럼 RETRYABLE / PERMANENT / REQUIRES_REVIEW로 나눴다(`ErpFailureClassifier`). 모든 실패를 선점 만료 뒤 재시도로 두면, 다시 해도 안 되는 건이 60초마다 끝없이 반복되고 DLQ로 가지 않았다. RETRYABLE이 아니면 `ErpSync.stop()`으로 자동 처리를 멈춘다 (TC-48-23~25).
- 구현 중: 스케줄러를 켜고 끄는 `@ConditionalOnProperty`(`inventory.outbox.scheduler.enabled`)를 뺐다. 운영에는 필요 없는 스위치라, 테스트는 첫 실행과 주기를 1시간으로 늦추는 설정으로 대신한다.
- 구현 중: ERP가 같은 키를 한 번만 반영한다고 가정하지 않기로 했다. 결과 모름(UNKNOWN)과 선점 만료 건을 같은 키로 다시 보내던 것을 없애고, dualwrite의 BROADCAST_UNKNOWN 처리처럼 참조번호로 조회해 확인한다(`ErpClient.find`, `checkUnknown`, `markExpiredClaimsUnknown`). 자동으로 다시 보내는 건 연결 실패(PENDING)뿐이다. 백오프 표기도 실제 동작(1, 2, 4, 8초, 5번째에서 중단)에 맞게 고쳤다 (TC-48-15, 16, 18, 19, 21, 22, 25, 26).
- 구현 중: 선점 만료 정리(`markExpiredClaimsUnknown`)를 본 스케줄러에서 떼어 별도 스케줄러(`ErpSyncRecoveryScheduler`, 10초)로 옮겼다. 선점 만료(60초)가 지나야 대상이 되는 복구 작업이라 1초마다 볼 필요가 없고, 실패해도 본 작업(중계, 전송, 확인)을 막지 않게 하기 위해서다. 둘이 서로 기다리지 않게 스케줄러 스레드를 2개로 늘렸다(`spring.task.scheduling.pool.size`).
- 구현 중: 전송과 확인이 20건을 한 번에 선점하던 것을, 한 건씩 선점해 처리하고 없을 때까지 반복하게 바꿨다(`claimNext`, `claimNextUnknown`). 20건을 한 번에 잡으면 선점 시계가 함께 시작해서, ERP가 느릴 때 뒤쪽 건이 보내기도 전에 60초를 넘겨 UNKNOWN으로 빠졌다. 한 건씩 잡으면 선점 시간은 그 건 하나의 호출 시간만 버티면 된다. 처리량은 스케줄러 주기가 아니라 ERP 응답 시간과 Pod 수로 정해진다. 실행은 `fixedDelay` 1초라 같은 Pod 안에서는 겹치지 않고, Pod끼리는 `SKIP LOCKED`로 서로 다른 건을 가져간다. dualwrite 처리 워커(잡 하나 = 정산 하나)와 같은 단위다.
- 구현 중: 중계와 ERP 전송·확인을 다른 스케줄러로 나눴다(`OutboxScheduler`, `ErpSyncScheduler`). 전송이 없을 때까지 반복하므로 ERP가 느리면 한 실행이 길어지는데, 같은 메서드에 있으면 그동안 중계가 기다려 새 입출고가 늦게 넘어갔다. 스케줄러가 셋(중계, ERP, 선점 만료 정리)이라 스레드를 3개로 늘렸다.
- 구현 중: 스케줄러 세 클래스(`OutboxScheduler`, `ErpSyncScheduler`, `ErpSyncRecoveryScheduler`)를 `app/workflow/WorkflowScheduler` 하나로 모았다. 메서드마다 `@Scheduled`가 따로라 주기, 스레드, 예외 처리는 그대로 독립이고, 중계 → 전송·확인 → 복구 흐름을 한 파일에서 본다. `@EnableScheduling`도 이 클래스 한곳에 둔다.
- 구현 중: 중계를 CTE 한 문장(20건)에서 한 건씩, 없을 때까지 반복으로 바꿨다(`OutboxRelayer`, `OutboxRelayService`). 한 문장이면 넘기지 못하는 메시지 하나 때문에 20건이 함께 롤백되고, 그 메시지가 늘 맨 앞이라 뒤의 모든 메시지가 막혔다. 실패한 건은 새 트랜잭션에서 `attempts`, `last_error`를 남기고 `available_at`을 백오프만큼(DB 시계) 미루며, 5번째 실패면 FAILED로 멈춘다(R13, TC-48-27~29). 재시도 규칙은 `erp_sync`와 같게 `RetryPolicy`로 모았다. outbox의 `locked_at`, `locked_by`는 지웠다. 중계는 외부 호출 없이 트랜잭션 하나로 끝나서 쓰지 않는다(dualwrite에서 outbox에 있는 건 Redis 발행이 트랜잭션 밖이라서다). `V48_1`이 아직 머지 전이라 새 마이그레이션 없이 `V48_1`을 고쳤다.
- 구현 중: 중계를 한 건씩에서 dualwrite 발행 배치처럼 20건 선점(`locked_at`, `locked_by`, 30초) 후 한 건씩 넘기는 방식으로 바꿨다. 선점 표시가 없으면 id를 한 번에 가져와도 커밋과 함께 잠금이 풀려 여러 Pod가 같은 건을 받는다. 그래서 앞에서 뺐던 outbox의 `locked_at`, `locked_by`를 다시 넣었다. 넘길 때와 실패를 기록할 때 내 선점인지 확인한다(TC-48-30).
- 구현 중: 중계 코드를 dualwrite 발행 배치 모양으로 줄였다. 한 번 실행에 한 묶음(20건), 실패하면 그 건만 기록하고 다음 건. 실패 분류와 결과 레코드(`OutboxRelaySummary`)는 지웠다. 실패 원인은 `last_error`에 남는다.
- 구현 중: outbox의 재시도 소진 상태를 FAILED에서 REQUIRES_REVIEW로 바꿨다. dualwrite `payouts`처럼 FAILED는 확정 실패(ERP 거절 같은 결론), REQUIRES_REVIEW는 결론 없이 자동 처리를 그만둔 것으로 나눈다. 중계에는 확정 실패가 없다. `erp_sync`는 재시도를 다 써도 PENDING(안 보냄)과 UNKNOWN(보냈는지 모름)의 구분이 처리 방법을 정하므로 상태를 그대로 두고 `next_retry_at` NULL로 멈춘다.
- 구현 중: ERP 전송·확인도 중계처럼 결과 레코드(`ErpSendSummary`)를 지우고 처리한 건수만 돌려준다. 스케줄러는 부르기만 하고, 실패를 모아 로그로 남기던 반복문을 뺐다. 실패의 결과는 DB(`last_error`, `failed_at`, 선점)에 남고, 자동 처리를 멈춘 건은 지표 리스너가 WARN으로, 선점 만료로 UNKNOWN이 된 건은 복구 스케줄러가 WARN으로 남긴다.
- 구현 중: `relayDue`, `sendDue`, `checkUnknown`이 처리한 건수를 돌려주지 않게(`void`) 했다. 쓰는 곳이 테스트뿐이었다. 테스트는 건수 대신 ERP가 받은 요청, 조회 기록, DB 상태로 확인한다.
- 구현 중: ERP를 부른 뒤 결과 저장이 실패하면 실패 종류와 상관없이 바로 UNKNOWN으로 둔다(dualwrite가 전송 뒤 `markPending`이 실패해도 BROADCAST_UNKNOWN으로 두는 것과 같다). 전에는 원인 모를 실패를 PENDING인 채로 멈춰서, 사람이 "안 보냄"으로 읽고 다시 보낼 수 있었다. UNKNOWN 표시마저 실패하면(DB 다운) 예외가 스케줄러로 올라가고, 선점 만료 뒤 UNKNOWN이 된다. 실패 분류(`handleFailure`)는 조회 결과 저장(`checkOne`)에만 남는다 (TC-48-23, 25).
- 구현 중: 조회 간격을 전송 재시도와 나눴다(`ErpLookupPolicy`, `inventory.erp.lookup-backoff`, 기본 10초, 1분, 5분, 15분, 1시간). 전송과 같은 1, 2, 4, 8초를 쓰면 ERP가 조금만 늦게 반영해도 약 15초 만에 사람 확인으로 넘어갔다. 못 찾은 횟수는 `lookups`에 센다 (TC-48-16, 33, 34).
- 구현 중: 조회해서 찾으면 ERP에 들어간 내용(회사, 상품, 구분, 수량)을 보낸 payload와 비교해, 다르면 SENT로 두지 않고 사람 확인으로 멈춘다(MISMATCH). dualwrite가 receipt의 전송 내용을 기대와 비교하는 것과 같다 (TC-48-32, 35).
- 구현 중: `erp_sync`의 시각(`next_retry_at`, `sent_at`, `failed_at`)을 JVM 시계가 아니라 결과를 저장하는 트랜잭션의 DB 시계(`now()`)로 정한다. 배치가 DB 시계와 비교하므로 JVM 시계가 늦으면 같은 실행에서 바로 다시 집었다. `ErpSync.retryLater`는 `retrySend`, `markUnknown`, `missLookup`으로 나눴다.
- 구현 중: `LoggingErpClient`를 `StubErpClient`로 이름을 바꿨다. ERP에 아무것도 보내지 않고 정해진 답(전송 성공, 조회 없음)만 돌려주는 대역이라는 게 이름에 드러나야 한다. 실제 구현은 통신 방식으로 이름을 붙인다(예: `HttpErpClient`).
- 구현 중: 결과 이벤트 `ErpSyncRecorded`를 `ErpStockMovementOutcome`으로, 지표 리스너를 `ErpStockMovementMetrics`로, 지표를 `inventory.erp.stock-movement{outcome}`으로 바꿨다. "erp_sync 행이 기록됐다"로 읽혔는데, 실제로는 입출고 한 건을 ERP로 보낸 결과(성공률, 오류율을 보는 용도)다.
