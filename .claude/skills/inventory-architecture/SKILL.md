---
name: inventory-architecture
description: 재고 관리 시스템 프로젝트의 아키텍처·도메인 설계 규칙 (실용주의 헥사고날 + app, inventory(core/db/schema), flyway, p6spy 멀티모듈 + DDD/객체지향 + scale-out 동시성). 이 프로젝트에서 Java 코드를 작성·수정·리뷰할 때는 반드시 먼저 읽을 것 — 새 API·엔티티·VO·Repository·서비스 추가, 패키지/모듈 배치 결정, build.gradle 의존성 변경, 예외 처리, 트랜잭션·락 처리, 리팩터링 등 "코드가 어느 모듈에 어떤 모양으로 들어가야 하는지"가 걸린 모든 작업에 해당한다. 사용자가 규칙을 명시적으로 언급하지 않아도 적용한다.
---

# 재고 관리 시스템 아키텍처 규칙

이 문서는 이 프로젝트의 **합의된 설계 결정**이다.
코드를 작성하기 전에 해당 섹션을 확인하고 규칙과 다르게 가야 할 이유가 생기면 임의로 바꾸지 말고 사용자에게 먼저 알린다.
규칙마다 이유를 함께 적었다 — 애매한 상황에서는 이유를 기준으로 판단한다.

- 기술 스택: Java 21, Spring Boot (버전은 프로젝트 세팅 시 확정), Spring Data JPA, PostgreSQL, Flyway, P6Spy, Gradle(Kotlin DSL)
- 베이스 패키지: `com.logistics.inventory`
- 배포 전제: Helm으로 **다중 Pod scale-out** → 동시성·정합성은 반드시 DB 레벨에서 보장
- 코드 예시 — 작업에 해당하는 파일만 읽는다. 이름·필드는 요구에 맞게 바꾸되 **구조(어느 모듈, 무엇이 무엇을 호출하는지)는 바꾸지 않는다**:
  - 빌드 스크립트·모듈 의존성·p6spy 설정·application.yml·Flyway 마이그레이션 이미지: `references/build-setup.md`
  - VO·엔티티·Store 인터페이스·서비스·Command/Result·예외/ErrorCode: `references/domain-templates.md`
  - db Adapter·JPA 설정·Controller·Request/Response·예외 핸들러: `references/adapter-templates.md`

---

## 1. 모듈 구조와 의존 방향

실행 모듈은 `app` 하나이고, 실행 보조 모듈은 `support/` 아래에 두고, 도메인 모듈은 `{도메인}/core`, `{도메인}/db`, `{도메인}/schema` 세 개를 도메인 디렉토리 아래에 묶는다.

| 디렉토리 | Gradle 경로 | 이하 부르는 이름 |
|---|---|---|
| `app` | `:app` | `app` |
| `inventory/core` | `:inventory:core` | `domain` |
| `inventory/db` | `:inventory:db` | `db` |
| `inventory/schema` | `:inventory:schema` | `schema` |
| `support/flyway` | `:support:flyway` | `flyway` |
| `support/p6spy` | `:support:p6spy` | `p6spy` |

`support`, `inventory`는 코드 없는 컨테이너 프로젝트다. 확장할 때는 `{도메인}/core`, `{도메인}/db`, `{도메인}/schema`(예: `order/core`), `support/{기능}`(예: `support/logging`)으로 붙인다. 도메인 컨테이너의 `build.gradle.kts`에서 하위 모듈 `group`을 `com.logistics.{도메인}`으로 두고(Gradle은 모듈을 `group:name`으로 구분해서 `:inventory:core`와 `:order:core`가 섞이지 않게), jar 이름은 `archivesName`으로 `{도메인}-core`처럼 도메인을 붙인다 (bootJar 안에서 `core.jar`끼리 겹치지 않게).

두 번째 도메인이 생겨 다른 도메인에 공개할 계약이 필요해지면 그때 `{도메인}/api`(공개 인터페이스와 모델)를 추가한다. 쓰는 상대가 하나뿐인 지금은 구현이 하나인 인터페이스만 늘어나서 두지 않는다.

```
 app (bootJar) ──implementation──► domain ◄──compileOnly── db
   │                                                       │
   ├─ runtimeOnly ──► db                                   └─ testRuntimeOnly ──► flyway, schema
   ├─ runtimeOnly ──► schema
   ├─ runtimeOnly ──► flyway
   └─ runtimeOnly ──► p6spy
```

| 모듈 | 담는 것 | 허용 의존성 |
|---|---|---|
| `app` | **유일한 실행 모듈(bootJar)**. 모든 도메인을 조립하는 곳. `@SpringBootApplication`, Controller, 요청/응답 DTO, `GlobalExceptionHandler`, 웹 설정, `application.yml` | `implementation(project(":inventory:core"))`, `runtimeOnly(project(":inventory:db"))`, `runtimeOnly(project(":inventory:schema"))`, `runtimeOnly(project(":support:flyway"))`, `runtimeOnly(project(":support:p6spy"))`, spring-boot-starter-webmvc, validation, actuator, spring-tx(`org.springframework.dao` 예외 매핑용) |
| `domain` | 엔티티(JPA 어노테이션 포함), VO, Store(저장 포트) **인터페이스**, 도메인/애플리케이션 서비스, Command·Result, 도메인 예외·ErrorCode | `compileOnly` jakarta.persistence-api, spring-context, spring-tx (Hibernate 어노테이션이 필요하면 `compileOnly` hibernate-core) |
| `db` | Spring Data JPA Repository, domain Store 인터페이스의 구현체(Adapter), native query, JPA·DataSource 설정, `inventory-db.yml`(DataSource와 JPA 설정, 실행 단위별 값은 환경변수로) | `compileOnly(project(":inventory:core"))`(실행 시점 domain은 app이 넣는다), spring-boot-starter-data-jpa, postgresql, `testRuntimeOnly(project(":support:flyway"))`, `testRuntimeOnly(project(":inventory:schema"))` |
| `schema` | **이 도메인 테이블의 단일 원천**. `db/migration/V*__*.sql`만. main에 Java 코드 없음 (테스트는 Testcontainers PostgreSQL에 마이그레이션을 적용해 제약을 SQL로 검증) | main 의존성 없음. 테스트만 `support:flyway`, testcontainers-postgresql |
| `flyway` | Flyway 실행 의존성과 **도메인에 속하지 않는 공용 기초 데이터**(`db/migration`). 도메인 테이블은 두지 않는다. main에 Java 코드 없음 (테스트는 Testcontainers PostgreSQL로 마이그레이션 적용을 검증) | spring-boot-starter-flyway(Boot 4부터 Flyway 자동 설정이 별도 모듈), flyway-database-postgresql |
| `p6spy` | SQL 로깅. p6spy 스타터, SQL 포매터(`PrettySqlFormatter`), 포매터를 등록하는 AutoConfiguration(`META-INF/spring/...AutoConfiguration.imports`) | p6spy-spring-boot-starter, `compileOnly` spring-boot-autoconfigure, hibernate-core(포매터용) |

**금지**
- `app`이 `db`, `schema`, `flyway`, p6spy의 클래스를 import (그래서 `runtimeOnly`다 — 컴파일 에러로 막힌다)
- `domain`에 spring-web, spring-data-jpa(`JpaRepository` 등), DB 드라이버 의존성 추가
- `domain`이 다른 어떤 프로젝트 모듈도 참조
- `schema`, `flyway` 외의 모듈에 마이그레이션 SQL을 두거나, 도메인 테이블 마이그레이션을 `flyway`에 두거나, `ddl-auto`를 `validate` 외 값(create, update)으로 설정
- p6spy를 운영 프로파일에서 활성화 (`decorator.datasource.enabled=true`)
- 운영 설정을 프로파일 없는 기본 동작에 기대기 — 프로파일 미지정은 local이고, 운영은 `prod`를 명시해야만 운영 설정이 된다

**이유**
- 도메인이 저장소 인터페이스를 소유하고 db가 구현하는 의존성 역전(DIP)이 이 구조의 핵심이다. 엔티티에 JPA 어노테이션을 두는 것은 매핑 메타데이터만 허용한 의도적 타협이고, 도메인이 Spring Data나 DB 구현을 아는 순간 타협이 아니라 붕괴가 된다.
- 규칙을 문서가 아니라 **빌드가 강제**하게 만든 것이므로, 컴파일이 안 된다고 의존성을 추가해 우회하지 않는다.
- `schema`, `flyway` 분리: 테이블 SQL은 그 테이블을 쓰는 도메인의 `schema`에 두어 도메인 단위로 함께 옮기고 지울 수 있게 하고, Flyway 실행 설정과 공용 기초 데이터는 `flyway`에 둔다. 여러 jar의 `db/migration`은 Flyway가 classpath에서 한꺼번에 읽으므로 앱, 테스트, (나중의) 마이그레이션 Job이 같은 SQL을 쓴다. **현재는 모든 프로파일에서 앱 기동 시 마이그레이션**한다(Boot 기본값, 여러 Pod가 동시에 떠도 Flyway 락으로 하나만 실행). **Helm 차트를 도입할 때** 운영만 `spring.flyway.enabled=false`로 끄고 `pre-install/pre-upgrade` hook Job으로 옮긴다 — 그때 얻는 것: Pod 기동 지연 제거, 마이그레이션 실패 시 배포 중단(롤링 중 재시작 루프 방지), 앱 계정에서 DDL 권한 제거. 앱은 어느 경우든 `ddl-auto=validate`로 스키마와 엔티티 불일치를 검증한다.
- p6spy: 별도 모듈로 두고 app에는 `runtimeOnly`로만 넣어 app 코드가 의존하지 못하게 한다. 포매터 같은 로깅 코드가 app에 섞이지 않고, p6spy를 다른 방식으로 바꿔도 이 모듈 안에서 끝난다. jar는 운영 이미지에도 포함되므로, 운영 프로파일에서는 `decorator.datasource.enabled=false`로 DataSource 래핑 자체를 끄고 로컬·테스트에서만 켠다 (`enable-logging=false`는 로깅만 끄고 프록시 오버헤드는 남는다).
- 마이그레이션은 **하위 호환**으로 작성한다: pre-upgrade Job이 끝난 뒤에도 롤링 업데이트 동안 구버전 Pod가 새 스키마에서 돈다. 컬럼 삭제·이름 변경은 추가 → 코드 전환 → 삭제로 배포를 나눈다.

### 패키지 배치
```
inventory/core/    com.logistics.inventory.domain
  ├─ product/
  │    ├─ entity/    Product(엔티티), ProductCode(VO)
  │    └─ port/      ProductStore(저장 포트 인터페이스)
  ├─ inventory/      InventoryService
  │    ├─ entity/    Inventory, Quantity
  │    ├─ model/     Command/Result (StockResult 등, 바깥과 주고받는 record)
  │    └─ port/      InventoryStore
  ├─ transaction/    (같은 모양) entity/StockTransaction(원장), port/StockTransactionStore
  └─ common/         BusinessException, ErrorKind, ErrorCode, BaseTimeEntity 등 공통
inventory/db/      com.logistics.inventory.db
  ├─ product/        ProductJpaRepository, ProductStoreAdapter
  ├─ inventory/      ...
  └─ config/         JpaConfig (@EntityScan("...domain"), @EnableJpaRepositories("...db"))
inventory/schema/  src/main/resources/db/migration/   V{이슈번호}_{순번}__{설명}.sql — 예: V7_1__create_product_inventory.sql (Java 코드 없음)
support/flyway/    src/main/resources/db/migration/   공용 기초 데이터 (같은 버전 규칙, Java 코드 없음)
support/p6spy/     com.logistics.inventory.p6spy   PrettySqlFormatter, autoconfigure/P6SpyFormatterAutoConfiguration
app/               com.logistics.inventory.api
  ├─ inventory/      InventoryController, *Request, *Response
  ├─ common/         GlobalExceptionHandler, ErrorStatusMapper(ErrorKind → HttpStatus), ApiResponse
  ├─ InventoryApplication (scanBasePackages = "com.logistics.inventory")
  └─ resources/      application.yml, application-local.yml, application-prod.yml
```
패키지는 레이어(service/, repository/)가 아니라 **도메인(애그리거트) 단위**로 먼저 나눈다 — 관련 코드가 한곳에 모여야 경계가 보인다. 애그리거트 안에서는 `entity/`(엔티티와 그 VO, 도메인 안에서만 쓴다), `model/`(Command, Result처럼 app과 db가 주고받는 입출력 record), `port/`(db가 구현할 저장 포트)로 나누고, 서비스는 애그리거트 패키지 바로 아래에 둔다. 바깥 모듈이 보는 것(`model/`, `port/`)과 도메인 내부(`entity/`)가 패키지 이름으로 구분된다.

---

## 2. 레이어 간 데이터 흐름

```
Request DTO ──toCommand()──► Command ──► Service ──► Entity 행위 호출
                                            │
Response DTO ◄──from(result)── Result ◄─────┘
```

- **Controller는 엔티티를 받지도, 반환하지도 않는다.** 요청은 `Request.toCommand()`로 Command로 바꾸고, 응답은 `Response.from(result)`로 만든다. 엔티티가 API 응답에 노출되면 테이블 구조가 API 계약이 되고, 지연 로딩 예외와 순환 참조가 생긴다.
- Command·Result·Request·Response는 모두 **record**.
- Controller에는 비즈니스 로직이 없다 — 변환과 위임만 한다.
- 검증은 두 층으로 나눈다:
  - Request DTO: 형식 검증(Bean Validation — `@NotBlank`, `@Positive` 등). 잘못된 입력을 빨리 걸러낸다.
  - 도메인(엔티티·VO): 비즈니스 불변식(재고 음수 불가 등). API를 거치지 않는 호출에서도 규칙이 지켜져야 하므로 도메인 검증은 생략하지 않는다.

---

## 3. 도메인 모델링 (DDD · 객체지향)

### 3.1 엔티티
- **도메인 객체 = JPA 엔티티**. 별도 매핑 클래스나 매퍼를 만들지 않는다.
- 상태 변경은 **의도를 드러내는 메서드**로만 한다: `inventory.decrease(quantity)` (O) / `inventory.setQuantity(...)` (X).
- 불변식은 엔티티·VO 안에서 검증한다. 서비스에 `if (inventory.getQuantity() < n) throw ...` 같은 규칙 판단을 두지 않는다 — 물어보지 말고 시켜라(Tell, Don't Ask). 규칙이 서비스에 흩어지면 같은 규칙이 여러 곳에 복제되고 결국 어긋난다.
- 생성은 **정적 팩토리 메서드**(`Product.register(productCode, name)`)로 하고, 팩토리 안에서 불변식을 검증한다.
- JPA용 기본 생성자는 `@NoArgsConstructor(access = AccessLevel.PROTECTED)`로만 연다.
- getter는 외부에서 실제로 필요한 것만 둔다. 클래스 전체 `@Getter`보다 필드 단위가 기본.
- 다른 애그리거트는 **객체가 아니라 ID로 참조**한다 (`Inventory`는 `Product`가 아니라 `productId`를 가진다). 애그리거트 경계가 곧 락·트랜잭션의 경계이고, 객체 참조는 원치 않는 지연 로딩과 경계 침범을 부른다.

### 3.2 값 객체(VO)
- **record**로 만들고, compact constructor에서 검증한다 (`Quantity`는 음수 불가, `ProductCode`는 형식 검증).
- 원시 타입 대신 VO를 쓴다 — `long quantity`, `String productCode`가 메서드 시그니처에 떠다니면 순서 실수나 검증 누락을 컴파일러가 못 잡는다.
- 연산은 새 인스턴스를 반환한다 (`quantity.minus(other)`).
- 엔티티에는 `@Embeddable` record 또는 `AttributeConverter`로 매핑한다.

### 3.3 Lombok · final · record
| 대상 | 규칙 |
|---|---|
| `@Setter`, `@Data` | **전 모듈 금지** — 캡슐화를 무너뜨리고 불변식 우회 경로를 만든다 |
| 엔티티의 `@Builder`, `@AllArgsConstructor` | 사용하지 않는다 — 정적 팩토리를 우회해 불변식 검증 없이 객체가 생긴다 |
| `@Getter`, `@RequiredArgsConstructor`, `@NoArgsConstructor(access = PROTECTED)` | 허용 |
| `final` | 적극 사용: 주입받는 의존성(`private final`), 메서드 파라미터, 재할당하지 않는 지역 변수, 엔티티가 아닌 클래스의 필드. 단 **엔티티의 영속 필드는 final로 두지 않는다**(JPA 스펙상 프록시·리플렉션 주입 대상) |
| 긴 파라미터 목록 | 한 줄에 다 들어가지 않으면 파라미터를 한 줄에 하나씩 두고, 닫는 괄호 `) {`는 따로 한 줄에 둔다 |
| `record` | VO, Command, Result, Request/Response DTO, 이벤트. **엔티티는 record로 만들지 않는다**(JPA는 가변 객체·프록시가 필요) |

### 3.4 테이블의 일부 컬럼만 매핑하는 엔티티 (필요할 때만)
같은 테이블에 엔티티가 둘 이상 붙는 경우가 생기면 다음을 지킨다.
1. 테이블당 **INSERT를 책임지는 소유 엔티티는 하나**이며, 모든 NOT NULL 컬럼을 매핑한다.
2. 부분 매핑 엔티티는 INSERT하지 않는다. 읽기 전용이면 `@Immutable`, 수정이 필요하면 자기가 매핑한 컬럼만 수정하고, 한 컬럼을 수정하는 엔티티는 하나로 제한한다.
3. 같은 트랜잭션에서 같은 row를 서로 다른 엔티티로 함께 로딩하지 않는다 (1차 캐시가 달라 덮어쓰기 위험).
4. 단순 조회 목적이면 엔티티 대신 projection(DTO 조회)을 먼저 고려한다.

### 3.5 애그리거트 사이의 협력
- **흐름은 유스케이스 서비스가, 규칙은 애그리거트가.** 한 애그리거트 안의 로직은 엔티티·VO에 위임하고, 서비스는 여러 애그리거트를 어떤 순서로 부를지만 정한다.
- 애그리거트끼리는 **ID나 VO만** 주고받는다. 애그리거트 A의 엔티티를 B의 메서드에 넘기지 않는다.
- 애그리거트 A의 도메인 로직 안에서 B의 Store를 부르지 않는다. 여러 애그리거트의 Store를 함께 쓰는 곳은 유스케이스 서비스뿐이다.
- 서비스 메서드에서 애그리거트 경계가 바뀌는 지점에 한 줄 한국어 코멘트를 단다.
  ```java
  // 상품 (Product)
  final Product product = productStore.saveIfAbsent(Product.register(command.productCode(), command.name()));
  // 재고 (Inventory) — 행 잠금 후 증가
  final Inventory inventory = getForUpdate(product.getId());
  final StockChange change = inventory.increase(command.quantity());
  // 원장 (StockTransaction)
  stockTransactionStore.save(StockTransaction.record(change, command.idempotencyKey()));
  ```
- **일급 컬렉션**: 같은 스트림 필터·검사를 두 번 이상 쓰게 되면 `List<X>` 대신 컬렉션 클래스로 뽑고 도메인 의미가 있는 메서드를 둔다.

### 3.6 쓰지 않는 패턴
| 패턴 | 이유 |
|---|---|
| 구현이 하나뿐인 서비스에 인터페이스(`XxxUseCase`) | 포트는 실제 인프라 경계(Store 등)에만 둔다. 가짜 추상화는 읽을 파일만 늘린다 |
| 한 줄짜리 wrapper, 한 군데서만 쓰는 의미 없는 private helper | 인라인한다. helper는 중복을 없애면서 도메인 의미를 주는 것만 남긴다 (`getForUpdate` 같은) |
| 서비스의 읽기 전용 사전 검증(`assertXxx`) 후 별도 변경 | 락 없이 검증하고 나중에 바꾸면 그 사이에 다른 Pod가 값을 바꿀 수 있다. 불변식은 **락을 잡은 뒤 엔티티 메서드 안에서** 검증한다 |
| 여러 애그리거트를 한 번에 검사하는 Checker 클래스 | 규칙의 주인이 흐려진다. 규칙은 각 애그리거트가 가진다 |

---

## 4. Store (포트와 어댑터)

- 도메인의 저장 포트 이름은 `XxxStore`다. Spring Data의 `JpaRepository`, `@Repository`와 이름이 겹치지 않게 해서, 도메인 포트(`InventoryStore`), Spring Data 인터페이스(`InventoryJpaRepository`), 구현(`InventoryStoreAdapter`)이 이름만 보고 구분되게 한다.
- 인터페이스는 `domain`에 도메인 언어로 정의한다: `InventoryStore.findByProductIdForUpdate(...)`. Spring Data 타입(`Page`, `Specification` 등)을 도메인 인터페이스 시그니처에 노출하지 않는다.
- 구현은 `db`에 둔다:
  - `InventoryJpaRepository extends JpaRepository<Inventory, Long>` — Spring Data 인터페이스 (db 내부 전용, package-private 권장)
  - `InventoryStoreAdapter implements InventoryStore` — 도메인 인터페이스 구현, `@Repository`
- native query·락 힌트·`ON CONFLICT` 같은 DB 특화 코드는 전부 `db`에만 있다.

---

## 5. 서비스와 트랜잭션

- 유스케이스 서비스는 `domain` 모듈에 둔다 (`@Service`). 서비스는 **흐름을 조율**하고(조회 → 엔티티 행위 호출 → 기록), 규칙 판단은 엔티티에 위임한다.
- `@Transactional`은 서비스의 public 메서드에만 붙인다. 조회는 `@Transactional(readOnly = true)`. Controller와 db Adapter에는 붙이지 않는다.
- 원칙은 트랜잭션당 애그리거트 하나다. 예외는 두 가지뿐이고 다른 곳으로 넓히지 않는다:
  - 재고 변경과 원장(StockTransaction) 기록은 항상 **같은 트랜잭션** — 재고와 이력이 어긋나면 안 된다.
  - 입고는 여기에 Product 생성까지 묶는다 — 미등록 상품 입고가 한 요청이기 때문이다.
- DB 제약 위반(예: 멱등성 키 중복)을 잡아 복구해야 하면 **트랜잭션 밖의 별도 빈**에서 잡는다. PostgreSQL은 제약 위반 시 트랜잭션을 중단시키므로 같은 트랜잭션 안에서는 재조회할 수 없고, 같은 클래스 안 호출은 프록시를 거치지 않는다(self-invocation).

---

## 6. 동시성 · 정합성 (scale-out 전제)

Pod가 여러 개 떠 있으므로 **한 JVM 안에서만 유효한 수단은 정합성을 보장하지 못한다.**

**금지**: `synchronized`, `ReentrantLock` 등 JVM 락 / 재고 수량의 로컬 캐시 / 멱등성 키 등 상태의 인메모리 저장 / Redis 분산 락 도입(합의되지 않음 — 데이터가 PostgreSQL 하나에 있으므로 DB row 락이 이미 분산 락이다)

**재고 변경 패턴**
1. `findByProductIdForUpdate`(`@Lock(PESSIMISTIC_WRITE)`, `SELECT ... FOR UPDATE`)로 애그리거트를 로딩
2. 엔티티 메서드(`increase`/`decrease`)로 변경 — 불변식은 도메인이 검증
3. 변경 감지로 저장 + 같은 트랜잭션에서 원장 기록

- 신규 상품 최초 입고: `db`에서 `INSERT ... ON CONFLICT (product_code) DO NOTHING` 후 조회/락 — 여러 Pod가 같은 상품 코드를 동시에 입고해도 상품은 한 건.
- 여러 상품을 한 트랜잭션에서 잠글 때는 **productId 오름차순**으로 락을 획득한다 (데드락 방지).
- DB 제약은 최후 방어선으로 항상 둔다: `CHECK (quantity >= 0)`, `UNIQUE (product_code)`, `UNIQUE (idempotency_key)`. 앱 로직을 믿더라도 제약을 생략하지 않는다.
- 낙관적 락(`@Version`)은 재고처럼 같은 row에 경합이 몰리는 곳에 쓰지 않는다 (재시도 폭증).

---

## 7. 예외와 에러 응답

- 비즈니스 예외는 **`BusinessException` 하나**로 던진다: `throw BusinessException.of(INSUFFICIENT_STOCK, "productId=1, 현재=3, 요청=5")`. 에러마다 하위 클래스를 만들지 않는다 — 에러 하나 추가가 enum 한 줄 + 던지는 곳으로 끝나야 한다.
- 별도 예외 클래스는 **코드에서 잡아 처리해야 할 때만** 만든다 (`DuplicateIdempotencyKeyException` — 멱등성 래퍼가 잡아 복구).
- `ErrorCode`(domain)는 enum 하나다. 각 값은 기본 메시지와 **종류**(`ErrorKind`: INVALID / NOT_FOUND / CONFLICT / UNPROCESSABLE)를 가진다. **상수 이름이 곧 응답 code**다(`INSUFFICIENT_STOCK`) — 번호를 따로 두지 않는다. 중복을 사람이 아니라 컴파일러가 막게 하기 위해서다. 이름에는 도메인 명사를 넣는다(`PRODUCT_NOT_FOUND`). 도메인이 늘어 enum을 나눠야 할 때 인터페이스로 뽑는다.
- **HTTP 상태는 모른다** — 도메인은 웹을 모른다. 종류는 enum이 정하고, 던지는 쪽이 고르지 않는다 (같은 code가 경로마다 다른 상태로 나가면 클라이언트가 분기할 수 없다).
- 예외 메시지에는 원인을 추적할 식별자와 값을 넣는다. `of(code, detail)`이 기본 문구를 앞에 붙인다 (`"재고가 부족합니다. productId=1, 현재=3, 요청=5"`).
- 모든 응답(성공, 에러)은 `app`의 공통 응답 `ApiResponse`(`status`, `code`, `message`, `data`)로 내린다. 성공은 Controller가 `ApiResponse.ok(response)`로 감싸고 `code`는 `SUCCESS`다. 에러는 `GlobalExceptionHandler`가 `ErrorKind` → HTTP 상태로 매핑해 `code`에 `ErrorCode` 상수 이름을 담는다. 도메인 밖 오류(형식 검증, 경로, 락 대기 초과, 500)는 enum을 두지 않고 HTTP 상태 이름(`NOT_FOUND`)과 상태 문구를 code, message로 쓴다. 검증 실패의 틀린 항목은 `data`에 `[{field, reason}]`로 담는다.
- 상수 이름 변경은 외부 계약 변경이다. API 테스트는 `$.code`를 **문자열 리터럴**로 검증해 의도치 않은 rename을 잡는다 (enum 상수를 참조하면 IDE rename에 같이 바뀐다).
- 도메인 테스트는 메시지가 아니라 `errorCode`로 검증한다.
- 예상하지 못한 예외는 500으로 응답하되 스택트레이스·SQL 등 내부 정보를 응답에 담지 않는다.

---

## 8. 테스트

- domain: Spring 없이 순수 단위 테스트 (엔티티·VO의 불변식과 행위)
- db: Testcontainers PostgreSQL 기반, 스키마는 `flyway` 모듈 SQL로 생성 (H2 사용 금지 — `FOR UPDATE`, `ON CONFLICT`, CHECK 동작이 달라 동시성 검증이 무의미해진다)
- app: `@WebMvcTest`로 요청 검증·응답 형식·에러 매핑
- 동시성: 실제 PostgreSQL에 멀티스레드 동시 요청 → 최종 재고와 원장 합계 일치, 음수 없음 검증
- 아키텍처: 모듈 간 방향은 Gradle이 강제하므로, 모듈 내부 규칙(예: 도메인에 `@Setter` 없음, 서비스 외 `@Transactional` 없음)은 ArchUnit 테스트로 고정한다

---

## 작업 완료 전 체크리스트
- [ ] 새 클래스가 올바른 모듈·도메인 패키지에 있는가 (1장)
- [ ] build.gradle에 금지된 의존성을 추가하지 않았는가
- [ ] Controller가 엔티티를 받거나 반환하지 않는가
- [ ] 상태 변경이 setter가 아닌 의도가 드러나는 엔티티 메서드로 이뤄지는가, 불변식이 엔티티·VO 안에 있는가
- [ ] 재고 변경이 비관적 락 → 엔티티 메서드 → 같은 트랜잭션 원장 기록 패턴을 따르는가
- [ ] JVM 락·로컬 캐시·인메모리 상태를 쓰지 않았는가
- [ ] 스키마 변경은 `flyway` 모듈에 새 버전 파일로 추가했는가 (기존 V 파일 수정 금지 — 이미 적용된 환경과 체크섬이 어긋난다), 필요한 DB 제약을 함께 넣었는가, 구버전 코드와 호환되는가
- [ ] 규칙과 다르게 구현한 부분이 있다면 사용자에게 알렸는가
