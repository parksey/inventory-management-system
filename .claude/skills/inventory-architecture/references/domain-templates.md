# 코드 템플릿 — domain

---

## 1. VO(record)

```java
package com.logistics.inventory.domain.inventory.entity;

@Embeddable
public record Quantity(long value) {

    public Quantity {
        if (value < 0) {
            throw BusinessException.of(INVALID_QUANTITY, "value=" + value);
        }
    }

    public static Quantity zero() {
        return new Quantity(0);
    }

    public Quantity plus(final Quantity other) {
        return new Quantity(Math.addExact(value, other.value));
    }

    public Quantity minus(final Quantity other) {
        return new Quantity(value - other.value);
    }

    public boolean isLessThan(final Quantity other) {
        return value < other.value;
    }

    public boolean isZero() {
        return value == 0;
    }
}
```
- 검증은 compact constructor 한 곳 — 어떤 경로로 만들어져도 잘못된 값이 존재할 수 없다.
- `Math.addExact`로 overflow도 예외로 막는다.

---

## 2. 엔티티

```java
package com.logistics.inventory.domain.inventory.entity;

@Entity
@Table(name = "inventory")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Inventory extends BaseTimeEntity {

    @Id
    @Column(name = "product_id")
    private Long productId;                 // 다른 애그리거트(Product)는 ID로 참조

    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "quantity", nullable = false))
    private Quantity quantity;

    // 빌더는 클래스 안에서만 쓴다. 밖에서는 정적 팩토리로만 만든다
    @Builder(access = AccessLevel.PRIVATE)
    private Inventory(final Long productId, final Quantity quantity) {
        this.productId = Objects.requireNonNull(productId);
        this.quantity = quantity;
    }

    // 상품이 새로 등록될 때 함께 만드는 재고. 수량 0에서 시작한다
    public static Inventory create(final Long productId) {
        return Inventory.builder()
            .productId(productId)
            .quantity(Quantity.zero())
            .build();
    }

    public StockChange increase(final Quantity amount) {
        requirePositive(amount);
        final Quantity before = quantity;
        this.quantity = quantity.plus(amount);
        return StockChange.inbound(productId, amount, before, quantity);
    }

    public StockChange decrease(final Quantity amount) {
        requirePositive(amount);
        if (quantity.isLessThan(amount)) {
            throw BusinessException.of(INSUFFICIENT_STOCK,
                    "productId=%d, 현재=%d, 요청=%d".formatted(productId, quantity.value(), amount.value()));
        }
        final Quantity before = quantity;
        this.quantity = quantity.minus(amount);
        return StockChange.outbound(productId, amount, before, quantity);
    }

    private void requirePositive(final Quantity amount) {
        if (amount.isZero()) {
            throw BusinessException.of(INVALID_QUANTITY, "value=" + amount.value());
        }
    }

}
```

```java
// 상태 변경의 결과 — record
public record StockChange(Long productId, StockChangeType type, Quantity amount, Quantity before, Quantity after) {
    public static StockChange inbound(...)  { ... }
    public static StockChange outbound(...) { ... }
}
```

```java
// domain/common — Spring Data Auditing(@CreatedDate)은 spring-data 의존이 필요하므로 JPA 콜백으로 처리
@MappedSuperclass
@Getter
public abstract class BaseTimeEntity {

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        final OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}
```

---

## 3. Store 인터페이스

```java
package com.logistics.inventory.domain.inventory.port;

public interface InventoryStore {

    /** 재고 변경용 조회 — 커밋까지 다른 트랜잭션의 변경을 막는 행 잠금을 건다. */
    Optional<Inventory> findByProductIdForUpdate(Long productId);

    /** 새 재고를 저장한다. 이미 저장된 재고의 변경은 트랜잭션 커밋 때 변경 감지가 저장한다. */
    void save(Inventory inventory);   // 어댑터는 persist (id를 직접 넣는 엔티티라 merge를 피한다)
}
```

```java
package com.logistics.inventory.domain.product.port;

public interface ProductStore {

    Optional<Product> findByProductCode(ProductCode productCode);

    /**
     * 같은 상품 코드가 없을 때만 저장한다. 다른 트랜잭션이 넣는 중이면 그 트랜잭션이 끝날 때까지 기다린다.
     * 이 트랜잭션이 실제로 넣었으면 true, 이미 있었으면 false.
     */
    boolean saveIfAbsent(Product product);   // 어댑터는 INSERT ... ON CONFLICT DO NOTHING
}
```
- 시그니처는 도메인 언어로. `JpaRepository`, `Page`, `Specification` 같은 Spring Data 타입을 노출하지 않는다.

---

## 4. 서비스, 도메인 서비스, Command

```java
public record InboundCommand(ProductCode productCode, Quantity quantity, RequesterId requesterId) {}
public record OutboundCommand(ProductCode productCode, Quantity quantity, RequesterId requesterId) {}
```
- 상태를 바꾸는 유스케이스는 결과 record를 돌려주지 않는다(`void`). 조회만 결과 record(`StockResult`)를 돌려준다.

```java
package com.logistics.inventory.domain.inventory;

/** 상품을 찾고, 없으면 등록한다. 상품이 생기면 수량 0인 재고도 반드시 함께 생긴다. */
@Component
@RequiredArgsConstructor
public class ProductRegistrar {

    private final ProductStore productStore;
    private final InventoryStore inventoryStore;

    public Product getOrRegister(final ProductCode productCode) {
        return productStore.findByProductCode(productCode).orElseGet(() -> register(productCode));
    }

    // 다른 트랜잭션이 같은 상품을 먼저 넣었으면 그쪽이 재고도 만들었으므로, 이 트랜잭션이 넣었을 때만 재고를 만든다
    private Product register(final ProductCode productCode) {
        final boolean created = productStore.saveIfAbsent(Product.register(productCode));
        final Product product = productStore.findByProductCode(productCode).orElseThrow();
        if (created) {
            inventoryStore.save(Inventory.create(product.getId()));
        }
        return product;
    }
}
```
- "A가 생기면 B도 생긴다"는 규칙을 이름 있는 도메인 서비스로 둔다. 상품을 만드는 길이 이것 하나라 재고가 빠지지 않는다.
- `persist`한 재고는 바로 다음 잠금 조회 전에 Hibernate가 INSERT한다(`FlushMode.AUTO`).

```java
package com.logistics.inventory.domain.inventory;

@Service
@RequiredArgsConstructor
public class InventoryService {

    private final ProductRegistrar productRegistrar;
    private final ProductStore productStore;
    private final InventoryStore inventoryStore;
    private final StockTransactionStore stockTransactionStore;

    @Transactional
    public void inbound(final InboundCommand command) {
        // 상품 (Product) — 없으면 수량 0인 재고와 함께 등록
        final Product product = productRegistrar.getOrRegister(command.productCode());
        // 재고 (Inventory) — 행 잠금 후 증가
        final Inventory inventory = getInventoryForUpdate(product.getId());
        final StockChange change = inventory.increase(command.quantity());   // 규칙은 엔티티가
        // 원장 (StockTransaction)
        stockTransactionStore.save(StockTransaction.record(change, command.requesterId()));
    }

    @Transactional
    public void outbound(final OutboundCommand command) {
        // 상품 (Product)
        final Product product = productStore.findByProductCode(command.productCode())
                .orElseThrow(() -> BusinessException.of(PRODUCT_NOT_FOUND, "productCode=" + command.productCode().value()));
        // 재고 (Inventory) — 행 잠금 후 감소
        final Inventory inventory = getInventoryForUpdate(product.getId());
        final StockChange change = inventory.decrease(command.quantity());
        // 원장 (StockTransaction)
        stockTransactionStore.save(StockTransaction.record(change, command.requesterId()));
    }

    // 입고와 출고가 함께 쓴다. 상품이 있는데 재고 행이 없으면 데이터 손상 → 비즈니스 예외가 아니라 500
    private Inventory getInventoryForUpdate(final Long productId) {
        return inventoryStore.findByProductIdForUpdate(productId)
                .orElseThrow(() -> new IllegalStateException("상품은 있는데 재고 행이 없습니다. productId=" + productId));
    }
}
```
- 서비스에는 `if (재고 < 요청)` 같은 규칙 판단이 없다. 조회 → 엔티티 행위 → 기록 흐름만 있다.
- 재고 변경과 원장 기록이 한 트랜잭션. 변경된 `Inventory`는 `save`를 부르지 않고 변경 감지로 저장된다.

---

## 5. 예외, ErrorCode

```java
package com.logistics.inventory.domain.common;

// 도메인 개념의 에러 종류. HTTP 상태로의 매핑은 app이 한다 — 도메인은 웹을 모른다
public enum ErrorKind { INVALID, NOT_FOUND, CONFLICT, UNPROCESSABLE }

```

```java
package com.logistics.inventory.domain.common;

// 상수 이름 = 외부 code. 중복은 컴파일러가 막는다. 이름에는 도메인 명사를 넣는다 (NOT_FOUND가 아니라 PRODUCT_NOT_FOUND)
// 도메인이 늘어 enum을 나눠야 할 때 인터페이스로 뽑는다
@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    INVALID_PRODUCT_CODE("상품 코드 형식이 올바르지 않습니다.", ErrorKind.INVALID),
    INVALID_PRODUCT_NAME("상품명이 올바르지 않습니다.", ErrorKind.INVALID),
    INVALID_QUANTITY("수량이 올바르지 않습니다.", ErrorKind.INVALID),
    PRODUCT_NOT_FOUND("상품을 찾을 수 없습니다.", ErrorKind.NOT_FOUND),
    INSUFFICIENT_STOCK("재고가 부족합니다.", ErrorKind.CONFLICT),
    IDEMPOTENCY_KEY_MISMATCH("같은 멱등성 키로 다른 요청이 들어왔습니다.", ErrorKind.UNPROCESSABLE);

    private final String message;
    private final ErrorKind kind;
}
```

```java
package com.logistics.inventory.domain.common;

// 비즈니스 예외는 이 클래스 하나. 에러마다 하위 클래스를 만들지 않는다
@Getter
public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;

    private BusinessException(final ErrorCode errorCode, final String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public static BusinessException of(final ErrorCode errorCode) {
        return new BusinessException(errorCode, errorCode.getMessage());
    }

    // detail에는 원인 추적용 식별자와 값만 넣는다. 기본 문구는 자동으로 앞에 붙는다
    public static BusinessException of(final ErrorCode errorCode, final String detail) {
        return new BusinessException(errorCode, errorCode.getMessage() + " " + detail);
    }
}
```

사용:
```java
throw BusinessException.of(INSUFFICIENT_STOCK,
        "productId=%d, 현재=%d, 요청=%d".formatted(productId, quantity.value(), amount.value()));
// getMessage() → "재고가 부족합니다. productId=1, 현재=3, 요청=5", 응답 code → "INSUFFICIENT_STOCK"
```

**컴파일러가 막는 것**: enum 안의 code 중복(상수 이름), 종류 누락(생성자 인자), 문자열 code로 던지기(`of`는 `ErrorCode`만 받음), 종류별 HTTP 매핑 누락(app의 `default` 없는 switch).
**컴파일러가 못 막는 것**: 상수 이름 변경(= 외부 계약 변경) → API 테스트가 `$.code`를 **문자열 리터럴**로 검증해서 잡는다. 테스트에서 enum 상수를 참조하면 IDE rename에 같이 바뀌어 잡지 못한다.

- 종류(kind)는 enum이 정한다. 던지는 쪽이 `conflict(...)`처럼 종류를 고르지 않는다 — 같은 code가 호출 경로에 따라 다른 HTTP 상태로 나가면 클라이언트가 code를 믿고 분기할 수 없다.
- **별도 예외 클래스는 코드에서 잡아 처리해야 할 때만** 만든다 (예: `DuplicateIdempotencyKeyException` — 멱등성 래퍼가 잡아 기존 결과를 재조회. HTTP로 나가지 않으므로 `BusinessException`이 아니다).
- 도메인 단위 테스트는 `.extracting("errorCode").isEqualTo(INSUFFICIENT_STOCK)`로, API 테스트는 `jsonPath("$.code").value("INSUFFICIENT_STOCK")`로 검증한다.
