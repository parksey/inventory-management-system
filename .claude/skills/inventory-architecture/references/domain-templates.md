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
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Inventory extends BaseTimeEntity {

    @Id
    @Column(name = "product_id")
    @Getter
    private Long productId;                 // 다른 애그리거트(Product)는 ID로 참조

    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "quantity", nullable = false))
    @Getter
    private Quantity quantity;

    public static Inventory create(final Long productId) {
        final Inventory inventory = new Inventory();
        inventory.productId = Objects.requireNonNull(productId);
        inventory.quantity = Quantity.zero();
        return inventory;
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

    Optional<Inventory> findByProductId(Long productId);

    /** 재고 변경용 조회 — 다른 트랜잭션의 변경을 막는 행 잠금을 건다. */
    Optional<Inventory> findByProductIdForUpdate(Long productId);

    /** 재고 행이 없으면 수량 0으로 만든다. 동시에 호출돼도 한 건만 생긴다. */
    void createIfAbsent(Long productId);
}
```

```java
package com.logistics.inventory.domain.product.port;

public interface ProductStore {

    Optional<Product> findByProductCode(ProductCode productCode);

    /** 같은 상품 코드가 이미 있으면 기존 상품을, 없으면 저장한 상품을 반환한다. 동시 호출에도 한 건만 생긴다. */
    Product saveIfAbsent(Product product);
}
```
- 시그니처는 도메인 언어로. `JpaRepository`, `Page`, `Specification` 같은 Spring Data 타입을 노출하지 않는다.

---

## 4. 서비스, Command/Result

```java
public record InboundCommand(ProductCode productCode, String name, Quantity quantity, String idempotencyKey) {}
public record OutboundCommand(ProductCode productCode, Quantity quantity, String idempotencyKey) {}

public record StockChangeResult(Long transactionId, ProductCode productCode, String name, StockChangeType type,
                                long quantity, long beforeQuantity, long afterQuantity,
                                OffsetDateTime createdAt) {
    public static StockChangeResult of(final Product product, final StockTransaction transaction) { ... }
}
```

```java
package com.logistics.inventory.domain.inventory;

@Service
@RequiredArgsConstructor
public class InventoryService {

    private final ProductStore productStore;
    private final InventoryStore inventoryStore;
    private final StockTransactionStore stockTransactionStore;

    @Transactional
    public StockChangeResult inbound(final InboundCommand command) {
        // 상품 (Product)
        final Product product = productStore.saveIfAbsent(Product.register(command.productCode(), command.name()));
        // 재고 (Inventory) — 행 보장 후 잠금, 증가
        inventoryStore.createIfAbsent(product.getId());
        final Inventory inventory = getForUpdate(product.getId());
        final StockChange change = inventory.increase(command.quantity());   // 규칙은 엔티티가
        // 원장 (StockTransaction)
        final StockTransaction transaction =
                stockTransactionStore.save(StockTransaction.record(change, command.idempotencyKey()));
        return StockChangeResult.of(product, transaction);
    }

    @Transactional
    public StockChangeResult outbound(final OutboundCommand command) {
        // 상품 (Product)
        final Product product = productStore.findByProductCode(command.productCode())
                .orElseThrow(() -> BusinessException.of(PRODUCT_NOT_FOUND, "productCode=" + command.productCode().value()));
        // 재고 (Inventory) — 행 잠금 후 감소
        final Inventory inventory = getForUpdate(product.getId());
        final StockChange change = inventory.decrease(command.quantity());
        // 원장 (StockTransaction)
        final StockTransaction transaction =
                stockTransactionStore.save(StockTransaction.record(change, command.idempotencyKey()));
        return StockChangeResult.of(product, transaction);
    }

    private Inventory getForUpdate(final Long productId) {
        return inventoryStore.findByProductIdForUpdate(productId)
                // 상품이 있는데 재고 행이 없으면 데이터 손상 → 비즈니스 예외가 아니라 500
                .orElseThrow(() -> new IllegalStateException("재고 행이 없습니다. productId=" + productId));
    }
}
```
- 서비스에는 `if (재고 < 요청)` 같은 규칙 판단이 없다. 조회 → 엔티티 행위 → 기록 흐름만 있다.
- 재고 변경과 원장 기록이 한 트랜잭션. 변경된 `Inventory`는 변경 감지로 저장된다.

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
