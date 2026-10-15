# 기능 명세 — 상품 입고 (#12)

### 요구사항 해석
- 상품 코드와 수량으로 입고하면 그 상품의 재고가 수량만큼 늘어난다.
- 처음 보는 상품 코드면 상품(이름은 상품 코드와 같은 값)과 수량 0의 재고 행을 만든 뒤 입고한다. 상품명은 상품명 수정 이슈(#14)에서 바꿀 수 있다.
- 입고마다 재고 변동 원장(`stock_transaction`)에 한 건을 남긴다. 상품, 구분(입고), 수량, 변경 전후 재고, 요청자, 시각을 기록한다. 원장 조회 API는 #21에서 만든다.
- 요청자 ID는 반드시 받는다. 인증은 이 과제 범위 밖이라, 앞단(게이트웨이)이 인증 후 넣어 준다고 가정하고 헤더로 받는다.
- 여러 Pod가 같은 상품에 동시에 입고해도 수량이 빠지지 않고, 처음 보는 상품이 동시에 들어와도 상품은 하나만 생긴다. 앱 메모리가 아니라 DB(행 잠금, 유니크 제약)로 보장한다.
- 중복 요청 방지(멱등성 키)는 #15에서 원장에 컬럼을 추가해 다룬다. 이 이슈의 원장에는 키 컬럼을 두지 않는다.

**용어**

| 용어 | 뜻 |
|---|---|
| 원장 (`StockTransaction`) | 재고가 바뀔 때마다 한 건씩 쌓는 변동 기록. 수정, 삭제하지 않는다. 현재 재고는 원장 수량을 모두 더한 값과 같아야 한다 |
| 요청자 ID (`requesterId`) | 입고를 요청한 사람이나 시스템의 식별자. 이 서비스는 값을 기록만 하고 누구인지 확인하지 않는다 |

### 설계

**데이터** — 원장 테이블을 추가한다 (`inventory/schema`의 `V12_1__create_stock_transaction.sql`)

```sql
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
```
- `quantity`는 항상 양수이고 방향은 `type`이 나타낸다. `-3`처럼 부호로 방향을 표현하면 `quantity > 0` 같은 단순한 제약을 걸 수 없고, 0이나 부호가 틀린 값이 섞여도 DB가 막지 못한다.
- `ck_stock_transaction_balance`는 "변경 후 = 변경 전 ± 수량"을 DB가 직접 확인한다. 예를 들어 앱 버그로 전 5, 수량 3, 후 9를 쓰려 하면 INSERT가 거절된다. 원장끼리 이어지는지(앞 건의 후 = 다음 건의 전)는 행 하나만 보는 CHECK로 확인할 수 없어, 재고 행 잠금 아래에서 기록하는 것으로 보장한다.
- `type`에 `OUTBOUND`를 지금 넣는 이유: 출고(#13)가 같은 요구사항에서 나온 등록된 이슈다. 나중에 넣으려면 CHECK 제약을 지우고 다시 만드는 마이그레이션이 하나 더 필요하다. 출고 규칙(후 = 전 - 수량)도 이 제약 하나에 함께 둔다.
- `updated_at`이 없다. 원장은 쓰고 나면 바뀌지 않는다.
- 상품별 이력 조회용 인덱스(`product_id, created_at`)는 조회 API를 만드는 #21에서 쿼리 모양을 보고 추가한다.

**도메인** (`inventory/core`)

| 패키지 | 클래스 | 역할 |
|---|---|---|
| `product.entity` | `Product.register(ProductCode)` | 새 상품. 이름은 상품 코드 값 (R2) |
| `product.port` | `ProductStore` | `findByProductCode(ProductCode)`, `saveIfAbsent(Product)` → `boolean`. 같은 코드가 없을 때만 저장하고, 이 트랜잭션이 실제로 넣었으면 true. 동시 호출에도 한 건 |
| `inventory.entity` | `Quantity` | `plus(Quantity)` 추가. `Math.addExact`로 overflow도 예외 |
| `inventory.entity` | `Inventory.create(productId)` | 상품이 새로 등록될 때 함께 만드는 재고. 수량 0 |
| `inventory.entity` | `Inventory.increase(Quantity)` → `StockChange` | 0이면 `INVALID_QUANTITY`. 변경 전후 수량을 담은 `StockChange`를 돌려준다 |
| `inventory.entity` | `StockChange` (record) | `productId`, `type`, `amount`, `before`, `after`. 재고가 바뀐 결과. 원장에 그대로 넘긴다 |
| `inventory.entity` | `StockChangeType` (enum) | `INBOUND`, `OUTBOUND`. 재고가 정하는 변경 방향이라 `StockChange`와 함께 둔다 |
| `inventory.port` | `InventoryStore` | `findByProductIdForUpdate(productId)`, `save(Inventory)`(새 재고 저장) 추가 |
| `inventory.model` | `InboundCommand` (record) | `productCode`, `quantity`, `requesterId` |
| `inventory` | `ProductRegistrar.getOrRegister(ProductCode)` | 상품을 찾고, 없으면 수량 0인 재고와 함께 등록 (R11) |
| `inventory` | `InventoryService.inbound(InboundCommand)` | 흐름 조율 (아래 흐름) |
| `transaction.entity` | `StockTransaction` (엔티티) | `record(StockChange, RequesterId)`. 원장 한 건 |
| `transaction.entity` | `RequesterId` (VO, record) | 공백이 아닌 1~64자 (R4) |
| `transaction.port` | `StockTransactionStore` | `save(StockTransaction)` |

`ErrorCode` 추가: `INVALID_REQUESTER_ID`(INVALID).

**db** (`inventory/db`)
- `ProductStoreAdapter.findByProductCode`: 상품 코드로 조회.
- `ProductStoreAdapter.saveIfAbsent`: `INSERT ... ON CONFLICT (product_code) DO NOTHING`. 넣은 행 수가 1이면 true.
- `InventoryStoreAdapter.findByProductIdForUpdate`: `@Lock(PESSIMISTIC_WRITE)` → `SELECT ... FOR UPDATE`.
- `InventoryStoreAdapter.save`: `EntityManager.persist`. `Inventory`는 id(`productId`)를 직접 넣는 엔티티라 `JpaRepository.save`를 쓰면 새 객체로 보지 않고 `merge`한다. 그러면 INSERT 전에 SELECT가 한 번 더 나가고, 넘긴 객체가 아니라 복사본이 영속 상태가 되어 이후 변경이 저장되지 않을 수 있다.
- `StockTransactionStoreAdapter.save`: `JpaRepository.save`.
- 잠금 대기 상한: `inventory-db.yml`에 `spring.datasource.hikari.connection-init-sql: SET lock_timeout = '3s'` (아래 "잠금 대기 상한").

**API** (`app`)

`POST /api/v1/inventory/{productCode}/inbound`
```http
POST /api/v1/inventory/TS-BASIC-BLK-M/inbound
X-User-Id: worker-01
Content-Type: application/json

{"quantity": 3}
```

| 상황 | 상태 | `code` |
|---|---|---|
| 입고 성공 (기존 상품, 새 상품 모두) | 200 | `SUCCESS`, `data` = `null` |
| 상품 코드 형식이 틀림 | 400 | `INVALID_PRODUCT_CODE` |
| `X-User-Id` 헤더 없음 | 400 | `BAD_REQUEST` |
| `X-User-Id`가 공백이거나 65자 이상 | 400 | `INVALID_REQUESTER_ID` |
| 수량 없음, 1 미만, 1,000,000 초과, 정수가 아님 | 400 | `BAD_REQUEST` (`data`에 틀린 항목, JSON 역직렬화 실패는 `data` 없음) |
| 재고 행 잠금 대기 3초 초과 | 503 | `SERVICE_UNAVAILABLE` + `Retry-After: 1` (#20에서 만든 매핑) |

- `InboundController`가 입고만 맡고 `InventoryService`에 위임한다. 조회는 기존 `InventoryController`에 그대로 둔다. 상태를 바꾸는 입고와 조회를 나누고, 출고(#13)를 `OutboundController`로 붙일 때 입고 파일을 고치지 않게 하기 위해서다.
  - 컨트롤러가 기댈 유스케이스 인터페이스(`InboundUsecase`)는 두지 않는다. 구현이 `InventoryService` 하나뿐이고 테스트도 실제 DB로 하므로, 인터페이스는 파일과 따라가는 단계만 늘린다 (아키텍처 규칙 3.6).
- `InboundRequest(@NotNull @Positive @Max(1_000_000) Long quantity)`, `toCommand(productCode, requesterId)`.
- 헤더 이름은 `app`의 `ApiHeaders.REQUESTER_ID`에 한 번만 둔다. 출고(#13)와, 요청자를 기록하게 되면 상품명 수정(#14)도 같은 헤더를 쓴다. 헤더에는 Bean Validation을 붙이지 않고 형식 검증은 `RequesterId` VO 한 곳에서 한다. API마다 `@NotBlank @Size(max = 64)`를 반복하면 한 곳만 상한을 바꾸는 식으로 어긋날 수 있다. 헤더가 아예 없으면 Spring이 400 `BAD_REQUEST`를 낸다.
- 소수 수량(`1.5`)은 `spring.jackson.deserialization.accept-float-as-int: false`로 400을 낸다.
- 성공 응답에 데이터를 담지 않는다. 입고는 재고를 바꾸는 명령이라 결과를 돌려주지 않고, 바뀐 재고가 필요하면 조회 API로 읽는다 (명령과 조회 분리). 이번 입고의 변경 전후 재고는 원장에 남아 이력 조회(#21)로 볼 수 있다.
  - 대안: 입고 결과(입고 수량, 변경 전후 재고)를 응답에 담기. 클라이언트가 조회를 한 번 덜 하지만, 같은 요청이 다시 오면 처음 응답과 같은 값을 돌려줘야 하는 멱등성(#15)에서 첫 결과를 저장하고 다시 만들어 줘야 한다. 데이터가 없으면 다시 온 요청에도 200만 돌려주면 된다.
- 성공은 200이다. 새 상품이 함께 생겨도 `Location`으로 알려 줄 새 자원 주소가 없다.

**흐름** — `InventoryService.inbound`, `@Transactional` 하나

```java
// InventoryService.inbound
// 상품 (Product) — 없으면 수량 0인 재고와 함께 등록
final Product product = productRegistrar.getOrRegister(command.productCode());
// 재고 (Inventory) — 행 잠금 후 증가
final Inventory inventory = inventoryStore.findByProductIdForUpdate(product.getId())
    .orElseThrow(() -> new IllegalStateException("상품은 있는데 재고 행이 없습니다. productId=" + product.getId()));
final StockChange change = inventory.increase(command.quantity());
// 원장 (StockTransaction)
stockTransactionStore.save(StockTransaction.record(change, command.requesterId()));

// ProductRegistrar
public Product getOrRegister(final ProductCode productCode) {
    return productStore.findByProductCode(productCode).orElseGet(() -> register(productCode));
}

private Product register(final ProductCode productCode) {
    final boolean created = productStore.saveIfAbsent(Product.register(productCode));
    final Product product = productStore.findByProductCode(productCode).orElseThrow();
    if (created) {
        inventoryStore.save(Inventory.create(product.getId()));
    }
    return product;
}
```
1. 상품 조회. 없으면 `INSERT ... ON CONFLICT DO NOTHING` 후 다시 조회하고, 이 트랜잭션이 상품을 넣었을 때만 수량 0인 재고를 `persist`한다
2. 재고 행 `SELECT ... FOR UPDATE` → 다른 입고는 여기서 커밋까지 기다린다. 1에서 `persist`한 재고는 Hibernate가 이 조회 전에 먼저 INSERT한다(자동 flush, `FlushMode.AUTO`)
3. `inventory.increase` → 커밋 때 변경 감지로 `UPDATE`
4. 원장 `INSERT` (같은 트랜잭션이라 재고와 원장이 함께 커밋되거나 함께 롤백된다)

**처음 보는 상품이 두 Pod에 동시에 들어올 때** (READ COMMITTED)

| 시점 | Pod A | Pod B |
|---|---|---|
| t1 | 상품 조회 → 없음 | 상품 조회 → 없음 |
| t2 | 상품 INSERT (아직 커밋 전) | |
| t3 | | 같은 코드 INSERT → A의 커밋 전 행 때문에 유니크 인덱스에서 기다림 |
| t4 | 넣었으므로(`created = true`) 재고 생성, 잠금, 증가, 원장, **커밋** | |
| t5 | | 기다림이 풀리고 충돌로 판단 → 아무것도 안 넣음(`created = false`) → 재고를 만들지 않음 |
| t6 | | 상품 다시 조회 → A가 커밋한 행이 보임 → 재고 잠금 조회 → A가 만든 재고 위에 증가 |

결과는 상품 1건, 재고 = 두 수량의 합, 원장 2건이다. READ COMMITTED는 문장마다 새로 커밋된 데이터를 보기 때문에 t6의 재조회가 A의 행을 찾는다. A가 롤백되면 B의 기다리던 INSERT가 들어가서(`created = true`) B가 재고를 만든다. 한 트랜잭션이 잠그는 재고 행은 하나뿐이라 입고끼리 서로를 기다리는 순환(데드락)이 생기지 않는다.

**상품과 재고를 함께 만드는 규칙을 어디에 두나** — `ProductRegistrar` (도메인 서비스)
- "상품이 생기면 수량 0인 재고도 생긴다"(R11)는 도메인 규칙이다. 상품을 만드는 방법을 `getOrRegister` 하나로 두어, 나중에 상품을 만드는 다른 기능이 생겨도 같은 메서드를 거치면 재고가 빠지지 않는다. 입고 서비스는 "등록, 잠금 후 증가, 원장 기록" 흐름만 남는다.
- 재고는 상품을 **실제로 넣은 트랜잭션만** 만든다(`created`). 다른 트랜잭션이 먼저 넣었다면 그쪽이 같은 트랜잭션에서 재고도 만들어 커밋했다. 그래서 재고 INSERT에는 `ON CONFLICT`가 필요 없고, 두 요청이 같은 재고를 만들려다 PK 위반이 나는 일도 없다.
- `inventory` 패키지에 둔다. 의존 방향이 `inventory` → `product` 한쪽이라, `product`에 두면 `product`가 `InventoryStore`를 알게 되어 두 패키지가 서로를 참조한다.
- 재고 잠금 조회가 비면 고쳐 넣지 않고 `IllegalStateException`(500)으로 드러낸다. 이 상태는 앱을 거치지 않고 상품만 넣은 데이터 손상이다. 조용히 재고를 만들면 그런 데이터에 동시 입고가 왔을 때 둘 다 재고를 만들려다 PK 위반이 나서, 원인을 알기 어려운 500이 된다.
- 대안 1: `InventoryService`의 private 메서드. 지금은 부르는 곳이 입고 하나라 코드 양은 같지만, 규칙이 입고 흐름 안에 묻혀서 상품을 만드는 다른 기능이 생기면 재고 생성을 빠뜨릴 수 있다.
- 대안 2: 상품 등록 이벤트를 발행하고 재고 쪽 리스너가 재고를 만든다. 재고는 같은 요청 안에서 바로 잠가야 해서 같은 트랜잭션의 동기 리스너만 쓸 수 있고, `created`일 때만 발행하는 조건도 그대로 남는다. 흐름을 따라가기만 어려워진다.
- 대안 3: 상품 어댑터가 `WITH ... INSERT` 한 문장으로 상품과 재고를 함께 넣는다. 왕복은 줄지만 상품 어댑터가 `inventory` 테이블에 쓰게 되고, 규칙이 db 모듈 SQL 안에 숨는다.
- 대안 4: `product`에서 `inventory`로 가는 `DEFERRABLE INITIALLY DEFERRED` 외래 키로, 재고 없는 상품은 커밋 자체를 막는다. 상품과 재고가 서로를 참조하는 순환 외래 키가 되어 SQL로 데이터를 넣고 지우기가 번거롭다. 상품을 만드는 길이 입고 하나인 지금은 두지 않는다.

**왜 상품을 먼저 조회하고 없을 때만 INSERT하나** (대안: 항상 `INSERT ... ON CONFLICT DO NOTHING` 후 조회)
- 입고는 대부분 이미 있는 상품에 온다. 항상 INSERT를 시도하면 매번 충돌로 끝나는 INSERT가 한 번 더 나가고, `GENERATED ALWAYS AS IDENTITY`의 번호도 충돌할 때마다 하나씩 버려진다. 예를 들어 기존 상품에 입고가 1만 번 오면 새 상품의 `id`가 1만 칸 건너뛴다.
- 먼저 조회하면 기존 상품은 SELECT 한 번으로 끝나고, INSERT는 처음 보는 상품일 때만 나간다. 조회와 INSERT 사이에 다른 Pod가 같은 상품을 넣어도 `ON CONFLICT DO NOTHING`과 재조회가 처리하므로 정합성은 같다.

**왜 상품 INSERT는 `ON CONFLICT DO NOTHING`인가** (대안: 그냥 INSERT하고 유니크 위반을 잡아 재조회)
- PostgreSQL은 제약 위반이 나면 그 트랜잭션을 더 쓸 수 없게 만든다. 위반을 잡아 같은 트랜잭션에서 다시 조회하면 "current transaction is aborted" 오류가 난다. 다시 하려면 트랜잭션을 처음부터 새로 열어야 해서 재시도 코드가 필요하다.
- `ON CONFLICT DO NOTHING`은 위반을 일으키지 않고 "넣지 않음"으로 끝나서, 같은 트랜잭션에서 바로 재조회할 수 있다.
- 대안: 조회해서 없으면 그냥 `save`. 조회와 INSERT 사이에 다른 Pod가 같은 상품을 넣을 수 있다. 실제로 처음 보는 상품에 20건을 동시에 입고하면(TC-12-14) 200이 11건, `uk_product_product_code` 위반 500이 9건이었다. 뒤 요청도 유니크 인덱스에서 앞 요청을 기다리는 것은 같지만, 기다림이 끝난 뒤 일반 INSERT는 오류를 내고 `ON CONFLICT DO NOTHING`은 0행으로 넘어간다.
- 같은 문제를 Django `get_or_create`, Rails `create_or_find_by`는 세이브포인트 안에서 INSERT하고 유니크 위반을 잡아 다시 조회하는 방식으로 푼다. JPA는 flush 중 예외가 나면 그 영속성 컨텍스트를 더 쓸 수 없어 이 방식이 맞지 않고, PostgreSQL이 이 경합을 위해 제공하는 `ON CONFLICT`를 쓴다.

**왜 재고 수량을 `UPDATE inventory SET quantity = quantity + ?`로 바꾸지 않나** (대안: 잠금 없이 원자적 UPDATE 한 문장)
- 한 문장 UPDATE도 동시 입고의 수량을 빠뜨리지 않는다. 하지만 원장에 남길 변경 전 재고를 얻으려면 `RETURNING`으로 받은 값에서 거꾸로 계산해야 하고, "수량은 0이면 안 된다" 같은 규칙이 SQL 안으로 들어간다.
- 아키텍처 규칙대로 행을 잠그고 엔티티 메서드(`increase`)로 바꾸면 규칙은 도메인에, 순서 보장은 DB 행 잠금에 둔다. 출고(#13)의 "재고보다 많이 나갈 수 없다"도 같은 모양(`decrease`)으로 들어간다.

**잠금 대기 상한** — 커넥션마다 `SET lock_timeout = '3s'`
- 잠금을 3초 넘게 기다리면 PostgreSQL이 그 문장을 실패시키고(`55P03`), Spring이 `PessimisticLockingFailureException`으로 바꿔 #20의 503 매핑이 응답한다. 상한이 없으면 잠금이 오래 잡힌 상황에서 요청이 계속 쌓여 커넥션 풀(10개)이 바닥나고, 그 상품과 상관없는 조회까지 커넥션을 못 얻어 멈춘다.
- 대안 1: 잠금 조회에 `jakarta.persistence.lock.timeout` 힌트. PostgreSQL에는 `FOR UPDATE`에 대기 시간을 주는 문법이 없어 힌트가 실제로 걸리는지가 Hibernate 버전에 달려 있다. 또 힌트는 그 조회에만 걸려서, t3처럼 상품 INSERT가 유니크 인덱스에서 기다리는 경우는 막지 못한다.
- 대안 2: 트랜잭션마다 `SET LOCAL lock_timeout`. 잠그는 서비스 메서드마다 한 줄씩 넣어야 하고, 빠뜨려도 티가 나지 않는다.
- 커넥션 초기화로 걸면 설정 한 줄로 모든 잠금 대기에 적용된다. Flyway도 같은 커넥션 풀을 쓰므로 마이그레이션의 잠금 대기도 3초에서 끊긴다. 배포 중 마이그레이션이 운영 트래픽이 잡은 잠금을 오래 기다리며 그 뒤의 요청까지 줄 세우는 것보다, 빨리 실패하고 다시 배포하는 편이 낫다.
- 3초는 커넥션 풀 대기 상한(`connection-timeout: 3000`)과 같게 맞춘 값이다. 입고 한 건은 수 ms에 끝나므로, 정상 경합으로 3초를 넘기는 일은 없다.

**왜 요청자 ID를 헤더로 받나** (대안: 요청 본문의 `requesterId` 필드)
- 요청자는 "무엇을 입고하나"가 아니라 "누가 요청했나"다. 인증을 붙이면 앞단이 토큰에서 꺼내 헤더로 넣어 주거나 서버가 토큰에서 직접 읽게 되는데, 헤더로 받아 두면 그때 컨트롤러 한 곳만 바뀌고 입고, 출고 요청 본문은 그대로다.
- 본문에 두면 클라이언트가 원하는 값을 마음대로 적는 구조가 API 계약으로 굳는다.

**왜 상품 코드를 경로에 두나** (대안: `POST /api/v1/inventory/inbound` + 본문 `{"productCode", "quantity"}`)
- 조회가 `GET /api/v1/inventory/{productCode}`라서, 같은 상품의 재고를 바꾸는 입고, 출고도 그 아래(`/{productCode}/inbound`, `/{productCode}/outbound`)에 두면 경로만 보고 대상이 보인다.
- 상품 코드 형식 검증이 조회와 같은 경로(`ProductCode` VO → 400 `INVALID_PRODUCT_CODE`)로 처리된다.
- 여러 상품을 한 번에 입고하는 묶음 요청은 범위 밖이다. 생기면 본문에 목록을 받는 별도 경로를 둔다.

### 비즈니스 규칙
- R1. 등록된 상품에 입고하면 재고가 입고 수량만큼 늘어난다.
- R2. 등록되지 않은 상품 코드로 입고하면 이름이 상품 코드와 같은 상품과 수량 0의 재고를 만든 뒤 입고한다.
- R3. 입고마다 원장 한 건(상품, `INBOUND`, 수량, 변경 전후 재고, 요청자, 시각)이 재고 변경과 같은 트랜잭션에 남는다.
- R4. 요청자 ID는 공백이 아닌 1~64자다. 없으면 입고하지 않는다.
- R5. 입고 수량은 1 이상 1,000,000 이하의 정수다.
  - 상한 이유: 실수로 자릿수를 더 친 요청(예: `100` 대신 `1000000000`)이 그대로 들어가면 재고가 크게 틀어지고, 바로잡으려면 같은 크기의 출고를 다시 해야 한다. 한 번에 들어오는 물량으로 넉넉한 100만에서 끊는다.
- R6. 상품 코드 형식이 틀리면 입고하지 않는다 (#7의 형식 규칙).
- R7. 같은 상품에 동시에 입고해도 최종 재고는 입고 수량의 합이고, 원장 수량 합계와 같다.
- R8. 처음 보는 상품 코드에 동시에 입고해도 상품은 하나만 생긴다.
- R9. 원장의 수치가 맞지 않는 행(수량 0 이하, 전후 재고 음수, 후 ≠ 전 ± 수량, 알 수 없는 구분)은 DB에 저장되지 않는다.
- R10. 재고 행 잠금을 3초 넘게 기다리면 입고하지 않고 503을 돌려준다.
- R11. 상품이 생기면 수량 0인 재고도 같은 트랜잭션에서 생긴다. 재고 행이 없는 상품에 입고하면 데이터 손상으로 보고 입고하지 않는다.

### 예외 케이스

| 상황 | 결과 |
|---|---|
| 상품 코드 형식이 틀림 | 400 `INVALID_PRODUCT_CODE`, 상품이 생기지 않음 |
| `X-User-Id` 없음 | 400 `BAD_REQUEST`, 재고 그대로 |
| `X-User-Id` 공백, 65자 이상 | 400 `INVALID_REQUESTER_ID`, 재고 그대로 |
| 수량 없음, 0, 음수, 1,000,001 이상, `1.5`, 문자열 | 400 `BAD_REQUEST`, 재고 그대로 |
| 다른 트랜잭션이 재고 행을 3초 넘게 잠금 | 503 `SERVICE_UNAVAILABLE`, `Retry-After: 1`, 재고 그대로 |
| 상품은 있는데 재고 행이 없음 (앱을 거치지 않은 데이터) | 500 `INTERNAL_SERVER_ERROR`, 재고 행과 원장을 만들지 않음 |

### 테스트 케이스

| ID | 대상 규칙 | Given | When | Then | 종류 |
|---|---|---|---|---|---|
| TC-12-01 | R9 | 상품 하나 | SQL로 원장 INSERT: 수량 0, 전 -1, 입고인데 후 ≠ 전 + 수량, 출고인데 후 ≠ 전 - 수량, 구분 `MOVE` | 각각 체크 제약 위반 | 통합 (PostgreSQL) |
| TC-12-02 | R9 | 상품 하나 | SQL로 원장 INSERT: 입고 전 5, 수량 3, 후 8 / 출고 전 8, 수량 3, 후 5 | 저장됨 | 통합 (PostgreSQL) |
| TC-12-03 | R2 | | `Product.register(TS-BASIC-BLK-M)` | 이름이 `TS-BASIC-BLK-M` | 단위 |
| TC-12-04 | R1 | 재고 5 | `increase(3)` | 재고 8, `StockChange`(INBOUND, 3, 전 5, 후 8) | 단위 |
| TC-12-05 | R5 | 재고 5 | `increase(0)` | `INVALID_QUANTITY`, 재고 5 | 단위 |
| TC-12-06 | R4 | | `RequesterId` 생성: `worker-01`, 64자 / null, 빈 문자열, 공백, 65자 | 앞은 생성됨, 뒤는 `INVALID_REQUESTER_ID` | 단위 |
| TC-12-07 | R1, R3 | `TS-BASIC-BLK-M` 재고 5 | `X-User-Id: worker-01`로 3 입고 | 200, `data` 없음, 재고 8, 원장 1건(INBOUND, 3, 5, 8, `worker-01`), 상품명 그대로 | 통합 (HTTP) |
| TC-12-08 | R2, R3 | 상품 없음 | `NEW-ITEM-01`에 10 입고 | 200, 상품 1건(이름 `NEW-ITEM-01`), 재고 10, 원장 1건(전 0, 후 10) | 통합 (HTTP) |
| TC-12-09 | R4 | 재고 5 | `X-User-Id` 없이 / 공백 / 65자로 입고 | 없으면 400 `BAD_REQUEST`, 공백과 65자는 400 `INVALID_REQUESTER_ID`. 재고 5, 원장 0건 | 통합 (HTTP) |
| TC-12-10 | R5 | 재고 5 | 수량 0, -1, 1000001, 없음, `1.5`, `"abc"`로 입고 | 400 `BAD_REQUEST`, 재고 5, 수량 0일 때 `data[0].field` = `quantity` | 통합 (HTTP) |
| TC-12-11 | R5 | 재고 5 | 수량 1000000 입고 | 200, 재고 1000005 | 통합 (HTTP) |
| TC-12-12 | R6 | | `ts-basic`에 입고 | 400 `INVALID_PRODUCT_CODE`, 상품 0건 | 통합 (HTTP) |
| TC-12-13 | R7 | 재고 0 | 20개 요청이 동시에 각 1~20 입고 | 모두 200, 재고 210, 원장 20건, 원장 수량 합 210 | 통합 (동시성) |
| TC-12-14 | R8 | 상품 없음 | 20개 요청이 동시에 `NEW-ITEM-01`에 각 1 입고 | 모두 200, 상품 1건, 재고 20, 원장 20건 | 통합 (동시성) |
| TC-12-15 | R10 | 재고 5, 다른 트랜잭션이 재고 행을 `FOR UPDATE`로 잡고 있음 | 입고 | 503 `SERVICE_UNAVAILABLE`, `Retry-After: 1`, 잠금이 풀린 뒤 재고 5 | 통합 (HTTP) |
| TC-12-16 | R11 | SQL로 상품 `NO-STOCK-01`만 넣고 재고 행 없음 | 입고 | 500, 재고 행 생기지 않음, 원장 0건 | 통합 (HTTP) |
| TC-12-17 | R8, R11 | 다른 트랜잭션이 `NEW-ITEM-01`의 상품과 재고를 넣고 커밋하지 않음 | 입고 → 0.5초 뒤 다른 트랜잭션 커밋 | 커밋 전에는 응답 없음(유니크 인덱스에서 기다림), 커밋 후 200. 상품 1건, 재고 행 1건(수량 3), 원장 1건 | 통합 (HTTP) |

- TC-12-01, 02는 `inventory/schema` 테스트에서 앱 없이 SQL로 검증한다 (#7의 제약 테스트와 같은 방식).
- TC-12-07 ~ 17은 `app`에서 Testcontainers PostgreSQL과 실제 HTTP로 검증한다. 원장과 재고는 `JdbcTemplate`으로 직접 읽어 확인한다.
  - 요청 하나의 결과(TC-12-07 ~ 12, 16)는 `InventoryInboundApiTest`, 여러 요청이나 다른 트랜잭션과 겹치는 경우(TC-12-13 ~ 15, 17)는 `InventoryInboundConcurrencyTest`에 둔다. 동시성 테스트는 스레드와 커넥션을 직접 다루고 느려서(잠금 대기 초과 3초) 따로 모으고, 출고와 입출고 혼합 동시성 테스트도 같은 곳에 붙인다. 공통 준비(HTTP 요청, 재고와 원장 조회)는 `InventoryApiTestSupport`에 둔다.
- TC-12-10의 `1.5`: Jackson은 기본으로 소수를 정수 필드에 넣을 때 소수점을 버린다(`1.5` → `1`). 입고 수량이 조용히 바뀌지 않도록 이 경우를 400으로 막는다.
- TC-12-17은 TC-12-14가 결과만 보는 것을 보완한다. 동시에 보내는 테스트는 요청이 우연히 어긋나면 `ON CONFLICT`에서 기다리는 경로를 타지 않고도 통과하므로, 다른 트랜잭션이 등록 중인 상태를 직접 만들어 "기다렸다가 넣지 않고(`created = false`) 그 상품과 재고를 쓴다"를 확인한다.
- 동시성 테스트(TC-12-13, 14)는 한 앱 인스턴스에 요청을 동시에 보낸다. 앱 메모리에 잠금이나 상태가 없고 정합성을 DB만으로 지키므로, 같은 DB를 쓰는 Pod 여러 대와 같은 조건이다. 여러 앱 인스턴스를 띄운 검증은 #16에서 한다.

### PR 계획

| PR | 브랜치 | 범위 | 포함 테스트 케이스 |
|---|---|---|---|
| 1 | `feat/12-stock-transaction-schema` | 명세서, 원장 테이블 마이그레이션(`V12_1`), 원장 제약 테스트 (`Refs #12`) | TC-12-01, TC-12-02 |
| 2 | `feat/12-inbound` | 도메인(`Product.register`, `Inventory.create`, `Inventory.increase`, `ProductRegistrar`, `StockTransaction`, `RequesterId`), Store와 Adapter, `lock_timeout` 설정, 입고 서비스와 API (`Closes #12`) | TC-12-03 ~ TC-12-17 |

- 나누는 이유: 원장 테이블은 앱 코드 없이 SQL만으로 완결되고 제약을 SQL로 바로 검증할 수 있다. PR 1만 머지돼도 쓰는 코드가 없어 동작이 바뀌지 않는다. 리뷰도 "원장 컬럼과 제약이 맞나"와 "입고 흐름과 동시성이 맞나"를 따로 본다.

### 결정 필요
- 요청자 ID 헤더 이름 `X-User-Id`, 길이 1~64자 (추천)
- 입고 수량 상한 1,000,000 (추천)
- API 경로 `POST /api/v1/inventory/{productCode}/inbound` (추천, 대안은 위 "왜 상품 코드를 경로에 두나")
- 잠금 대기 상한 3초, 커넥션 초기화로 설정 (추천, 대안은 위 "잠금 대기 상한")

### 변경 이력
- `StockChangeType`을 `transaction.entity`에서 `inventory.entity`로 옮김. 재고 엔티티가 `StockChange`를 만들 때 정하는 값이라, 재고가 원장 패키지를 참조하지 않게 하기 위해서 (구현 중)
- TC-12-10에 틀린 항목 이름(`quantity`) 확인 추가, 소수 수량을 막는 Jackson 설정 명시 (구현 중)
- 요청자 헤더 이름을 `ApiHeaders` 공통 상수로 옮기고 헤더의 Bean Validation을 없앰. 여러 API가 같은 헤더를 쓰므로 이름과 검증을 한 곳에 두기 위해서. 공백, 65자의 응답 code가 `BAD_REQUEST`에서 `INVALID_REQUESTER_ID`로 바뀜 (구현 중)
- 재고 행 보장(`createIfAbsent`, `INSERT ... ON CONFLICT`)을 없애고, 잠금 조회 결과가 없으면 `Inventory.create`로 수량 0인 재고를 만들어 `save`하도록 변경. 재고 생성도 도메인 팩토리로 하기 위해서. 상품 INSERT의 유니크 대기가 순서를 정해 동시 생성이 생기지 않는다 (구현 중)
- 입고 응답의 `data`를 없앰(`StockChangeResult`, `StockChangeResponse` 삭제). 입고는 상태를 바꾸는 명령이라 결과를 돌려주지 않고, 멱등성(#15)에서 다시 온 요청에 첫 결과를 다시 만들어 줄 필요가 없어지기 때문 (구현 중)
- 상품과 재고를 함께 만드는 규칙(R11)을 `ProductRegistrar`로 옮김. 상품을 실제로 넣은 트랜잭션만 재고를 만들고, 재고 잠금 조회가 비면 500으로 드러내도록 변경(TC-12-16 추가). `ProductStore.saveIfAbsent`는 넣었는지를 `boolean`으로 돌려주고, 새 재고 저장은 `merge`를 피하려고 `persist`로 변경 (구현 중)
- 새 상품 등록 경합을 순서대로 재현하는 TC-12-17 추가, TC-12-07에 상품명을 덮어쓰지 않는지 확인 추가 (구현 중)
- 입고 API를 `InboundController`로 분리. 유스케이스 인터페이스는 구현이 하나뿐이라 두지 않음 (구현 중)
- 동시성 테스트를 `InventoryInboundConcurrencyTest`로 분리하고 공통 준비를 `InventoryApiTestSupport`로 뺌 (구현 중)
