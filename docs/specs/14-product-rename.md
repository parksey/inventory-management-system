# 기능 명세 — 상품명 수정 (#14)

### 요구사항 해석
- 입고로 처음 생긴 상품은 이름이 상품 코드다(`TS-BASIC-BLK-M`). 상품 코드로 상품을 지정해 실제 이름(`베이직 반팔 티셔츠 검정 M`)으로 바꿀 수 있다.
- 바꾼 뒤 재고 조회(`GET /api/v1/inventory/{productCode}`)의 `name`에 바뀐 이름이 나온다.
- 등록되지 않은 상품 코드면 404다. 입고와 달리 상품을 새로 만들지 않는다.
- 이름이 없거나 공백뿐이면 400이다. DB 컬럼(`name VARCHAR(200)`)보다 긴 이름도 400으로 거절한다.
- 요청자는 기록하지 않는다(Open Question 결정). 언제 바뀌었는지는 `product.updated_at`에 남는다.
- 상품 코드 변경, 상품 삭제는 범위 밖이다.

### 설계

**데이터** — 변경 없음. `product.name VARCHAR(200) NOT NULL`, `updated_at`이 이미 있다.

**도메인** (`inventory/core`)

| 패키지 | 클래스 | 역할 |
|---|---|---|
| `product.entity` | `ProductName` (VO, `@Embeddable` record) | 새로 추가. `null`, 빈 문자열, 공백뿐, 200자 초과면 `INVALID_PRODUCT_NAME` |
| `product.entity` | `Product` | `name` 필드 타입을 `String` → `ProductName`(컬럼 `name` 그대로). `rename(ProductName)` 추가. `register`는 `new ProductName(productCode.value())`로 이름을 만든다 |
| `product.model` | `RenameProductCommand` (record) | `productCode`, `name` |
| `product` | `ProductService.rename(RenameProductCommand)` | 상품 조회 → 없으면 `PRODUCT_NOT_FOUND` → `product.rename` |

`ErrorCode` 추가: `INVALID_PRODUCT_NAME`(INVALID).

이름 타입을 바꾸면서 함께 고치는 곳: 재고 조회 JPQL의 `p.name` → `p.name.value`, `ProductStoreAdapter.saveIfAbsent`의 `product.getName()` → `product.getName().value()`. `StockResult.name`은 `String` 그대로라 API 응답은 바뀌지 않는다.

**API** (`app`)

`PATCH /api/v1/products/{productCode}`
```http
PATCH /api/v1/products/TS-BASIC-BLK-M
Content-Type: application/json

{"name": "베이직 반팔 티셔츠 검정 M"}
```

| 상황 | 상태 | `code` |
|---|---|---|
| 수정 성공 | 200 | `SUCCESS`, `data` = `null` |
| 등록되지 않은 상품 코드 | 404 | `PRODUCT_NOT_FOUND` |
| 이름 없음, 빈 문자열, 공백뿐, 200자 초과 | 400 | `INVALID_PRODUCT_NAME` |
| 상품 코드 형식이 틀림 | 400 | `INVALID_PRODUCT_CODE` |
| 본문이 JSON이 아님 | 400 | `BAD_REQUEST` |

- `app/.../api/product/ProductController`가 받고 `ProductService`에 위임한다. 상품명은 재고가 아니라 상품의 속성이라 `/v1/inventory`가 아니라 `/v1/products` 아래에 둔다.
- `PATCH`를 쓴다. 상품의 일부(이름)만 바꾸는 요청이고, `PUT`은 상품 전체를 보내는 뜻이 되기 때문이다.
- `RenameProductRequest(String name)`, `toCommand(productCode)`. 상태를 바꾸는 요청이라 데이터를 돌려주지 않는다(아키텍처 규칙 2장). 바뀐 이름은 재고 조회로 읽는다.

**왜 이름 검증을 Request의 Bean Validation이 아니라 `ProductName` VO 한 곳에 두나**
- 입고 수량은 `InboundRequest`의 `@NotNull @Positive @Max`로 거르고(400 `BAD_REQUEST`), 도메인의 `Quantity`도 따로 검증한다. 같은 방식이면 이름은 `@NotBlank @Size(max = 200)`와 `ProductName`에 같은 규칙을 두 번 쓰게 된다.
- 이름 규칙은 "비어 있지 않고 200자 이하" 하나뿐이고, 둘 중 한 곳만 상한을 바꾸면 API로 들어온 이름과 다른 경로로 들어온 이름의 기준이 달라진다. #12에서 요청자 헤더를 `RequesterId` VO 한 곳에서만 검증하기로 한 것과 같은 이유다.
- 그래서 Request에는 검증 어노테이션을 붙이지 않고, 비어 있으면 VO가 `INVALID_PRODUCT_NAME`(400)을 던진다. 응답 code도 "무엇이 틀렸는지"를 바로 알려 준다.

**왜 이름을 `String`이 아니라 VO로 두나**
- 지금은 `Product.register`가 상품 코드를 이름으로 넣을 뿐 이름 규칙이 없다. `rename(String)`으로 두면 규칙이 `rename` 안에만 있고, 나중에 이름을 받는 다른 생성 경로(예: 상품 등록 API)가 생기면 같은 검사를 또 써야 한다.
- `ProductName`으로 두면 이름이 만들어지는 순간 검증되고, `Product`는 검증된 이름만 받는다(아키텍처 규칙 3.2).

**흐름** — `ProductService.rename`, `@Transactional` 하나
```java
// 상품 (Product) — 없으면 404. 이름 수정은 상품을 만들지 않는다
final Product product = productStore.findByProductCode(command.productCode())
    .orElseThrow(() -> BusinessException.of(PRODUCT_NOT_FOUND, "productCode=" + command.productCode().value()));
product.rename(command.name());
```
1. 상품 조회. 없으면 404
2. `product.rename` → 커밋 때 변경 감지로 `UPDATE product SET name = ?, updated_at = ? ...`

- 재고와 원장은 건드리지 않는다. 트랜잭션당 애그리거트 하나(상품) 원칙 그대로다.

**재고 변경이 상품명 수정을 막지 않는다** — 상품과 재고를 다른 테이블로 나눈 목적
- 재고 행은 입고, 출고가 몰리는 곳이라 `FOR UPDATE`로 자주, 길게 잠긴다. 수량이 상품 테이블에 함께 있었다면 상품명 수정이 그 잠금을 기다리고, 3초(`lock_timeout`)를 넘으면 503으로 실패한다. 수량을 `inventory`로 떼어 둔 덕분에 이름 수정은 재고 잠금과 상관없이 끝난다.
- 입고, 출고 트랜잭션이 상품 행에 거는 잠금은 하나뿐이다. 원장과 재고가 `product(id)`를 FK로 참조해서, 원장을 INSERT하면 PostgreSQL이 상품 행에 `FOR KEY SHARE`를 건다. 이름 수정의 `UPDATE`는 키 컬럼 값을 바꾸지 않으므로 `FOR NO KEY UPDATE`만 잡고, 이 둘은 서로 막지 않는다. Hibernate가 `UPDATE` 문에 `product_code`까지 넣지만 PostgreSQL은 값이 실제로 바뀌었는지로 판단한다.

**상품명 수정끼리는 잠그지 않는다**
- 같은 상품명을 두 Pod가 동시에 바꾸면 나중에 커밋한 쪽 이름이 남는다(A가 "검정 M", B가 "블랙 M"으로 바꾸면 늦게 커밋한 B의 "블랙 M"). 이름은 이전 값에 더하거나 빼는 값이 아니라 통째로 바꾸는 값이라, 마지막 요청이 이기는 것이 사용자가 기대하는 결과다. 재고처럼 "읽은 값을 기준으로 계산해서 쓰는" 경우가 아니라 잃어버리는 변경이 없다.

### 비즈니스 규칙
- R1. 등록된 상품의 이름을 바꾸면 이후 재고 조회의 `name`에 바뀐 이름이 나온다.
- R2. 등록되지 않은 상품 코드면 404 `PRODUCT_NOT_FOUND`이고, 상품을 만들지 않는다.
- R3. 상품명은 비어 있거나 공백뿐일 수 없고 200자 이하다. 어기면 400 `INVALID_PRODUCT_NAME`이고 이름은 그대로다.
- R4. 상품 코드 형식은 조회, 입고와 같은 규칙으로 검증한다 (400 `INVALID_PRODUCT_CODE`).
- R5. 같은 상품의 재고가 입고, 출고로 잠겨 있어도 상품명 수정은 기다리지 않고 성공한다.

### 예외 케이스

| 상황 | 결과 |
|---|---|
| 등록되지 않은 상품 코드 | 404 `PRODUCT_NOT_FOUND`, 상품이 생기지 않음 |
| `name` 없음, `null`, `""`, `"   "`, 201자 | 400 `INVALID_PRODUCT_NAME`, 이름 그대로 |
| 상품 코드 형식이 틀림 (`ts-basic`) | 400 `INVALID_PRODUCT_CODE` |
| 본문이 JSON이 아님 | 400 `BAD_REQUEST` |
| 같은 상품을 동시에 수정 | 나중에 커밋한 이름이 남음 |
| 같은 상품에 입고, 출고가 진행 중 | 기다리지 않고 200 |

### 테스트 케이스

| ID | 대상 규칙 | Given | When | Then | 종류 |
|---|---|---|---|---|---|
| TC-14-01 | R3 | | `new ProductName("베이직 반팔 티셔츠 검정 M")`, 200자 이름 | 생성됨 | 단위 |
| TC-14-02 | R3 | | `null`, `""`, `"   "`, 201자로 `ProductName` 생성 | `INVALID_PRODUCT_NAME` | 단위 |
| TC-14-03 | R1 | 이름이 상품 코드인 상품 | `rename(new ProductName("베이직 반팔 티셔츠 검정 M"))` | 이름이 바뀜 | 단위 |
| TC-14-04 | R1 | `TS-BASIC-BLK-M` (이름 `TS-BASIC-BLK-M`) | `{"name": "베이직 반팔 티셔츠 검정 M"}`로 수정 후 재고 조회 | 200, `data` 없음, 조회 `data.name` = `베이직 반팔 티셔츠 검정 M` | 통합 (HTTP) |
| TC-14-05 | R2 | 상품 1건 | `NOT-EXIST` 이름 수정 | 404 `PRODUCT_NOT_FOUND`, 상품 1건 그대로 | 통합 (HTTP) |
| TC-14-06 | R3 | `TS-BASIC-BLK-M` | `name` 없음, `null`, `""`, `"   "`, 201자로 수정 | 400 `INVALID_PRODUCT_NAME`, 이름 그대로 | 통합 (HTTP) |
| TC-14-07 | R4 | | `ts-basic` 이름 수정 | 400 `INVALID_PRODUCT_CODE` | 통합 (HTTP) |
| TC-14-08 | R5 | 다른 트랜잭션이 재고 행 `FOR UPDATE`, 원장 INSERT 후 커밋 전 | 이름 수정 | 200 (3초 대기 후 503이 아님), 이름이 바뀜 | 통합 (HTTP) |

- 단위는 `ProductNameTest`(새로), `ProductTest`(기존, TC-12-03의 이름 비교를 `ProductName`으로 고친다), HTTP는 `app`의 `api/product/ProductRenameApiTest`(새로).
- 상품명 수정끼리의 동시 수정은 테스트하지 않는다. 잠금이 없고 "마지막 커밋이 이긴다"는 PostgreSQL의 기본 동작이라 우리가 정한 코드를 검증하는 테스트가 아니다. 반면 재고 잠금과 서로 막지 않는 것(TC-14-08)은 테이블을 나눈 설계와 재고 흐름의 잠금 범위에 달린 성질이라 테스트로 고정한다.

### PR 계획

| PR | 브랜치 | 범위 | 포함 테스트 케이스 |
|---|---|---|---|
| 1 | `feat/14-product-rename` | 명세서, `ProductName`, `Product.rename`, `ProductService`, 상품명 수정 API, 테스트 (`Closes #14`) | TC-14-01 ~ TC-14-08 |

- 테이블 변경 없이 VO 하나, 메서드 하나, API 하나를 더하는 작은 변경이라 PR 하나로 충분하다.

### 결정 필요
- 없음 (요청자 기록 여부는 "기록 안 함"으로 결정됨)

### 변경 이력
- R5, TC-14-08 추가. 재고 변경이 상품명 수정을 막지 않는 것은 상품과 재고 테이블을 나눈 목적이라, "잠그지 않는 이유"의 부수 효과가 아니라 규칙으로 적고 테스트로 고정한다 (PR 리뷰)
