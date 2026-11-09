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
    CONSTRAINT ck_outbox_message_status CHECK (status IN ('PENDING', 'PUBLISHED')),
    CONSTRAINT uk_outbox_message_event UNIQUE (aggregate_type, aggregate_id, event_type)
);
-- 전달 배치가 매번 찾는 조건: 아직 안 보냈고 지금 보낼 수 있는 것, 오래된 것부터
CREATE INDEX ix_outbox_message_pending ON outbox_message (available_at, id) WHERE status = 'PENDING';
```
- `UNIQUE (aggregate_type, aggregate_id, event_type)`: 원장 한 건에 STOCK_CHANGED 메시지는 하나뿐이다. 코드가 같은 원장으로 두 번 넣으려 하면 DB가 막는다.
- `aggregate_id`에 FK를 걸지 않는다. 다른 종류의 메시지(상품 등)를 같은 테이블에 받을 수 있게 하기 위해서다.
- `payload`는 넣는 순간 확정한다. 다시 전달할 때 현재 재고로 다시 만들면 그 사이의 다른 입출고가 섞여 내용이 달라진다.
- `attempts`, `available_at`, `locked_at`, `locked_by`, `published_at`, `last_error`는 PR 2의 전달 배치가 쓴다. 이 PR에서는 기본값으로만 들어간다. 컬럼을 미리 만드는 이유는 PR 2에서 테이블 변경 없이 배치만 추가하기 위해서다.
- `status`는 PENDING, PUBLISHED 두 값으로 시작한다. 실패 상태는 PR 2에서 재시도 정책을 정할 때 새 마이그레이션으로 추가한다 (결정 필요 2).
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

### 비즈니스 규칙
- R1. 입고, 출고가 커밋되면 그 원장 한 건에 대한 STOCK_CHANGED 메시지가 PENDING으로 하나 남는다.
- R2. 입고, 출고가 실패해 롤백되면 메시지도 남지 않는다.
- R3. 같은 멱등성 키로 다시 온 요청은 메시지를 다시 남기지 않는다.
- R4. 원장 한 건에 같은 종류의 메시지는 한 행만 있을 수 있다 (DB 제약).
- R5. payload는 원장에 기록된 값과 같다.

### 예외 케이스

| 상황 | 결과 |
|---|---|
| 재고 부족으로 출고 409 | 메시지 없음 |
| 없는 상품 출고 404 | 메시지 없음 |
| 같은 멱등성 키, 같은 내용 재전송 | 200, 메시지 그대로 1건 |
| 같은 원장으로 STOCK_CHANGED를 두 번 INSERT (SQL 직접) | 유니크 위반 |
| status에 정해지지 않은 값 (SQL 직접) | CHECK 위반 |

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

### PR 계획

| PR | 브랜치 | 범위 | 포함 테스트 케이스 |
|---|---|---|---|
| 1 | `feat/48-external-client-idempotency` | outbox 테이블, 입출고 트랜잭션에서 메시지 기록 (`Refs #48`) | TC-48-01 ~ TC-48-07 |
| 2 | `feat/48-outbox-relay` | 전달 배치: 선점(`FOR UPDATE SKIP LOCKED` + 잠금 만료), 외부 호출 포트, 재시도, 실패 상태 (`Closes #48`) | PR 2 착수 전 이 명세에 추가 |

### 결정 필요
1. **(PR 2) 상품 단위 순서 보장.** 변동량을 보내면 합계는 순서와 상관없이 맞는다. 받는 쪽이 "중간에 음수가 되면 거절"하는 규칙을 가지면 순서가 필요하다. 받는 쪽 규칙이 정해지면 결정한다.
2. **(PR 2) 재시도와 실패.** 재시도 횟수와 백오프, 실패 상태 이름(FAILED 등), 실패 건 확인 방법(조회 API, 로그, 메트릭).
3. **(PR 2) 받는 쪽이 여럿일 때.** 외부 ERP와 모니터링처럼 대상이 둘 이상이면, 대상마다 전달 상태를 따로 두는 테이블(`(outbox_message_id, target)` UNIQUE)이 필요하다. 대상이 하나면 outbox 상태만으로 충분하다.

### 변경 이력
- 구현 중: `OutboxMessage.stockChanged`가 `StockTransaction` 대신 원장 id, `StockChange`, 발생 시각을 받는다. 원장 엔티티는 저장해야 id와 시각이 생겨서, 엔티티를 받으면 단위 테스트(TC-48-07)를 쓸 수 없다.
- 구현 중: payload의 `occurredAt`을 ISO-8601 문자열로 바꿨다. Hibernate JSON 변환이 `OffsetDateTime`을 숫자로 쓴다. native INSERT 대체안은 필요 없었다.
- 구현 중: TC-48-01~03의 Given을 테스트 공통 준비 데이터(상품 하나, 재고 5)에 맞췄다.
