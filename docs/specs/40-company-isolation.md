# 기능 명세 — 회사별 상품, 재고, 멱등성 분리 (#40)

### 요구사항 해석
- 모든 API(재고 조회, 이력 조회, 입고, 출고, 상품명 수정)는 `X-Company-Code` 헤더로 회사 코드를 받는다. 없으면 400 `BAD_REQUEST`, 형식이 틀리면 400 `INVALID_COMPANY_CODE`다.
- 상품은 "회사 코드 + 상품 코드"로 구분한다. A사의 `SKU-001`과 B사의 `SKU-001`은 상품 행이 따로 있고, 재고와 이력도 따로 쌓인다.
- 다른 회사의 상품 코드로 조회, 이력 조회, 상품명 수정, 출고를 하면 그 상품이 없는 것과 똑같이 404 `PRODUCT_NOT_FOUND`다. 응답만 보고는 "다른 회사에 이 코드가 있다"는 사실을 알 수 없다.
- 다른 회사에 이미 있는 상품 코드로 입고하면, 내 회사의 새 상품으로 등록되고 재고가 쌓인다 (미등록 상품 첫 입고와 같은 동작, #12).
- 멱등성 키는 "회사 코드 + 키"로 판단한다. A사와 B사가 같은 키를 보내도 각자 처음 보는 키라 둘 다 반영된다. 같은 회사 안에서는 지금과 같다(#15).
- 기존 상품과 멱등성 기록은 마이그레이션으로 기본 회사 `DEFAULT`에 넣는다. 재고와 이력은 상품을 따라가므로 따로 옮길 것이 없다.
- 회사 인증과 회사 등록은 하지 않는다. 형식이 맞는 회사 코드는 모두 받는다.

**용어**

| 용어 | 뜻 |
|---|---|
| 회사 코드 (`CompanyCode`) | 시스템을 함께 쓰는 회사(테넌트)를 가리키는 코드. 요청이 어느 회사의 데이터에 적용되는지 정한다 |
| 기본 회사 (`DEFAULT`) | 이 기능 전에 쌓인 데이터의 회사 코드 |

### 설계

**데이터** — `inventory/schema`의 `V40_1__add_company_code.sql`

```sql
-- 상품: 회사마다 상품 코드가 따로다. 기존 상품은 기본 회사로
ALTER TABLE product ADD COLUMN company_code VARCHAR(64) NOT NULL DEFAULT 'DEFAULT';
ALTER TABLE product ALTER COLUMN company_code DROP DEFAULT;
ALTER TABLE product DROP CONSTRAINT uk_product_product_code;
CREATE UNIQUE INDEX uk_product_company_code_product_code ON product (company_code, product_code);
ALTER TABLE product ADD CONSTRAINT uk_product_company_code_product_code UNIQUE USING INDEX uk_product_company_code_product_code;

-- 멱등성 기록: 회사마다 키가 따로다. 기존 기록은 기본 회사로
ALTER TABLE idempotency_record ADD COLUMN company_code VARCHAR(64) NOT NULL DEFAULT 'DEFAULT';
ALTER TABLE idempotency_record ALTER COLUMN company_code DROP DEFAULT;
ALTER TABLE idempotency_record DROP CONSTRAINT idempotency_record_pkey;
CREATE UNIQUE INDEX pk_idempotency_record ON idempotency_record (company_code, idempotency_key);
ALTER TABLE idempotency_record ADD CONSTRAINT pk_idempotency_record PRIMARY KEY USING INDEX pk_idempotency_record;
```

- 인덱스를 `CREATE UNIQUE INDEX`로 먼저 만들고, 유니크 제약과 PK가 `USING INDEX`로 그 인덱스를 쓴다. 제약만 선언해도 PostgreSQL이 같은 인덱스를 만들지만, DDL만 보고도 어떤 인덱스가 있는지 보이게 한다. 제약과 별도로 인덱스를 하나 더 만들면 같은 컬럼에 인덱스가 둘이 되어 쓰기마다 두 번 고치므로 하나만 둔다.
- 이 기능의 쿼리(회사 + 상품 코드로 상품 찾기와 등록, 회사 + 키로 멱등성 기록 넣기와 다시 읽기)는 모두 이 두 인덱스를 쓴다. 재고 잠금(`inventory` PK)과 이력 조회(`ix_stock_transaction_product_id_id`)는 쿼리가 바뀌지 않는다.

- `DEFAULT 'DEFAULT'`는 기존 행을 채우는 데만 쓰고 바로 지운다. 기본값이 남아 있으면 회사 코드를 빠뜨린 INSERT가 오류 없이 기본 회사 데이터가 된다. 기본값이 없으면 NOT NULL 위반으로 바로 드러난다.
- PostgreSQL 11부터 상수 기본값을 준 `ADD COLUMN`은 테이블을 다시 쓰지 않는다. 기존 행이 많아도 마이그레이션이 오래 걸리지 않는다.
- 유니크와 PK의 컬럼 순서를 `company_code`가 앞으로 둔다. 모든 조회가 회사 코드를 함께 걸기 때문에, 앞 컬럼이 회사여야 "이 회사의 상품"을 인덱스로 바로 찾는다.

**`inventory`, `stock_transaction`에는 회사 코드를 두지 않는다**
- 두 테이블은 `product_id`로 상품을 가리키고, `product.id`는 회사와 상관없이 전역에서 유일하다. A사 `SKU-001`(id 1)과 B사 `SKU-001`(id 2)의 재고는 `product_id`가 달라서 이미 따로 있다.
- 재고와 이력을 읽는 길은 모두 "회사 코드 + 상품 코드 → 상품 → `product_id`"를 거친다. 상품을 찾을 때 회사가 걸러지므로 그 뒤의 재고와 이력도 자기 회사의 것만 나온다.
- 대안: 두 테이블에도 `company_code`를 넣는 방법. 상품을 거치지 않고 회사별로 재고를 모아 보는 쿼리(예: "A사 전체 재고 목록")가 조인 없이 된다. 하지만 지금 그런 API가 없고, 같은 값을 세 곳에 두면 "상품은 A사인데 재고는 B사" 같은 어긋남을 막는 제약을 따로 둬야 한다. 회사별 목록 API가 생길 때 다시 본다.

**멱등성 기록은 대리키가 아니라 복합 PK로 둔다** (대안: `id BIGINT` PK + `UNIQUE (company_code, idempotency_key)`)
- 두 방식 모두 같은 키인지는 `(company_code, idempotency_key)` 유니크 인덱스 하나로 판단한다. 정합성 차이는 없다.
- 대리키가 나은 점: 엔티티를 `Product`처럼 `Long id`와 `@Embedded` VO로 매핑할 수 있다. 지금은 `@IdClass` 때문에 회사 코드와 키를 `String` 필드로 두고, VO를 돌려주는 getter를 따로 만든다.
- 그래도 복합 PK를 고른 이유
  - 쓰지 않는 인덱스가 하나 더 생긴다. 대리키를 두면 `id` PK 인덱스와 회사 + 키 유니크 인덱스, 두 개가 된다. 조회는 항상 회사 + 키로 하고 이 테이블을 외래 키로 가리키는 곳도 없어서 `id` 인덱스를 읽는 쿼리가 없다. 그런데 입고나 출고 한 건마다 행이 하나 들어가므로 그때마다 `id` 인덱스에도 항목이 추가된다.
  - 회사별로 파티션을 나눌 때 PK를 바꾸지 않아도 된다. PostgreSQL은 파티션 테이블의 PK에 파티션 키가 들어가야 한다. `PARTITION BY HASH (company_code)`로 나누면 복합 PK는 이미 `company_code`를 포함해 그대로 쓴다. 대리키는 PK를 `(company_code, id)`로 바꿔야 해서, `id`만으로 행을 구분한다는 대리키의 이점이 사라진다.
- 시간 기준 파티션(`created_at`, #15의 오래된 키 정리 경로)은 두 방식 모두 유니크에 `created_at`이 들어가야 해서 회사 + 키만의 유일성을 DB가 보장하지 못한다. 어느 쪽을 골라도 그때 함께 풀어야 한다.
- 나중에 멱등성 기록과 원장을 연결해야 하면(응답에 데이터를 담게 될 때) `idempotency_record`에 `stock_transaction_id`를 두면 되므로 대리키가 필요하지 않다.

**회사 테이블을 두지 않는다**
- 이슈가 회사 등록, 관리를 범위 밖으로 두었다. 회사 테이블을 만들어도 채울 길이 없고, 외래 키를 걸면 등록되지 않은 회사의 요청이 모두 실패해서 "형식이 맞으면 받는다"와 어긋난다.
- 그래서 `company_code` 문자열을 그대로 둔다. 실제 서비스라면 회사 테이블과 외래 키를 추가하고, 앱은 등록된 회사인지 확인한다.

**도메인** (`inventory/core`)

| 패키지 | 클래스 | 바뀌는 것 |
|---|---|---|
| `common` | `CompanyCode` (record, 신규) | `[A-Z0-9-]{1,64}`가 아니면 `INVALID_COMPANY_CODE`. 상품 코드와 같은 형식이다 |
| `common` | `ErrorCode` | `INVALID_COMPANY_CODE("회사 코드 형식이 올바르지 않습니다.", INVALID)` 추가 |
| `product.entity` | `Product` | `companyCode` 필드. `register(companyCode, productCode)` |
| `product.model` | `RenameProductCommand` | `companyCode` 추가 |
| `product.port` | `ProductStore` | `findByProductCode(companyCode, productCode)` |
| `product` | `ProductService.rename` | 회사 코드와 상품 코드로 찾는다 |
| `inventory.model` | `InboundCommand`, `OutboundCommand` | `companyCode` 추가 |
| `inventory.port` | `InventoryStore` | `findStockByProductCode(companyCode, productCode)` |
| `inventory` | `ProductRegistrar.getOrRegister(companyCode, productCode)` | 회사 코드로 찾고 등록한다 |
| `inventory` | `InventoryService` | `getStock(companyCode, productCode)`, `getHistory(companyCode, productCode, cursorQuery)`. 입고, 출고는 Command의 회사 코드를 쓴다 |
| `idempotency.entity` | `IdempotencyRecord` | `companyCode` 필드. PK는 `IdempotencyRecordId(companyCode, idempotencyKey)` (`@IdClass`) |

- `CompanyCode`를 `common`에 두는 이유: 회사는 애그리거트가 아니다(등록, 상태, 행위가 없다). 상품, 멱등성 기록이 함께 쓰는 "데이터가 어느 회사의 것인가"라는 범위 값이라, 특정 애그리거트의 `entity/`에 두면 다른 애그리거트가 그 패키지를 참조하게 된다.
- 회사 코드를 Command에 넣는 이유: #15에서 멱등성 키를 Command 밖에 둔 것은 키가 "몇 번째 시도인지"를 가리는 꼬리표라서였다. 회사 코드는 "어느 회사의 상품을 바꾸는지"라서 상품 코드와 같은 업무 데이터다. 빠지면 처리할 대상 자체가 정해지지 않는다.
- `IdempotencyRecord.verifySameRequest`는 회사 코드를 비교하지 않는다. 회사 코드는 키의 일부라서, 저장된 기록을 찾았다면 회사 코드는 이미 같다.
- 404 메시지는 지금처럼 `productCode=SKU-001`만 담는다. 다른 회사의 상품이어도 "없는 상품"과 응답 본문이 글자까지 같아서, 응답으로는 다른 회사에 같은 코드가 있는지 알 수 없다.

**저장** (`inventory/db`)
- `ProductJpaRepository`: `findByCompanyCodeValueAndProductCodeValue`, `insertIfAbsent`는 `ON CONFLICT (company_code, product_code) DO NOTHING`.
- `InventoryJpaRepository.findStockByProductCode`: 조인 조건에 `p.companyCode.value = :companyCode`를 더한다.
- `IdempotencyRecordJpaRepository`: `JpaRepository<IdempotencyRecord, IdempotencyRecordId>`, `insertIfAbsent`는 `ON CONFLICT (company_code, idempotency_key) DO NOTHING`. 다시 읽을 때 `findById(new IdempotencyRecordId(companyCode, key))`.
- 재고 잠금(`findByProductIdForUpdate`)과 원장 조회(`findHistoryBy`)는 그대로다. 둘 다 회사로 걸러 찾은 상품의 id를 받는다.

**API** (`app`)

모든 API가 `X-Company-Code` 헤더를 받는다 (`ApiHeaders.COMPANY_CODE`). 경로와 본문은 그대로다.
```http
POST /api/v1/inventory/SKU-001/outbound
X-Company-Code: COMPANY-A
X-User-Id: worker-01
Idempotency-Key: 6f1c2a3e-9b7d-4c1a-8e2f-0a5b3c7d9e11
Content-Type: application/json

{"quantity": 3}
```

| 상황 | 상태 | `code` |
|---|---|---|
| `X-Company-Code` 없음 | 400 | `BAD_REQUEST` |
| `X-Company-Code` 형식 틀림 (소문자, 공백, 65자 이상) | 400 | `INVALID_COMPANY_CODE` |
| 다른 회사의 상품을 조회, 이력 조회, 수정, 출고 | 404 | `PRODUCT_NOT_FOUND` (없는 상품과 같은 본문) |
| 그 밖 | 기존과 같다 | |

- 헤더로 받는 이유: 요청자 ID(`X-User-Id`)와 같은 성격이다. 둘 다 "누가 보냈는지"이고, 인증을 붙이면 앞단(게이트웨이)이 토큰에서 꺼내 넣는다. 그때도 API 경로와 컨트롤러 시그니처가 바뀌지 않는다.
- 대안: 경로 `/v1/companies/{companyCode}/inventory/...`. URL만 봐도 어느 회사의 자원인지 보이고 로그의 경로로 회사를 알 수 있다. 하지만 모든 경로, nginx 설정, 부하 테스트 URL이 바뀌고, 인증을 붙이면 토큰의 회사와 경로의 회사가 같은지 따로 확인해야 한다.
- 회사 코드는 지표 태그에 넣지 않는다. 형식이 맞으면 모두 받으므로 값의 개수가 정해져 있지 않다(아키텍처 규칙 8장).

**흐름** — 트랜잭션과 잠금 순서는 바뀌지 않는다

```java
@Transactional
public void outbound(final IdempotencyKey idempotencyKey, final OutboundCommand command) {
    // 멱등성 기록 (IdempotencyRecord) — 회사 코드 + 키로 확인
    final IdempotencyRecord request = IdempotencyRecord.of(command.companyCode(), idempotencyKey, OUTBOUND, ...);
    ...
    // 상품 (Product) — 내 회사의 상품만 찾는다. 다른 회사 상품은 없는 것과 같다
    final Product product = productStore.findByProductCode(command.companyCode(), command.productCode())
        .orElseThrow(() -> BusinessException.of(PRODUCT_NOT_FOUND, ...));
    // 재고 (Inventory), 원장 (StockTransaction) — 기존과 같다
}
```
- 잠그는 대상은 여전히 `product_id`로 찾은 재고 행 하나다. A사와 B사의 같은 상품 코드는 행이 달라서 서로 기다리지 않는다.
- 동시 첫 입고는 `(company_code, product_code)` 유니크에서 줄을 선다. 같은 회사의 같은 코드끼리만 기다리고, 다른 회사끼리는 기다리지 않는다.

**배포할 때: 마이그레이션 하나로 제약을 바꾼다**
- 기존 제약(`uk_product_product_code`, `idempotency_record_pkey`)을 지우고 새 제약을 만든다. 구버전 Pod의 `ON CONFLICT (product_code)`, `ON CONFLICT (idempotency_key)`는 맞는 제약이 없어 오류가 난다. 그래서 롤링 배포 동안 **구버전 Pod가 받은 입고, 출고는 500**이다. 조회와 상품명 수정은 회사 코드 없이 상품 코드로 찾으므로, 새 버전 Pod가 다른 회사로 같은 상품 코드를 만들기 전까지는 동작한다 (기존 데이터는 모두 기본 회사라 한 건만 나온다). 같은 코드가 두 회사에 생긴 뒤에는 구버전 Pod의 조회가 두 건을 받아 500이다.
- 받아들이는 이유: 헤더가 필수가 되어 클라이언트도 이 배포와 함께 바뀌어야 한다. 배포 중 500은 클라이언트 재시도 계약(#15, 5xx는 같은 키로 재시도)으로 새 버전 Pod에서 처리된다. 같은 키로 재시도하므로 두 번 반영되지 않는다.
- 대안: 두 번에 나눠 배포한다. 1차는 컬럼과 새 유니크를 추가하고 기존 제약을 남긴 채 코드를 바꾸고, 2차에 기존 제약을 지운다(아키텍처 규칙의 "추가 → 코드 전환 → 삭제"). 롤링 중 500은 없다. 대신 1차만 배포된 동안에는 기존 제약이 남아 있어서, 다른 회사가 같은 상품 코드나 같은 키를 쓰면 500이 된다. 배포가 두 번 필요하고 중간 상태가 하나 더 생기는 데 비해, 이 과제에는 바꿔야 할 기존 클라이언트가 없어 한 번에 바꾼다.
- 운영 데이터는 모두 `DEFAULT` 회사가 된다. 기존 클라이언트는 `X-Company-Code: DEFAULT`를 보내면 지금까지의 데이터를 그대로 쓴다.

### 비즈니스 규칙
- R1. 모든 API는 `X-Company-Code`가 있어야 한다. 없으면 400 `BAD_REQUEST`, `[A-Z0-9-]{1,64}`가 아니면 400 `INVALID_COMPANY_CODE`이고 아무것도 바뀌지 않는다.
- R2. 상품은 회사 코드와 상품 코드로 구분한다. 서로 다른 회사가 같은 상품 코드로 입고하면 상품, 재고, 이력이 회사마다 따로 생긴다.
- R3. 다른 회사의 상품은 조회, 이력 조회, 상품명 수정, 출고할 수 없다. 응답은 없는 상품과 같은 404 `PRODUCT_NOT_FOUND`이고, 그 상품은 바뀌지 않는다.
- R4. 멱등성 키는 회사마다 따로 판단한다. 서로 다른 회사가 같은 키를 보내면 각각 한 번씩 반영된다.
- R5. 같은 회사 안에서 같은 상품 코드는 한 행, 같은 멱등성 키는 한 행만 있을 수 있다 (DB 제약).
- R6. 기존 상품과 멱등성 기록은 기본 회사 `DEFAULT`로 옮겨지고, 재고와 이력은 그 상품을 그대로 따라간다.
- R7. 회사가 여러 개여도 동시성 보장(초과 출고 없음, 재고와 원장 합계 일치)은 그대로다.

### 예외 케이스

| 상황 | 결과 |
|---|---|
| `X-Company-Code` 없음 | 400 `BAD_REQUEST` |
| `X-Company-Code` 소문자, 공백, 65자 이상 | 400 `INVALID_COMPANY_CODE` |
| 회사 코드와 상품 코드가 둘 다 틀림 | 400. 어느 쪽이 먼저 걸리는지는 정하지 않는다 |
| 다른 회사 상품의 조회, 이력, 수정, 출고 | 404 `PRODUCT_NOT_FOUND`, 없는 상품과 같은 본문 |
| 다른 회사에 있는 상품 코드로 입고 | 200, 내 회사의 새 상품으로 등록 |
| A사가 쓴 멱등성 키를 B사가 다른 내용으로 보냄 | 200, B사의 처음 요청으로 처리 (422가 아니다) |

### 테스트 케이스

| ID | 대상 규칙 | Given | When | Then | 종류 |
|---|---|---|---|---|---|
| TC-40-01 | R1 | | `CompanyCode`를 null, 공백, 소문자, 65자로 만듦 / 64자로 만듦 | `INVALID_COMPANY_CODE` / 생성됨 | 단위 |
| TC-40-02 | R5 | `COMPANY-A`의 `SKU-001` | SQL로 `COMPANY-A`의 `SKU-001` / `COMPANY-B`의 `SKU-001`을 INSERT | 유니크 위반 (23505) / 들어감 | 통합 (스키마) |
| TC-40-03 | R5 | `COMPANY-A`의 키 `K1` | SQL로 `COMPANY-A`의 `K1` / `COMPANY-B`의 `K1`을 INSERT | 유니크 위반 (23505) / 들어감 | 통합 (스키마) |
| TC-40-04 | R6 | V21_1까지 적용하고 상품, 재고, 원장, 멱등성 기록을 넣음 | 나머지 마이그레이션 적용 | 상품과 멱등성 기록의 `company_code`가 `DEFAULT`, 재고와 원장은 그대로, 회사 코드 없는 INSERT는 NOT NULL 위반 (23502) | 통합 (스키마) |
| TC-40-05 | R1 | 재고 5 | 헤더 없이 조회, 이력, 입고, 출고, 상품명 수정 | 모두 400 `BAD_REQUEST`, 재고 5, 원장 0건, 상품명 그대로 | 통합 (HTTP) |
| TC-40-06 | R1 | 재고 5 | `company-a`, 65자로 조회, 입고 | 400 `INVALID_COMPANY_CODE`, 재고 5 | 통합 (HTTP) |
| TC-40-07 | R2 | `COMPANY-A`의 `TS-BASIC-BLK-M` 재고 5 | `COMPANY-B`가 같은 코드로 3 입고 | 200. A 재고 5, B 재고 3, 상품 2건, A 이력 0건, B 이력 1건 | 통합 (HTTP) |
| TC-40-08 | R3 | `COMPANY-A`의 `TS-BASIC-BLK-M` 재고 5 | `COMPANY-B`가 그 코드를 조회 / A, B 모두 없는 `NOT-EXIST`를 조회 | 둘 다 404 `PRODUCT_NOT_FOUND`, 메시지가 상품 코드 자리만 다르고 같다 (`상품을 찾을 수 없습니다. productCode=...`) | 통합 (HTTP) |
| TC-40-09 | R3 | TC-40-08과 같음 | `COMPANY-B`가 그 코드의 이력 조회, 상품명 수정, 1 출고 | 모두 404 `PRODUCT_NOT_FOUND`, A 재고 5, A 상품명 그대로, 원장 0건 | 통합 (HTTP) |
| TC-40-10 | R4 | `COMPANY-A`, `COMPANY-B` 각각 `TS-BASIC-BLK-M` 재고 5 | 두 회사가 같은 키 `K1`로 3 입고 | 둘 다 200, A 8, B 8, 원장 2건, 멱등성 기록 2건 | 통합 (HTTP) |
| TC-40-11 | R4 | `COMPANY-A`가 `K1`로 3 입고 성공 | `COMPANY-B`가 `K1`으로 다른 상품에 2 출고 | 404 `PRODUCT_NOT_FOUND` (422가 아니다. A의 기록과 비교하지 않는다) | 통합 (HTTP) |
| TC-40-12 | R7 | `COMPANY-A`, `COMPANY-B` 각각 `TS-BASIC-BLK-M` 재고 10 | 두 회사가 15개씩 동시에 1 출고 | 회사마다 200 10건, 409 5건, 재고 0, 재고와 원장 합계 일치 | 통합 (동시성) |

- 단위는 `CompanyCodeTest`, 스키마는 `ProductInventorySchemaTest`, `IdempotencyRecordSchemaTest`, 마이그레이션은 `CompanyCodeMigrationTest`(`inventory/schema`), HTTP는 `InventoryCompanyApiTest`, 동시성은 `InventoryConcurrencyTest`에 둔다.
- 기존 API 테스트는 헤더가 필수가 되므로, 테스트 공통 헬퍼가 요청마다 `X-Company-Code: COMPANY-A`를 붙이고 준비 SQL도 `COMPANY-A`로 넣게 바꾼다. 기존 스키마 테스트의 INSERT도 회사 코드를 넣게 바꾼다. 기존 테스트의 기대 결과는 바뀌지 않는다.

**검증 방법** (자동화하지 않는 것)
- `./load-test/run.sh load`: k6가 `COMPANY-A`, `COMPANY-B` 두 회사로 같은 상품 코드(`LOAD-TEST-01`)에 입고, 출고를 섞어 보낸다(같은 키 중복 포함). 정합성 확인의 위반이 모두 0이고, 반영된 키 수와 원장 행 수가 같아야 한다.

### PR 계획

| PR | 브랜치 | 범위 | 포함 테스트 케이스 |
|---|---|---|---|
| 1 | `feat/40-company-isolation` | 명세서, 마이그레이션, 도메인과 저장, 모든 API의 헤더, 기존 테스트와 부하 테스트 스크립트 수정, README API 설명 (`Closes #40`) | TC-40-01 ~ TC-40-12 |

- 헤더 필수화와 제약 교체가 함께 배포되어야 해서(위 "배포할 때") PR 하나로 올린다.

### 결정 필요
- 없음 (회사 코드는 `X-Company-Code` 헤더로 받고, 제약은 마이그레이션 하나로 한 번에 바꾸기로 결정했다)

### 변경 이력
- 멱등성 기록을 대리키가 아니라 복합 PK로 두는 이유를 추가 (구현 리뷰)
- 복합 PK 이유에서 회사별 DB 분리 근거를 지움. 빈 DB로 떼어낼 때는 대리키도 id가 겹치지 않아 근거가 성립하지 않는다 (PR 리뷰)
