# 기능 명세 — 상품 재고 조회 (#7)

### 요구사항 해석
- 상품 코드로 상품 하나의 현재 재고(상품 코드, 상품명, 수량)를 조회하는 API를 만든다.
- 상품과 재고를 저장하는 테이블을 이 이슈에서 만든다. 입고, 출고 이슈가 이 테이블 위에서 동작한다.
- 같은 상품 코드의 상품은 하나만, 재고 수량은 0 이상만 저장된다. 앱 검증과 별개로 DB 제약으로도 막는다. 여러 Pod가 동시에 쓰거나 앱에 버그가 있어도 잘못된 데이터가 남지 않게 하기 위해서다.
- 상품 등록 API는 없다(상품은 입고 때 생긴다). 이 이슈의 테스트 데이터는 SQL로 넣는다.

**용어**
| 용어 | 뜻 |
|---|---|
| 상품 코드 (`productCode`) | 재고를 따로 세는 단위마다 붙이는 상품의 고유 코드. 같은 티셔츠라도 색, 사이즈가 다르면 코드가 다르다. 예: `TS-BASIC-BLK-M` |
| 상품 (`Product`) | 상품 코드와 상품명. 거의 바뀌지 않는다 |
| 재고 (`Inventory`) | 상품 하나의 현재 수량. 입고, 출고 때마다 바뀐다 |

### 설계

**데이터** — 상품과 재고를 1:1 두 테이블로 나눈다 (`inventory/schema`의 `V7_1__create_product_inventory.sql`)

```sql
CREATE TABLE product (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_code  VARCHAR(64)  NOT NULL,
    name          VARCHAR(200) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ,
    CONSTRAINT uk_product_product_code UNIQUE (product_code)
);

CREATE TABLE inventory (
    product_id  BIGINT      PRIMARY KEY REFERENCES product (id),
    quantity    BIGINT      NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ,
    CONSTRAINT ck_inventory_quantity_non_negative CHECK (quantity >= 0)
);
```
- 내부 참조는 `product.id`(BIGINT), 외부 식별은 `product_code`. `product_code`는 API로만 노출하고 `id`는 노출하지 않는다.
- `inventory`의 PK가 `product_id`라서 상품 하나에 재고 행은 하나뿐이다.
- `created_at`은 생성 때, `updated_at`은 수정 때만 채운다. 생성 직후 `updated_at`은 NULL이라 한 번도 바뀌지 않은 행을 구분할 수 있다. `created_at`의 `DEFAULT now()`는 SQL로 직접 넣는 데이터용이고, 앱은 `BaseTimeEntity`가 값을 넣는다.
- 상품 코드는 전체에서 고유하다(`UNIQUE (product_code)`). 여러 회사(테넌트)가 같은 창고를 쓰면 회사마다 같은 코드를 붙일 수 있지만, 테넌트 구분은 이 이슈 범위 밖이라 한 회사 기준으로 만든다. 테넌트를 도입할 때는 `tenant_id`를 추가하고 UNIQUE를 `(tenant_id, product_code)`로 바꾼다.

**왜 두 테이블로 나누나** (대안: `product`에 `quantity` 컬럼을 두는 한 테이블)

장점
1. 재고 변경과 상품 정보 수정이 서로 간섭하지 않는다.
   - 한 테이블이면 재고 변경(`FOR UPDATE`)과 상품 정보 수정이 같은 행 잠금을 두고 서로 기다린다.
   - JPA는 UPDATE 때 모든 컬럼을 쓴다. 한 테이블이면 잠금 없이 상품을 읽은 수정 트랜잭션이, 그 사이 커밋된 출고의 수량을 이전 값으로 덮어쓸 수 있다 (수량 10 읽음 → 출고가 7로 변경 → 수정이 `quantity = 10`으로 커밋). 두 테이블이면 상품 수정이 수량 컬럼을 갖지 않아 이런 일이 구조적으로 생기지 않는다.
   - 지금은 상품 수정 API가 없어, 앞으로 생길 수정 기능을 대비한 근거다.
2. 창고 위치별 재고로 확장할 때 바꿀 범위가 `inventory`로 좁다. `inventory`의 PK는 바뀌지만 `product`와 상품을 참조하는 쪽은 그대로다. 한 테이블이면 테이블을 쪼개야 하고, 롤링 배포 중 구버전이 함께 돌아서 컬럼 추가, 코드 전환, 삭제로 배포를 여러 번 나눠야 한다.

비용
- 조회는 `product_code` UNIQUE 인덱스와 `inventory` PK로 인덱스 조회 한 번이 늘 뿐이라 속도 차이가 거의 없다.
- "상품이 있으면 재고도 있다"를 DB가 강제하지 못한다. 입고가 상품과 재고 행을 한 트랜잭션에서 함께 만들어 지킨다.
- 엔티티와 리포지토리가 각각 두 개다.

아키텍처 규칙도 상품(`Product`)과 재고(`Inventory`)를 별도 애그리거트로 정해 두었다.

**왜 상품 코드를 PK로 쓰지 않나**
- 상품 코드는 오타 수정, 코드 체계 개편으로 바뀔 수 있다. PK면 재고, 이후의 변동 이력처럼 이 값을 참조하는 모든 테이블을 함께 고쳐야 한다.
- 행이 계속 쌓이는 변동 이력의 FK가 `BIGINT` 대신 `VARCHAR(64)`가 되어 인덱스가 커진다.
- 잠금 순서, 애그리거트 간 참조 등 아키텍처 규칙이 `productId` 기준이다.
- 테넌트를 도입하면 상품 코드만으로는 고유하지 않게 된다. PK가 `id`라서 그때도 UNIQUE 제약만 바꾸면 되고, 상품을 참조하는 테이블은 그대로다.

**도메인** (`inventory/core`)
| 패키지 | 클래스 | 역할 |
|---|---|---|
| `product.entity` | `ProductCode` (VO, record) | 형식 검증 (R1) |
| `product.entity` | `Product` (엔티티) | `id`, `productCode`, `name`. 조회에 필요한 필드만 매핑 |
| `inventory.entity` | `Quantity` (VO, record) | 0 이상 검증 (R2) |
| `inventory.entity` | `Inventory` (엔티티) | `productId`, `quantity`. `Product`는 객체가 아니라 `productId`로 참조 |
| `inventory.port` | `InventoryStore` | `findStockByProductCode(ProductCode)` → `Optional<StockResult>`. 상품과 재고를 조인해 상품 코드, 상품명, 수량만 읽는 조회 전용 projection |
| `inventory` | `InventoryService` | `getStock(ProductCode)` → `StockResult` |
| `inventory.model` | `StockResult` (record) | `productCode`, `name`, `quantity`. 서비스 결과이자 조인 projection 결과 |
| `common` | `BaseTimeEntity` | `@MappedSuperclass`. `@PrePersist`에서 `createdAt`만, `@PreUpdate`에서 `updatedAt`만 채운다. `Product`, `Inventory`가 상속 |
| `common` | `BusinessException`, `ErrorCode`, `ErrorKind` | 비즈니스 예외 |

`ErrorCode`: `INVALID_PRODUCT_CODE`(INVALID), `INVALID_QUANTITY`(INVALID), `PRODUCT_NOT_FOUND`(NOT_FOUND).

상품과 재고를 새로 만드는 메서드(`Product.register`, `Inventory.create`)와 상품명 검증은 이 이슈에 넣지 않는다. #7은 조회만 하고 테스트 데이터는 SQL로 넣어서 부르는 곳이 없다. 처음 쓰는 입고 이슈에서 함께 추가한다. 입고, 출고에 필요한 에러 코드도 그 이슈에서 추가한다.

**db** (`inventory/db`): `InventoryJpaRepository`(조인 JPQL), `InventoryStoreAdapter` (package-private). 상품만 따로 읽는 `ProductStore`는 #7에서 쓰는 곳이 없어 입고 이슈에서 추가한다

**API** (`app`)

`GET /api/v1/inventory/{productCode}`

| 상황 | 상태 | 본문 |
|---|---|---|
| 등록된 상품 | 200 | `{"productCode": "TS-BASIC-BLK-M", "name": "베이직 반팔 티셔츠 검정 M", "quantity": 5}` |
| 없는 상품 | 404 | `ProblemDetail` + `"code": "PRODUCT_NOT_FOUND"` |
| 형식이 틀린 코드 | 400 | `ProblemDetail` + `"code": "INVALID_PRODUCT_CODE"` |

- `/api`는 `application.yml`의 `server.servlet.context-path: /api`가 붙이고, 컨트롤러는 `/v1/inventory`만 갖는다. actuator와 Swagger도 함께 `/api` 아래로 간다(헬스 체크 `/api/actuator/health`).
- 경로를 `inventory` 아래에 두는 이유: 이 API가 돌려주는 것은 상품 정보가 아니라 재고이고, 입고, 출고 API도 같은 경로 아래로 모인다. `v1`은 응답 형식이 바뀔 때 기존 클라이언트를 깨지 않고 새 버전을 낼 수 있게 붙인다.
- `GlobalExceptionHandler`는 `BusinessException`만 처리한다. `ErrorKind` → HTTP 상태 매핑(`ErrorStatusMapper`)은 4종을 모두 둔다 (`default` 없는 switch라 하나라도 빠지면 컴파일 에러).
- 표준 MVC 예외, 락 대기 초과, 500 응답 형식은 공통 에러 응답 이슈 범위라 여기서는 다루지 않는다.

**흐름**: `@Transactional(readOnly = true)` 안에서 상품과 재고를 한 쿼리로 조인해 읽는다 → 결과가 없으면 `PRODUCT_NOT_FOUND`. 조회만 하므로 락은 걸지 않는다.
- 한 쿼리로 읽는 이유: 상품과 재고는 생명주기가 같다. 입고가 둘을 한 트랜잭션에서 함께 만들고, 재고는 상품 없이 존재할 수 없다(외래 키). 응답에 필요한 값도 상품 코드, 상품명, 수량 셋뿐이라 엔티티 두 개를 따로 읽을 이유가 없다.
- 재고를 바꾸는 입고, 출고는 이 projection이 아니라 재고 엔티티를 잠그고 엔티티 메서드로 바꾼다.

### 비즈니스 규칙
- R1. 상품 코드는 영문 대문자, 숫자, 하이픈으로 된 1~64자다. 소문자를 대문자로 바꿔 주지 않고 거절한다.
  - 이유: UNIQUE 제약은 글자가 정확히 같을 때만 막는다. 형식이 자유로우면 `ts-basic`, `TS-BASIC`, `TS-BASIC `처럼 사람 눈에는 같은 코드가 서로 다른 상품으로 저장된다. 입력에서 한 가지 형태만 받아 중복을 막는다. 자동 변환하지 않는 이유는 클라이언트가 보낸 코드와 저장된 코드가 달라지면 연동하는 쪽에서 찾지 못하기 때문이다. 64자는 실제 코드가 들어가기에 넉넉한 상한이다.
- R2. 재고 수량은 0 이상이다.
- R3. 같은 상품 코드의 상품은 DB에 하나만 저장된다.
- R4. 재고 수량이 음수인 행은 DB에 저장되지 않는다.
- R5. 재고 행은 존재하는 상품에만 생긴다.
- R6. 등록된 상품 코드로 조회하면 상품 코드, 상품명, 현재 수량을 돌려준다.
- R7. 등록되지 않은 상품 코드로 조회하면 404 `PRODUCT_NOT_FOUND`를 돌려준다.

### 예외 케이스
| 상황 | 결과 |
|---|---|
| 상품 코드 형식이 틀림 (소문자, 공백, 65자 이상) | 400 `INVALID_PRODUCT_CODE` |
| 등록되지 않은 상품 코드 | 404 `PRODUCT_NOT_FOUND` |
| 같은 상품 코드 INSERT, 음수 수량 저장 | DB가 제약 위반으로 거절 |

### 테스트 케이스
| ID | 대상 규칙 | Given | When | Then | 종류 |
|---|---|---|---|---|---|
| TC-7-01 | R1 | `TS-BASIC-BLK-M`, 64자 코드 | `ProductCode` 생성 | 생성됨 | 단위 |
| TC-7-02 | R1 | 빈 문자열, null, 65자, 소문자 포함, 공백 포함, `_` 포함 | `ProductCode` 생성 | `INVALID_PRODUCT_CODE` | 단위 |
| TC-7-03 | R2 | 0 | `Quantity` 생성 | 생성됨 | 단위 |
| TC-7-04 | R2 | -1 | `Quantity` 생성 | `INVALID_QUANTITY` | 단위 |
| TC-7-05 | R3 | `TS-BASIC-BLK-M` 상품 저장됨 | 같은 코드로 SQL INSERT | 유니크 제약 위반 | 통합 (PostgreSQL) |
| TC-7-06 | R4 | 재고 행 수량 0 | SQL로 수량을 -1로 UPDATE, -1로 INSERT | 체크 제약 위반, 수량 0 유지 | 통합 (PostgreSQL) |
| TC-7-07 | R5 | 상품 없음 | 없는 `product_id`로 재고 INSERT | 외래 키 제약 위반 | 통합 (PostgreSQL) |
| TC-7-08 | R6 | 상품 `TS-BASIC-BLK-M`, 수량 5 | `GET /api/v1/inventory/TS-BASIC-BLK-M` | 200, 상품 코드, 상품명, 수량 5 | 통합 (HTTP) |
| TC-7-09 | R7 | 상품 없음 | `GET /api/v1/inventory/NOT-EXIST` | 404, `code` = `"PRODUCT_NOT_FOUND"` | 통합 (HTTP) |
| TC-7-10 | R1 | | `GET /api/v1/inventory/ts-basic` | 400, `code` = `"INVALID_PRODUCT_CODE"` | 통합 (HTTP) |

- TC-7-05 ~ 07은 앱을 거치지 않고 SQL로 직접 넣어, 앱 검증이 없어도 DB가 막는지 확인한다. `inventory/schema` 모듈 테스트에서 실제 마이그레이션을 적용한 PostgreSQL로 검증한다.
- 엔티티와 스키마가 맞는지는 기존 기동 테스트(TC-1-01)가 `ddl-auto=validate`로 함께 확인한다.

### PR 계획
| PR | 브랜치 | 범위 | 포함 테스트 케이스 |
|---|---|---|---|
| 1 | `feat/7-inventory-schema` | 명세서, 스키마 마이그레이션(`V7_1`), DB 제약 테스트 (`Refs #7`) | TC-7-05 ~ TC-7-07 |
| 2 | `refactor/7-inventory-modules` | 실행 모듈을 `app/api`에서 `app`으로 올리고, 도메인 모듈을 `inventory/core`, `inventory/db`, `inventory/schema`로 묶고 `V7_1`과 제약 테스트를 `inventory/schema`로 이동, `support/flyway`는 Flyway 실행 설정과 공용 기초 데이터용으로 변경, 아키텍처 규칙과 README 반영, 규칙의 SKU 표기를 productCode로 변경 (`Refs #7`) | 없음 (동작 변경 없음, TC-7-05 ~ 07이 새 위치에서 통과) |
| 3 | `feat/7-inventory-query` | 도메인 모델(VO, 엔티티, `BaseTimeEntity`, 예외), Store와 Adapter, 조회 서비스, 조회 API, 404/400 응답 (`Closes #7`) | TC-7-01 ~ TC-7-04, TC-7-08 ~ TC-7-10 |

- PR 2를 넣은 이유: 모듈마다 한 가지 책임만 갖도록 응집도를 높인다. `app`은 실행과 도메인 조립, `inventory/core`는 도메인 규칙, `inventory/db`는 저장 구현, `inventory/schema`는 테이블 정의만 맡는다. `support/flyway`에 도메인 테이블을 두면 공용 기초 데이터와 도메인 스키마가 한곳에 섞이고, 도메인이 늘면 모든 테이블 SQL이 한 모듈에 쌓인다. 도메인 코드를 처음 넣는 PR 3 전에 모듈 구조를 먼저 바꿔, 조회 기능 코드가 처음부터 새 구조에 들어가게 한다.
- 나누는 이유: 스키마는 앱 코드 없이 SQL만으로 완결되고, DB 제약을 SQL로 직접 검증할 수 있다. PR 1만 머지돼도 노출되는 기능이 없어 단독으로 머지할 수 있다. 리뷰도 "테이블과 제약이 맞나"와 "도메인 규칙과 API 계약이 맞나"를 따로 본다.

### 결정 필요
- 없음 (상품 코드 형식은 R1, API 경로는 `GET /api/v1/inventory/{productCode}`로 확정)

### 변경 이력
- 설계 결정마다 이유와 비교한 대안을 추가 (승인 전 보완)
- 두 테이블로 나누는 이유를 장점(간섭 없음, 확장 범위)과 비용으로 다시 정리 (승인 전 보완)
- 상품 코드는 전체 고유로 두고 테넌트별 고유는 범위 밖으로 명시, SKU 용어 설명 제거 (승인 전 보완)
- PR을 스키마와 기능(도메인 + API)으로 다시 나누고, `updated_at`은 수정 때만 채우도록 변경 (승인 전 보완)
- 생성 메서드와 상품명 검증을 입고 이슈로 미루고 규칙, TC 번호를 다시 매김. 결정 필요 항목 확정 (승인 전 보완)
- 모듈 구조 변경(PR 2)을 추가하고 조회 기능을 PR 3으로 이동. 스키마 위치를 `inventory/schema`로 변경 (PR 1 머지 후)
- 도메인 저장 포트 이름을 `XxxRepository`에서 `XxxStore`로 변경. Spring Data의 `JpaRepository`와 이름이 겹치지 않게 하기 위해서 (PR 3)
- 애그리거트 패키지 안을 `entity/`(엔티티와 VO), `port/`(저장 포트)로 나누고 서비스는 애그리거트 바로 아래에 두도록 변경 (PR 3)
- 조회를 상품, 재고 두 번 읽기에서 조인 projection 한 쿼리로 변경. 상품과 재고의 생명주기가 같기 때문. 쓰는 곳이 없어진 `ProductStore`는 입고 이슈로 미룸 (PR 3)
- 서비스 입출력 record(`StockResult`)를 `model/` 패키지로 분리. 엔티티(`entity/`)와 바깥에 보이는 데이터를 구분하기 위해서 (PR 3)
- `/api` 접두사를 컨트롤러에서 `server.servlet.context-path`로 옮김. 헬스 체크 경로가 `/api/actuator/health`로 바뀌어 TC-1-01, TC-1-19의 호출 경로도 변경 (PR 3)
