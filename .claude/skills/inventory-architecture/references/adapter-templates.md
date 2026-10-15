# 코드 템플릿 — db, app (어댑터)

---

## 1. db — Spring Data Repository, Adapter, JPA 설정

```java
package com.logistics.inventory.db.inventory;

interface InventoryJpaRepository extends JpaRepository<Inventory, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))   // ⚠ PG에서 무시될 수 있음 — 아래 참고
    @Query("select i from Inventory i where i.productId = :productId")
    Optional<Inventory> findByProductIdForUpdate(@Param("productId") Long productId);

    @Modifying
    @Query(value = """
            INSERT INTO inventory (product_id, quantity, created_at, updated_at)
            VALUES (:productId, 0, now(), now())
            ON CONFLICT (product_id) DO NOTHING
            """, nativeQuery = true)
    void insertIfAbsent(@Param("productId") Long productId);
}
```

```java
@Repository
@RequiredArgsConstructor
class InventoryStoreAdapter implements InventoryStore {

    private final InventoryJpaRepository inventoryJpaRepository;

    @Override
    public Optional<Inventory> findByProductId(final Long productId) {
        return inventoryJpaRepository.findById(productId);
    }

    @Override
    public Optional<Inventory> findByProductIdForUpdate(final Long productId) {
        return inventoryJpaRepository.findByProductIdForUpdate(productId);
    }

    @Override
    public void createIfAbsent(final Long productId) {
        inventoryJpaRepository.insertIfAbsent(productId);
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
- 락 대기 시간 상한을 둬서 경합 시 무한 대기하지 않게 한다. PostgreSQL에는 `FOR UPDATE WAIT n` 문법이 없어 Hibernate가 `lock.timeout` 힌트를 무시할 수 있다 → 적용 여부를 테스트로 확인하고, 안 되면 `SET LOCAL lock_timeout` 또는 커넥션 옵션(`options=-c lock_timeout=3000`)으로 건다.

---

## 2. app — Controller, Request/Response

```java
package com.logistics.inventory.api.inventory;

@RestController
@RequestMapping("/v1/inventory")   // /api는 server.servlet.context-path가 붙인다
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @PostMapping("/inbound")
    public ApiResponse<StockChangeResponse> inbound(
            @RequestHeader(value = "Idempotency-Key", required = false) final String idempotencyKey,
            @Valid @RequestBody final InboundRequest request
    ) {
        return ApiResponse.ok(StockChangeResponse.from(inventoryService.inbound(request.toCommand(idempotencyKey))));   // 성공 응답도 공통 응답으로 감싼다
    }
}
```

```java
public record InboundRequest(
        @NotBlank String productCode,
        @NotBlank String name,
        @NotNull @Positive Long quantity
) {

    public InboundCommand toCommand(final String idempotencyKey) {
        return new InboundCommand(
                new ProductCode(productCode), 
                name, 
                new Quantity(quantity), 
                idempotencyKey
        );
    }
}

public record StockChangeResponse(
        Long transactionId,
        String productCode,
        String name,
        String type,
        long quantity,
        long beforeQuantity,
        long afterQuantity,
        OffsetDateTime createdAt
) {
    public static StockChangeResponse from(final StockChangeResult result) { ... }
}
```

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
