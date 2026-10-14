# 코드 템플릿 — db·api (어댑터)

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
class InventoryRepositoryAdapter implements InventoryRepository {

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
@EntityScan("com.logistics.inventory.domain")          // 엔티티는 domain 모듈에 있다
@EnableJpaRepositories("com.logistics.inventory.db")
class JpaConfig {}
```
- Spring Data 인터페이스와 Adapter는 package-private — db 밖에서는 도메인 인터페이스로만 접근된다.
- 락 대기 시간 상한을 둬서 경합 시 무한 대기하지 않게 한다. PostgreSQL에는 `FOR UPDATE WAIT n` 문법이 없어 Hibernate가 `lock.timeout` 힌트를 무시할 수 있다 → 적용 여부를 테스트로 확인하고, 안 되면 `SET LOCAL lock_timeout` 또는 커넥션 옵션(`options=-c lock_timeout=3000`)으로 건다 (DECISIONS B11 확정 후 이 템플릿 갱신).

---

## 2. api — Controller, Request/Response

```java
package com.logistics.inventory.api.inventory;

@RestController
@RequestMapping("/api/v1/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @PostMapping("/inbound")
    public StockChangeResponse inbound(
            @RequestHeader(value = "Idempotency-Key", required = false) final String idempotencyKey,
            @Valid @RequestBody final InboundRequest request
    ) {
        return StockChangeResponse.from(inventoryService.inbound(request.toCommand(idempotencyKey)));
    }
}
```

```java
public record InboundRequest(
        @NotBlank String sku,
        @NotBlank String name,
        @NotNull @Positive Long quantity
) {

    public InboundCommand toCommand(final String idempotencyKey) {
        return new InboundCommand(
                new Sku(sku), 
                name, 
                new Quantity(quantity), 
                idempotencyKey
        );
    }
}

public record StockChangeResponse(
        Long transactionId,
        String sku,
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

## 3. api — 예외 핸들러

세부(표준 예외별 매핑, 응답 필드, 로그 정책)는 `.pm-plans/tasks/05-error-response.md`. 여기서는 모양만.

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

// 도메인 밖 오류의 code. api 모듈이라 HTTP 상태를 직접 가진다 (ErrorKind로는 405·415·503·500을 표현할 수 없다)
@Getter
@RequiredArgsConstructor
public enum ApiErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 경로를 찾을 수 없습니다."),
    ...
    LOCK_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE, "요청이 몰려 재고를 변경하지 못했습니다. 잠시 후 다시 시도해 주세요."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String message;

    static ApiErrorCode from(final HttpStatusCode status) { ... }   // Spring 표준 예외용: 404·405·415·5xx, 나머지 INVALID_REQUEST
}
```

```java
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {   // 표준 MVC 예외 15여 종은 상위 클래스가 처리

    @ExceptionHandler(BusinessException.class)
    public ProblemDetail handleBusiness(final BusinessException e, final HttpServletRequest request) {
        final ErrorCode errorCode = e.getErrorCode();
        log.warn("비즈니스 예외: code={}, path={}, detail={}", errorCode.name(), request.getRequestURI(), e.getMessage());
        return ProblemDetailFactory.of(ErrorStatusMapper.toStatus(errorCode.getKind()), errorCode.name(), e.getMessage(), request.getRequestURI());
    }

    // 일시적 경합 → 503 + Retry-After (spring-tx의 org.springframework.dao 예외 — db 구현은 모른다)
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleLockTimeout(final PessimisticLockingFailureException e, final HttpServletRequest request) {
        log.warn("락 대기 초과: path={}", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(ProblemDetailFactory.of(ApiErrorCode.LOCK_TIMEOUT, request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(final Exception e, final HttpServletRequest request) {
        log.error("처리되지 않은 예외: path={}", request.getRequestURI(), e);   // 스택은 로그에만, 응답은 고정 문구
        return ProblemDetailFactory.of(ApiErrorCode.INTERNAL_ERROR, request.getRequestURI());
    }

    // 표준 예외 응답에 code·문구를 일괄 주입 (405 Allow 등 상위 클래스 헤더는 유지)
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(final Exception e, final Object body, final HttpHeaders headers,
                                                             final HttpStatusCode status, final WebRequest request) {
        final ApiErrorCode code = ApiErrorCode.from(status);
        return super.handleExceptionInternal(e, ProblemDetailFactory.of(status, code.name(), code.getMessage(), pathOf(request)),
                headers, status, request);
    }

    // handleMethodArgumentNotValid 오버라이드: INVALID_REQUEST + errors[{field, reason, rejectedValue}]
}
```
- `ProblemDetailFactory.of(...)`: 응답 공통 필드(`code`, `instance`, `timestamp`)를 채우는 유일한 곳. `title`은 두지 않는다 → HTTP 상태 문구(RFC 9457).
- 문구는 enum(`ErrorCode`, `ApiErrorCode`)에만 둔다. 핸들러에 문구 문자열을 쓰지 않는다.
- `DataIntegrityViolationException`은 매핑하지 않는다 → 500 (도메인 검증을 거친 뒤 제약 위반이면 버그).
