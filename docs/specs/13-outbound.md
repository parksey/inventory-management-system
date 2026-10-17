# 기능 명세 — 상품 출고 (#13)

### 요구사항 해석
- 상품 코드와 수량으로 출고하면 그 상품의 재고가 수량만큼 줄어든다.
- 재고보다 많이 출고하려 하면 출고하지 않는다. 일부만 출고하지 않는다(부분 출고 없음). 재고와 원장은 그대로다.
- 등록되지 않은 상품 코드면 404다. 입고와 달리 상품을 새로 만들지 않는다.
- 출고마다 입고와 같은 원장(`stock_transaction`)에 `OUTBOUND`로 한 건을 남긴다. 요청자 ID는 입고와 같은 헤더로 받는다.
- 여러 Pod가 같은 상품에 동시에 출고해도 재고는 음수가 되지 않고, 성공한 출고 수량만큼만 줄어든다. 입고와 같은 재고 행 잠금으로 보장한다.
- 중복 요청 방지(멱등성 키)는 #15, 입출고가 섞인 동시성 검증은 #16에서 다룬다.

### 설계

**데이터** — 변경 없음. #12의 원장 테이블이 이미 `OUTBOUND`와 "후 = 전 - 수량" CHECK 제약을 갖고 있다.

**도메인** (`inventory/core`)

| 패키지 | 클래스 | 역할 |
|---|---|---|
| `inventory.entity` | `Quantity` | `minus(Quantity)`, `isLessThan(Quantity)` 추가 |
| `inventory.entity` | `Inventory.decrease(Quantity)` → `StockChange` | 0이면 `INVALID_QUANTITY`, 재고보다 많으면 `INSUFFICIENT_STOCK`. 변경 전후를 담은 `StockChange`(`OUTBOUND`)를 돌려준다 |
| `inventory.model` | `OutboundCommand` (record) | `productCode`, `quantity`, `requesterId` |
| `inventory` | `InventoryService.outbound(OutboundCommand)` | 흐름 조율 (아래 흐름) |

`ErrorCode` 추가: `INSUFFICIENT_STOCK`(CONFLICT).

**API** (`app`)

`POST /api/v1/inventory/{productCode}/outbound`
```http
POST /api/v1/inventory/TS-BASIC-BLK-M/outbound
X-User-Id: worker-01
Content-Type: application/json

{"quantity": 3}
```

| 상황 | 상태 | `code` |
|---|---|---|
| 출고 성공 | 200 | `SUCCESS`, `data` = `null` |
| 등록되지 않은 상품 코드 | 404 | `PRODUCT_NOT_FOUND` |
| 재고보다 많이 출고 | 409 | `INSUFFICIENT_STOCK` |
| 상품 코드 형식이 틀림 | 400 | `INVALID_PRODUCT_CODE` |
| `X-User-Id` 없음 / 공백, 65자 이상 | 400 | `BAD_REQUEST` / `INVALID_REQUESTER_ID` |
| 수량 없음, 1 미만, 1,000,000 초과, 정수가 아님 | 400 | `BAD_REQUEST` |
| 재고 행 잠금 대기 3초 초과 | 503 | `SERVICE_UNAVAILABLE` + `Retry-After: 1` |

- `OutboundController`가 출고만 맡고 `InventoryService`에 위임한다. 입고(`InboundController`)와 같은 모양이다.
- `OutboundRequest(@NotNull @Positive @Max(1_000_000) Long quantity)`. 수량 상한, 요청자 헤더, 응답에 데이터를 담지 않는 이유는 #12 명세와 같다.
- 재고 부족은 409(`CONFLICT`)다. 요청 형식은 맞지만 지금 재고 상태와 맞지 않아서 거절하는 것이고, 재고가 그대로면 다시 보내도 같은 결과다. 잠금 대기 초과(503)는 잠깐 몰린 것이라 다시 보내면 성공할 수 있다는 점에서 다르다.

**흐름** — `InventoryService.outbound`, `@Transactional` 하나

```java
// 상품 (Product) — 없으면 404. 출고는 상품을 만들지 않는다
final Product product = productStore.findByProductCode(command.productCode())
    .orElseThrow(() -> BusinessException.of(PRODUCT_NOT_FOUND, "productCode=" + command.productCode().value()));
// 재고 (Inventory) — 행 잠금 후 감소. 재고 부족은 엔티티가 판단한다
final Inventory inventory = getInventoryForUpdate(product.getId());
final StockChange change = inventory.decrease(command.quantity());
// 원장 (StockTransaction)
stockTransactionStore.save(StockTransaction.record(change, command.requesterId()));
```
1. 상품 조회. 없으면 404
2. 재고 행 `SELECT ... FOR UPDATE` → 다른 입고, 출고는 여기서 커밋까지 기다린다
3. `inventory.decrease` → 재고보다 많으면 `INSUFFICIENT_STOCK`, 트랜잭션 롤백(재고, 원장 모두 그대로)
4. 원장 `INSERT`, 커밋 때 변경 감지로 재고 `UPDATE`

- 재고 행 잠금 조회와 "재고 행이 없으면 500"은 입고와 같다. 이 규칙을 한 곳에 두려고 `getInventoryForUpdate`로 뽑는다.

**같은 상품에 출고가 동시에 올 때** (재고 5, A와 B가 각각 3 출고)

| 시점 | Pod A | Pod B |
|---|---|---|
| t1 | 재고 행 `FOR UPDATE` → 5 | |
| t2 | | 재고 행 `FOR UPDATE` → A가 잡고 있어서 기다림 |
| t3 | `decrease(3)` → 2, 원장, **커밋** | |
| t4 | | 기다림이 풀리고 A가 커밋한 2를 읽음 → `decrease(3)` → `INSUFFICIENT_STOCK`, 롤백 |

결과는 A만 성공, 재고 2, 원장 1건이다. READ COMMITTED에서 `FOR UPDATE`는 잠금이 풀린 뒤 그 행의 최신 커밋 값을 다시 읽기 때문에 B가 오래된 5로 판단하지 않는다.

**왜 재고 부족 판단을 잠근 뒤 엔티티에서 하나** (대안 1: 잠그기 전에 조회해서 서비스에서 먼저 확인)
- 잠그기 전에 읽은 재고로 판단하면 위 표의 B처럼 둘 다 5를 보고 둘 다 통과한다. 재고 -1이 되려는 순간 CHECK 제약이 막지만, 그건 500이 되고 원인이 가려진다.
- 판단을 `Inventory.decrease` 안에 두면 잠긴 최신 값으로만 판단하고, "재고보다 많이 나갈 수 없다"는 규칙이 재고 엔티티 한 곳에 있다(아키텍처 규칙 3.6).

**대안 2: 조건부 UPDATE 한 문장** (`UPDATE inventory SET quantity = quantity - ? WHERE product_id = ? AND quantity >= ?`)
- 잠금 없이도 음수를 막고 왕복이 한 번 줄어든다. 하지만 원장에 남길 변경 전 재고를 `RETURNING`으로 받아 거꾸로 계산해야 하고, 0행이면 "상품이 없다"와 "재고가 부족하다"를 다시 조회해 구분해야 한다. 재고 규칙도 SQL로 옮겨 간다. 입고와 같은 "잠금 후 엔티티 메서드" 모양을 유지한다.

### 비즈니스 규칙
- R1. 등록된 상품에 출고하면 재고가 출고 수량만큼 줄어든다. 재고와 같은 수량을 출고하면 0이 된다.
- R2. 재고보다 많이 출고하면 409 `INSUFFICIENT_STOCK`이고, 재고와 원장은 그대로다.
- R3. 등록되지 않은 상품 코드로 출고하면 404 `PRODUCT_NOT_FOUND`이고, 상품을 만들지 않는다.
- R4. 출고마다 원장 한 건(상품, `OUTBOUND`, 수량, 변경 전후 재고, 요청자, 시각)이 재고 변경과 같은 트랜잭션에 남는다.
- R5. 요청자 ID, 수량, 상품 코드 형식은 입고와 같은 규칙으로 검증한다 (#12의 R4 ~ R6).
- R6. 같은 상품에 동시에 출고해도 재고는 음수가 되지 않고, 성공한 출고 수량만큼만 줄어든다. 원장 수량 합계와 재고 감소량이 같다.

### 예외 케이스

| 상황 | 결과 |
|---|---|
| 재고보다 많이 출고 | 409 `INSUFFICIENT_STOCK`, 재고 그대로, 원장 없음 |
| 등록되지 않은 상품 코드 | 404 `PRODUCT_NOT_FOUND`, 상품이 생기지 않음 |
| 상품 코드 형식이 틀림 | 400 `INVALID_PRODUCT_CODE` |
| `X-User-Id` 없음 / 공백, 65자 이상 | 400 `BAD_REQUEST` / `INVALID_REQUESTER_ID`, 재고 그대로 |
| 수량 없음, 0, 음수, 1,000,001 이상, `1.5`, `"abc"` | 400 `BAD_REQUEST`, 재고 그대로 |
| 다른 트랜잭션이 재고 행을 3초 넘게 잠금 | 503 `SERVICE_UNAVAILABLE` (#12와 같은 설정) |
| 상품은 있는데 재고 행이 없음 | 500 (#12의 R11과 같음) |

### 테스트 케이스

| ID | 대상 규칙 | Given | When | Then | 종류 |
|---|---|---|---|---|---|
| TC-13-01 | R1 | 재고 10 | `decrease(3)` | 재고 7, `StockChange`(OUTBOUND, 3, 전 10, 후 7) | 단위 |
| TC-13-02 | R1 | 재고 10 | `decrease(10)` | 재고 0 | 단위 |
| TC-13-03 | R2 | 재고 10 | `decrease(11)` | `INSUFFICIENT_STOCK`, 재고 10 | 단위 |
| TC-13-04 | R5 | 재고 10 | `decrease(0)` | `INVALID_QUANTITY`, 재고 10 | 단위 |
| TC-13-05 | R1, R4 | `TS-BASIC-BLK-M` 재고 5 | `X-User-Id: worker-01`로 3 출고 | 200, `data` 없음, 재고 2, 원장 1건(OUTBOUND, 3, 5, 2, `worker-01`) | 통합 (HTTP) |
| TC-13-06 | R1 | 재고 5 | 5 출고 | 200, 재고 0 | 통합 (HTTP) |
| TC-13-07 | R2 | 재고 5 | 6 출고 | 409 `INSUFFICIENT_STOCK`, 재고 5, 원장 0건 | 통합 (HTTP) |
| TC-13-08 | R3 | 상품 없음 | `NOT-EXIST`에 출고 | 404 `PRODUCT_NOT_FOUND`, 상품 1건(기존 것만) | 통합 (HTTP) |
| TC-13-09 | R5 | 재고 5 | `X-User-Id` 없이 / 공백 / 65자로 출고 | 400 (`BAD_REQUEST` / `INVALID_REQUESTER_ID`), 재고 5 | 통합 (HTTP) |
| TC-13-10 | R5 | 재고 5 | 수량 0, -1, 1000001, 없음, `1.5`, `"abc"` | 400 `BAD_REQUEST`, 재고 5 | 통합 (HTTP) |
| TC-13-11 | R5 | | `ts-basic`에 출고 | 400 `INVALID_PRODUCT_CODE` | 통합 (HTTP) |
| TC-13-12 | R6 | 재고 10 | 20개 요청이 동시에 각 1 출고 | 200 10건, 409 10건, 재고 0, 원장 10건, 원장 수량 합 10 | 통합 (동시성) |

- 단위 테스트는 `InventoryTest`, HTTP는 `InventoryOutboundApiTest`, 동시성은 #12의 동시성 테스트 클래스를 `InventoryConcurrencyTest`로 이름을 바꿔 함께 둔다. 입고만의 테스트가 아니게 되기 때문이다.

### PR 계획

| PR | 브랜치 | 범위 | 포함 테스트 케이스 |
|---|---|---|---|
| 1 | `feat/13-outbound` | 명세서, `Inventory.decrease`, 출고 서비스와 API, 테스트 (`Closes #13`) | TC-13-01 ~ TC-13-12 |

- 출고는 입고가 만든 테이블, 잠금, 원장 위에 메서드와 API를 더하는 작은 변경이라 PR 하나로 충분하다. #12의 PR이 머지된 뒤 dev 기준으로 올린다.

### 결정 필요
- 없음 (재고 부족 409, 경로, 요청자 헤더, 수량 상한은 이슈와 #12 결정을 따른다)

### 변경 이력
