# 기능 명세 — 재고 변동 이력 조회 (#21)

### 요구사항 해석
- 상품 코드로 그 상품의 원장(`stock_transaction`)을 최신순으로 조회한다. 입고(#12), 출고(#13)가 남긴 기록을 그대로 보여 준다.
- 이력마다 구분(입고, 출고), 수량, 변경 전후 재고, 요청자, 시각을 내려 준다.
- 이력은 계속 쌓이므로 페이지 번호와 크기로 나눠 조회한다. 페이지 크기에는 상한을 두고, 상한을 넘게 요청하면 거절하지 않고 상한만큼 조회한다.
- 클라이언트가 "더 보기"를 할 수 있게 다음 페이지가 있는지(`hasNext`)를 함께 내려 준다. 전체 건수는 내려 주지 않는다.
- 없는 상품은 404, 상품은 있는데 이력이 없으면 빈 목록이다.

### 설계

**데이터** — 인덱스를 추가한다 (`inventory/schema`의 `V21_1__create_stock_transaction_product_index.sql`)

```sql
CREATE INDEX ix_stock_transaction_product_id_id ON stock_transaction (product_id, id);
```
- 조회 쿼리 `WHERE product_id = ? ORDER BY id DESC LIMIT ? OFFSET ?`를 이 인덱스를 거꾸로 읽는 것으로 처리한다. 인덱스가 없으면 다른 상품의 원장까지 모두 읽고 정렬한다. 예를 들어 원장이 100만 건이고 그중 이 상품이 30건이면, 30건을 보려고 100만 건을 읽는다.
- #12 명세에서 미뤄 둔 인덱스(`product_id, created_at`)는 만들지 않는다. 정렬 기준이 `created_at`이 아니라 `id`이기 때문이다 (아래 "왜 `id` 순서로 정렬하나").

**도메인** (`inventory/core`)

| 패키지 | 클래스 | 역할 |
|---|---|---|
| `common` | `PageQuery` (record) | `page`, `size`. `page`는 0 이상, `size`는 1 이상이어야 하고 아니면 `IllegalArgumentException`. `size`가 100을 넘으면 100으로 줄인다 (R3) |
| `common` | `PageResult<T>` (record) | `items`, `page`, `size`, `hasNext` |
| `transaction.model` | `StockHistoryResult` (record) | `type`, `quantity`, `beforeQuantity`, `afterQuantity`, `requesterId`, `createdAt` |
| `transaction.port` | `StockTransactionStore` | `findHistory(Long productId, PageQuery)` → `PageResult<StockHistoryResult>` 추가 |
| `product.port` | `ProductStore` | 그대로 (`findByProductCode` 사용) |
| `inventory` | `InventoryService.getHistory(ProductCode, PageQuery)` | `@Transactional(readOnly = true)`. 상품을 찾고 없으면 `PRODUCT_NOT_FOUND`, 있으면 원장 조회 |

- `PageQuery`의 음수 검사는 API를 거치지 않은 호출을 막는 용도다. API에서는 컨트롤러 검증(아래)이 먼저 400을 낸다.
- `PageQuery`, `PageResult`를 `common`에 두는 이유: 페이지로 나눠 조회하는 기능이 원장 말고도 생길 수 있는 모양이고, Spring Data의 `Pageable`, `Slice`를 도메인 포트에 노출하지 않기 위해서다 (아키텍처 규칙 4장).

**db** (`inventory/db`)
- `StockTransactionJpaRepository`에 조회 쿼리를 추가한다. 엔티티가 아니라 결과 record로 바로 받는다.
  ```java
  @Query("""
      select new com.logistics.inventory.domain.transaction.model.StockHistoryResult(
          t.type, t.quantity.value, t.beforeQuantity.value, t.afterQuantity.value, t.requesterId.value, t.createdAt)
      from StockTransaction t
      where t.productId = :productId
      order by t.id desc
      """)
  Slice<StockHistoryResult> findHistory(Long productId, Pageable pageable);
  ```
- 반환 타입이 `Slice`라서 Spring Data가 `size + 1`건을 읽어 다음 페이지가 있는지 판단하고, `COUNT` 쿼리는 내지 않는다.
- `StockTransactionStoreAdapter.findHistory`가 `PageRequest.of(page, size)`로 부르고 `Slice`를 `PageResult`로 바꾼다.

**API** (`app`)

`GET /api/v1/inventory/{productCode}/history?page=0&size=20`

```json
{
  "status": 200,
  "code": "SUCCESS",
  "message": "success",
  "data": {
    "items": [
      {
        "type": "OUTBOUND",
        "quantity": 2,
        "beforeQuantity": 8,
        "afterQuantity": 6,
        "requesterId": "worker-02",
        "createdAt": "2026-10-16T01:20:31.512Z"
      },
      {
        "type": "INBOUND",
        "quantity": 3,
        "beforeQuantity": 5,
        "afterQuantity": 8,
        "requesterId": "worker-01",
        "createdAt": "2026-10-16T01:15:02.104Z"
      }
    ],
    "page": 0,
    "size": 20,
    "hasNext": false
  }
}
```

| 상황 | 상태 | `code` |
|---|---|---|
| 조회 성공 (이력이 없어도) | 200 | `SUCCESS` |
| 상품 코드 형식이 틀림 | 400 | `INVALID_PRODUCT_CODE` |
| `page` 음수, `size` 0 이하, 숫자가 아님 | 400 | `BAD_REQUEST` (`data`에 틀린 항목, 숫자가 아니면 `data` 없음) |
| 없는 상품 | 404 | `PRODUCT_NOT_FOUND` |

- 조회 API라 기존 `InventoryController`에 메서드를 추가한다.
- `@RequestParam(defaultValue = "0") @Min(0) int page`, `@RequestParam(defaultValue = "20") @Min(1) int size`. 100 초과는 검증하지 않고 `PageQuery`가 100으로 줄인다.
- 응답의 `size`는 실제로 적용한 크기다. 1000을 요청하면 `size: 100`이 내려가서, 클라이언트가 줄어든 것을 알 수 있다.
- 응답 DTO: `StockHistoryResponse(List<StockHistoryItem> items, int page, int size, boolean hasNext)`, `StockHistoryItem`은 원장 한 건.
- 원장 `id`는 내려 주지 않는다. 지금 클라이언트가 쓸 곳이 없다.

**흐름** — `InventoryService.getHistory`, `@Transactional(readOnly = true)`

```java
// 상품 (Product) — 없으면 404
final Product product = productStore.findByProductCode(productCode).orElseThrow(() ->
    BusinessException.of(PRODUCT_NOT_FOUND, "productCode=" + productCode.value())
);
// 원장 (StockTransaction)
return stockTransactionStore.findHistory(product.getId(), pageQuery);
```
- 상품 조회와 원장 조회를 나눈다. 원장을 상품과 조인해 한 번에 읽으면, 결과가 비었을 때 "상품이 없다"(404)와 "이력이 없다"(빈 목록)를 구분할 수 없다.
- 잠금은 걸지 않는다. 원장은 쓰고 나면 바뀌지 않아서, 조회 중에 새 이력이 추가되는 것 말고는 읽은 값이 틀어질 일이 없다.

**왜 `id` 순서로 정렬하나** (대안: `created_at` 순서)
- 같은 상품의 재고 변경은 재고 행 잠금으로 한 줄로 서고, 잠금을 잡은 뒤 원장을 INSERT하므로 `id`가 실제 변경 순서대로 매겨진다.
- `created_at`은 지금 각 Pod의 시계(`@PrePersist`의 `OffsetDateTime.now()`)로 찍는다. Pod끼리 시계가 몇 ms 어긋나면 순서가 뒤집힐 수 있다. 예를 들어 Pod A에서 입고(전 5, 후 8)가 먼저 일어나고 시계가 3ms 늦은 Pod B에서 출고(전 8, 후 6)가 바로 뒤에 일어나면, 출고의 `created_at`이 더 이르게 찍혀 시간순으로는 "전 8 → 후 6"이 "전 5 → 후 8"보다 앞에 나온다.
- 시각은 언제 일어났는지 보여 주는 값으로만 쓴다. 시각을 DB 한 시계로 찍도록 바꾸는 것은 입고에서 정한 동작을 바꾸는 일이라 이 이슈에서 하지 않는다.

**왜 페이지 번호로 나누나** (대안: 마지막으로 본 `id`보다 이전 것을 요청하는 커서 방식)
- 이슈의 완료 조건이 "페이지 크기와 번호로 나눠 조회"이고, 원하는 페이지로 바로 갈 수 있다.
- 커서 방식은 보는 도중 새 이력이 쌓여도 같은 건이 겹쳐 나오지 않는다. 예를 들어 0페이지(최신 20건)를 본 뒤 입고가 1건 생기면, 페이지 번호 방식의 1페이지 첫 건은 0페이지의 마지막 건과 같다. 뒤 페이지로 갈수록 앞의 건을 건너뛰느라 느려지지도 않는다.
- 한 상품의 이력을 수천 페이지씩 넘겨 보는 사용 방식이 아니라서 지금은 페이지 번호로 충분하다. 바꾸게 되면 `PageQuery`, `findHistory`의 쿼리 조건만 바뀐다.

**왜 전체 건수를 주지 않나** (대안: `totalElements`, `totalPages`)
- 전체 건수를 주려면 요청마다 `COUNT(*)`가 한 번 더 나간다. 이력은 지우지 않고 쌓이기만 해서, 많이 움직이는 상품일수록 이 쿼리가 무거워진다.
- "더 보기"에는 `hasNext`면 충분하다.

**왜 페이지 크기 상한을 넘으면 400이 아니라 상한으로 조회하나**
- 크기는 조회 결과의 양을 정할 뿐 데이터를 바꾸지 않는다. 1000을 요청한 클라이언트에게 100건을 주고 응답의 `size`로 알려 주면, 요청이 실패하지 않고 의도도 대부분 충족된다.
- 입고 수량처럼 잘못된 값이 그대로 데이터가 되는 경우와 다르다. 음수나 0은 의도를 알 수 없어서 400으로 거절한다.

### 비즈니스 규칙
- R1. 이력은 그 상품의 원장만, 원장 `id`가 큰 것부터 나온다.
- R2. 이력 한 건에는 구분, 수량, 변경 전 재고, 변경 후 재고, 요청자, 시각이 있다.
- R3. `page`는 0 이상, `size`는 1 이상이다. 기본값은 `page` 0, `size` 20이다. `size`가 100을 넘으면 100으로 조회한다.
- R4. 다음 페이지에 이력이 있으면 `hasNext`가 true다.
- R5. 상품 코드 형식이 틀리면 400, 없는 상품이면 404다.
- R6. 상품은 있는데 이력이 없으면 빈 목록이다.

### 예외 케이스

| 상황 | 결과 |
|---|---|
| 상품 코드 형식이 틀림 | 400 `INVALID_PRODUCT_CODE` |
| `page` -1, `size` 0, `page=abc` | 400 `BAD_REQUEST` |
| `size` 1000 | 200, 100건까지, 응답 `size` 100 |
| 없는 상품 | 404 `PRODUCT_NOT_FOUND` |
| 마지막 페이지를 넘는 `page` | 200, 빈 목록, `hasNext` false |

### 테스트 케이스

| ID | 대상 규칙 | Given | When | Then | 종류 |
|---|---|---|---|---|---|
| TC-21-01 | R3 | | `PageQuery` 생성: (0, 1), (0, 100), (0, 101), (-1, 20), (0, 0) | `size` 1, 100, 100. 뒤 둘은 `IllegalArgumentException` | 단위 |
| TC-21-02 | R1, R2 | `TS-BASIC-BLK-M` 원장 3건(입고 3 / 출고 2 / 입고 4), 다른 상품 원장 1건 | 이력 조회 | 200, 3건이 입고 4, 출고 2, 입고 3 순서, 각 건의 구분, 수량, 전후 재고, 요청자, 시각이 맞음. 다른 상품 원장 없음. `page` 0, `size` 20, `hasNext` false | 통합 (HTTP) |
| TC-21-03 | R1 | 원장 2건, 뒤 `id`의 `created_at`이 앞 `id`보다 1초 이름 | 이력 조회 | `id` 역순으로 나옴 | 통합 (HTTP) |
| TC-21-04 | R2 | `TS-BASIC-BLK-M` 재고 5 | `X-User-Id: worker-01`로 3 입고 후 이력 조회 | 1건: `INBOUND`, 3, 5, 8, `worker-01`, 시각 있음 | 통합 (HTTP) |
| TC-21-05 | R4 | 원장 25건 | `page` 0, 1, 2 (`size` 20) | 20건 `hasNext` true / 5건 false / 0건 false | 통합 (HTTP) |
| TC-21-06 | R3 | 원장 101건 | 파라미터 없이 / `size=1000` | 20건 `size` 20 / 100건 `size` 100 `hasNext` true | 통합 (HTTP) |
| TC-21-07 | R3 | | `page=-1` / `size=0` / `page=abc` | 400 `BAD_REQUEST`, 앞 둘은 `data[0].field`가 `page`, `size` | 통합 (HTTP) |
| TC-21-08 | R5 | | `NOPE-01` / `ts-basic` 이력 조회 | 404 `PRODUCT_NOT_FOUND` / 400 `INVALID_PRODUCT_CODE` | 통합 (HTTP) |
| TC-21-09 | R6 | `TS-BASIC-BLK-M` 원장 없음 | 이력 조회 | 200, `items` 빈 목록, `hasNext` false | 통합 (HTTP) |

- TC-21-02 ~ 09는 `app`의 `InventoryHistoryApiTest`(`InventoryApiTestSupport` 상속)에서 Testcontainers PostgreSQL과 실제 HTTP로 검증한다.
- 출고 원장은 SQL로 직접 넣는다. 원장 테이블과 출고 제약은 #12에서 이미 만들어져 있어 출고 API가 없어도 검증할 수 있다. TC-21-04는 입고 API가 쓴 원장을 이력 API가 그대로 읽는지(엔티티 매핑과 조회 쿼리가 같은 컬럼을 보는지) 확인한다.

### PR 계획

| PR | 브랜치 | 범위 | 포함 테스트 케이스 |
|---|---|---|---|
| 1 | `feat/21-stock-history` | 명세서, 인덱스 마이그레이션(`V21_1`), `PageQuery`, `PageResult`, 원장 조회 쿼리와 Store, 이력 조회 서비스와 API (`Closes #21`) | TC-21-01 ~ TC-21-09 |

### 결정 필요
- 없음

### 변경 이력
