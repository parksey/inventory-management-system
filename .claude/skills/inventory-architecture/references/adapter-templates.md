# 코드 템플릿 — db, app (어댑터)

---

## 1. db — Spring Data Repository, Adapter, JPA 설정

```java
package com.logistics.inventory.db.inventory;

interface InventoryJpaRepository extends JpaRepository<Inventory, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Inventory i where i.productId = :productId")
    Optional<Inventory> findByProductIdForUpdate(@Param("productId") Long productId);
}
```

```java
@Repository
@RequiredArgsConstructor
class InventoryStoreAdapter implements InventoryStore {

    private final InventoryJpaRepository inventoryJpaRepository;
    private final EntityManager entityManager;

    @Override
    public Optional<Inventory> findByProductIdForUpdate(final Long productId) {
        return inventoryJpaRepository.findByProductIdForUpdate(productId);
    }

    // id(productId)를 직접 넣는 엔티티라 JpaRepository.save는 merge로 동작한다 (SELECT가 한 번 더 나가고, 넘긴 객체가 영속 상태가 되지 않는다)
    @Override
    public void save(final Inventory inventory) {
        entityManager.persist(inventory);
    }
}
```

```java
package com.logistics.inventory.db.product;

interface ProductJpaRepository extends JpaRepository<Product, Long> {

    Optional<Product> findByProductCodeValue(String productCode);

    // 같은 코드가 이미 있으면(다른 트랜잭션이 넣는 중이면 커밋을 기다린 뒤) 오류 없이 넣지 않는다. 넣은 행 수(0 또는 1)를 돌려준다
    @Modifying
    @Query(value = """
            INSERT INTO product (product_code, name)
            VALUES (:productCode, :name)
            ON CONFLICT (product_code) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("productCode") String productCode, @Param("name") String name);
}

@Repository
@RequiredArgsConstructor
class ProductStoreAdapter implements ProductStore {

    private static final int INSERTED = 1;   // ON CONFLICT DO NOTHING이 실제로 넣었을 때의 행 수

    private final ProductJpaRepository productJpaRepository;

    @Override
    public boolean saveIfAbsent(final Product product) {
        return productJpaRepository.insertIfAbsent(product.getProductCode().value(), product.getName()) == INSERTED;
    }
}
```

```java
package com.logistics.inventory.db.config;

@Configuration
@EntityScan("com.logistics.inventory.domain")
@EnableJpaRepositories("com.logistics.inventory.db")
class JpaConfig {}
```
- Spring Data 인터페이스와 Adapter는 package-private — db 밖에서는 도메인 인터페이스로만 접근된다.
- `@Modifying` 쿼리는 `void`, `int`, `long`만 돌려줄 수 있다. 행 수를 도메인 의미(`boolean`)로 바꾸는 일은 어댑터가 한다.
- 잠금 대기 상한은 `inventory-db.yml`의 커넥션 초기화로 건다: `spring.datasource.hikari.connection-init-sql: SET lock_timeout = '3s'`. PostgreSQL에는 `FOR UPDATE WAIT n` 문법이 없어 `jakarta.persistence.lock.timeout` 힌트에 기대지 않고, 커넥션 설정은 행 잠금과 유니크 인덱스 대기 모두에 적용된다.

---

## 2. app — Controller, Request/Response

```java
package com.logistics.inventory.api.inventory;

// 유스케이스마다 컨트롤러를 둔다. 조회는 InventoryController, 출고는 OutboundController
@RestController
@RequestMapping("/v1/inventory")   // /api는 server.servlet.context-path가 붙인다
@RequiredArgsConstructor
public class InboundController {

    private final InventoryService inventoryService;

    @PostMapping("/{productCode}/inbound")
    public ApiResponse<Void> inbound(
            @PathVariable final String productCode,
            @RequestHeader(ApiHeaders.REQUESTER_ID) final String requesterId,   // 형식은 RequesterId가 검증
            @Valid @RequestBody final InboundRequest request
    ) {
        inventoryService.inbound(request.toCommand(productCode, requesterId));
        return ApiResponse.ok(null);   // 상태를 바꾸는 요청은 데이터를 돌려주지 않는다
    }
}
```

```java
public record InboundRequest(
    @NotNull @Positive @Max(1_000_000) Long quantity
) {

    public InboundCommand toCommand(final String productCode, final String requesterId) {
        return new InboundCommand(
            new ProductCode(productCode),
            new Quantity(quantity),
            new RequesterId(requesterId)
        );
    }
}
```
- 여러 API가 함께 쓰는 헤더 이름은 `app`의 `ApiHeaders`에 둔다 (`REQUESTER_ID = "X-User-Id"`). 테스트는 계약을 지키려고 헤더 이름을 문자열 리터럴로 쓴다.
- 조회 응답은 `XxxResponse.from(result)` record로 만든다 (`StockResponse`).

```java
@SpringBootApplication(scanBasePackages = "com.logistics.inventory")   // domain, db 빈까지 스캔
public class InventoryApplication {
    public static void main(final String[] args) {
        SpringApplication.run(InventoryApplication.class, args);
    }
}
```

---

## 3. app — 예외 핸들러

여기서는 모양만 둔다. 세부 매핑은 에러 응답 이슈의 명세서에서 정한다.

```java
package com.logistics.inventory.api.common;

final class ErrorStatusMapper {
    private ErrorStatusMapper() {}

    // ErrorKind 단위 매핑 — ErrorCode 상수를 추가해도 고치지 않는다. default가 없어서 ErrorKind 매핑 누락은 컴파일 에러
    static HttpStatus toStatus(final ErrorKind kind) {
        return switch (kind) {
            case INVALID -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case UNPROCESSABLE -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
    }
}

}
```

```java
// 성공과 에러가 같은 모양인 공통 응답. 클라이언트는 code로 분기한다
public record ApiResponse<T>(int status, String code, String message, T data) {
    public static <T> ApiResponse<T> ok(final T data) { return new ApiResponse<>(200, "SUCCESS", "success", data); }
    static <T> ApiResponse<T> error(final HttpStatusCode status, final String code, final String message, final T data) { ... }
    // 도메인 밖 에러: HTTP 상태 이름이 code, 상태 문구가 message (NOT_FOUND, "Not Found")
    static <T> ApiResponse<T> error(final HttpStatus status, final T data) { return error(status, status.name(), status.getReasonPhrase(), data); }
}
```

```java
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {   // 표준 MVC 예외 15여 종은 상위 클래스가 처리

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(final BusinessException e, final HttpServletRequest request) {
        final ErrorCode errorCode = e.getErrorCode();
        log.warn("비즈니스 예외: code={}, path={}, detail={}", errorCode.name(), request.getRequestURI(), e.getMessage());
        final HttpStatus status = ErrorStatusMapper.toStatus(errorCode.getKind());
        return ResponseEntity.status(status).body(ApiResponse.error(status, errorCode.name(), e.getMessage(), null));
    }

    // 일시적 경합 → 503 + Retry-After (spring-tx의 org.springframework.dao 예외 — db 구현은 모른다)
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleLockTimeout(final PessimisticLockingFailureException e, final HttpServletRequest request) {
        log.warn("락 대기 초과: path={}", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(ApiResponse.error(HttpStatus.SERVICE_UNAVAILABLE, null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(final Exception e, final HttpServletRequest request) {
        log.error("처리되지 않은 예외: path={}", request.getRequestURI(), e);   // 스택은 로그에만, 응답은 상태 문구
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.error(HttpStatus.INTERNAL_SERVER_ERROR, null));
    }

    // 표준 예외 응답 본문을 공통 응답으로 바꾼다 (405 Allow 등 상위 클래스 헤더는 유지).
    // 검증 실패(MethodArgumentNotValidException, HandlerMethodValidationException)면 data에 [{field, reason}]를 담는다
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(final Exception e, final Object body, final HttpHeaders headers,
                                                             final HttpStatusCode status, final WebRequest request) {
        return super.handleExceptionInternal(e, ApiResponse.error(HttpStatus.valueOf(status.value()), invalidFieldsOrNull(e)),
                headers, status, request);
    }
}
```
- 도메인 에러 문구는 `ErrorCode`에만 둔다. 도메인 밖 에러는 enum을 두지 않고 HTTP 상태 이름과 문구를 쓴다. 핸들러에 문구 문자열을 쓰지 않는다.
- 클라이언트가 보낸 값(`rejectedValue`)은 검증 실패 응답에 되돌려 주지 않는다.
- `DataIntegrityViolationException`은 매핑하지 않는다 → 500 (도메인 검증을 거친 뒤 제약 위반이면 버그).
