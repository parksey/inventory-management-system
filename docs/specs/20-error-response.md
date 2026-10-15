# 기능 명세 — 공통 에러 응답 형식 (#20)

### 요구사항 해석
- 어떤 이유로 실패하든 에러 응답은 한 가지 형식이다. 클라이언트는 응답 본문의 `code` 하나만 보고 분기한다.
- 지금은 `BusinessException`만 공통 형식으로 나간다. 그 밖의 오류는 Spring 기본 응답이 나가서 형식이 제각각이다.
  - 없는 경로, 지원하지 않는 메서드, 깨진 JSON, `@Valid` 검증 실패: Spring 기본 `ProblemDetail`이 나가고 `code`가 없다
  - 예상하지 못한 예외: Spring Boot 기본 에러 응답(`timestamp`, `status`, `error`, `path`)이 나가서 형식 자체가 다르다
- 이 이슈에서 위 오류를 모두 `ProblemDetail` + `code` 형식으로 맞추고, 검증 실패에는 틀린 항목과 이유를, 500에는 고정 문구만 담는다.
- 아직 `@Valid`를 쓰는 요청 DTO가 없다. 검증 실패, 메서드 불일치 같은 경우는 테스트에만 있는 컨트롤러로 확인한다. 입고, 출고 API가 생기면 그대로 이 형식을 쓴다.

### 설계

**응답 형식** — RFC 9457 `ProblemDetail` + 확장 필드. `Content-Type: application/problem+json`

| 필드 | 값 | 비고 |
|---|---|---|
| `type` | `about:blank` | 에러별 문서 URL이 없어서 RFC 기본값을 쓴다. 분기는 `code`로 한다 |
| `title` | HTTP 상태 문구 (`Not Found`) | `type`이 `about:blank`일 때 RFC가 정한 값. 따로 설정하지 않는다 |
| `status` | HTTP 상태 코드 | |
| `detail` | 사람이 읽는 메시지 (한국어) | 비즈니스 예외는 `ErrorCode` 기본 문구 + 식별자, 그 밖은 `ApiErrorCode` 고정 문구 |
| `instance` | 요청 경로 (`/api/v1/inventory/NOT-EXIST`) | 어느 요청의 에러인지 |
| `code` | `PRODUCT_NOT_FOUND`, `INVALID_REQUEST` 등 | 클라이언트가 분기하는 값. enum 상수 이름 |
| `timestamp` | ISO-8601 (`2026-10-16T10:21:33.512+09:00`) | 클라이언트가 문의할 때 로그와 맞춰 보는 용도 |
| `errors` | `[{"field": "quantity", "reason": "1 이상이어야 합니다"}]` | 검증 실패일 때만 |

응답에 넣지 않는 것: 스택트레이스, 예외 클래스 이름, 원본 예외 메시지(SQL, 제약 이름, JSON 파서 메시지 포함). 이런 정보는 로그에만 남긴다.

`errors`에 클라이언트가 보낸 값(`rejectedValue`)은 넣지 않는다. 클라이언트가 이미 아는 값이고, 비밀번호 같은 값이 들어오면 응답과 로그로 되돌아가기 때문이다. `field`와 `reason`만으로 어떤 값이 왜 틀렸는지 알 수 있다.

**응답 예시**

없는 상품 (404)
```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "상품을 찾을 수 없습니다. productCode=NOT-EXIST",
  "instance": "/api/v1/inventory/NOT-EXIST",
  "code": "PRODUCT_NOT_FOUND",
  "timestamp": "2026-10-16T10:21:33.512+09:00"
}
```

요청 검증 실패 (400)
```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "요청 값이 올바르지 않습니다.",
  "instance": "/api/v1/inventory/inbound",
  "code": "INVALID_REQUEST",
  "timestamp": "2026-10-16T10:21:33.512+09:00",
  "errors": [
    { "field": "quantity", "reason": "1 이상이어야 합니다" },
    { "field": "productCode", "reason": "공백일 수 없습니다" }
  ]
}
```

예상하지 못한 에러 (500)
```json
{
  "type": "about:blank",
  "title": "Internal Server Error",
  "status": 500,
  "detail": "일시적인 오류가 발생했습니다.",
  "instance": "/api/v1/inventory/TS-BASIC-BLK-M",
  "code": "INTERNAL_ERROR",
  "timestamp": "2026-10-16T10:21:33.512+09:00"
}
```

**에러 코드**

도메인 에러는 지금 있는 `ErrorCode`(`INVALID_PRODUCT_CODE`, `INVALID_QUANTITY`, `PRODUCT_NOT_FOUND`)를 그대로 쓴다. 입고, 출고의 에러 코드(재고 부족 등)는 그 이슈에서 `ErrorCode`에 한 줄씩 추가하고, `app`은 고치지 않는다. 상태는 `ErrorKind`로 정해지기 때문이다.

도메인 밖 에러는 `app`에 `ApiErrorCode` enum을 새로 둔다. 도메인은 HTTP를 모르지만 `app`은 웹 계층이라 상태를 직접 가진다. `ErrorKind` 4종으로는 405, 415, 503, 500을 나타낼 수 없어서 따로 둔다.

| code | 상태 | 언제 | detail |
|---|---|---|---|
| `INVALID_REQUEST` | 400 | `@Valid` 검증 실패, 깨진 JSON, 타입이 틀린 파라미터, 필수 파라미터·헤더 누락 | 요청 값이 올바르지 않습니다. |
| `RESOURCE_NOT_FOUND` | 404 | 없는 경로 | 요청한 경로를 찾을 수 없습니다. |
| `METHOD_NOT_ALLOWED` | 405 (+ `Allow` 헤더) | 경로는 있지만 메서드가 다름 | 지원하지 않는 HTTP 메서드입니다. |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | 지원하지 않는 `Content-Type` | 지원하지 않는 Content-Type입니다. |
| `LOCK_TIMEOUT` | 503 (+ `Retry-After: 1`) | 재고 행 잠금 대기 초과 (`PessimisticLockingFailureException`) | 요청이 몰려 재고를 변경하지 못했습니다. 잠시 후 다시 시도해 주세요. |
| `INTERNAL_ERROR` | 500 | 그 밖의 모든 예외 | 일시적인 오류가 발생했습니다. |

- 없는 상품(`PRODUCT_NOT_FOUND`)과 없는 경로(`RESOURCE_NOT_FOUND`)는 둘 다 404지만 `code`가 달라서 클라이언트가 구분할 수 있다. 오타로 경로를 잘못 부른 것과 상품이 없는 것은 대응이 다르다.
- 표에 없는 4xx(406 등)는 `INVALID_REQUEST`, 5xx는 `INTERNAL_ERROR`로 나간다.
- `LOCK_TIMEOUT`은 409가 아니라 503이다. 요청 자체는 정상이고 잠깐 몰린 것이라 다시 보내면 성공할 수 있다. 409는 "지금 상태와 충돌해서 다시 보내도 같다"로 읽힌다. `Retry-After`로 다시 보내도 된다는 것을 알린다. 실제 잠금 대기 시간 설정은 재고를 잠그는 입고, 출고 이슈에서 정하고, 여기서는 예외를 응답으로 바꾸는 것만 한다.
- `DataIntegrityViolationException`(DB 제약 위반)은 따로 매핑하지 않아 500이 된다. 도메인 검증을 거친 뒤에 제약이 깨졌다면 버그이므로 ERROR 로그로 드러나야 한다.

**클래스** (`app`, `com.logistics.inventory.api.common`)

| 클래스 | 역할 |
|---|---|
| `ApiErrorCode` (enum, 신규) | 도메인 밖 에러의 code, HTTP 상태, 문구. `from(HttpStatusCode)`로 Spring 표준 예외의 상태를 code로 바꾼다 |
| `ProblemDetailFactory` (신규) | `ProblemDetail`을 만들고 `code`, `timestamp`를 채우는 유일한 곳 |
| `GlobalExceptionHandler` (변경) | `ResponseEntityExceptionHandler`를 상속. 아래 표대로 처리 |
| `ErrorStatusMapper` (그대로) | `ErrorKind` → HTTP 상태 |

| 예외 | 처리 | 로그 |
|---|---|---|
| `BusinessException` | `ErrorStatusMapper`로 상태, `ErrorCode` 이름이 code | WARN |
| Spring MVC 표준 예외 (없는 경로, 메서드, 미디어 타입, JSON 파싱, 타입 불일치, 필수 값 누락 등) | 상위 클래스가 상태를 정하고, `handleExceptionInternal` 오버라이드에서 `ApiErrorCode.from(status)`로 code와 문구를 채운다. 405의 `Allow` 같은 헤더는 상위 클래스가 넣은 그대로 둔다 | WARN |
| `MethodArgumentNotValidException` (`@Valid @RequestBody`) | `INVALID_REQUEST` + `errors` (필드 이름, 이유) | WARN |
| `HandlerMethodValidationException` (`@RequestParam @Max` 등) | `INVALID_REQUEST` + `errors` (파라미터 이름, 이유) | WARN |
| `PessimisticLockingFailureException` | 503 `LOCK_TIMEOUT` + `Retry-After: 1` | WARN |
| `Exception` | 500 `INTERNAL_ERROR`, 고정 문구 | ERROR + 스택 |

- `instance`는 `ProblemDetail`에 값이 없으면 Spring MVC가 요청 경로로 채워 준다. 직접 넣지 않는다.
- 문구는 enum(`ErrorCode`, `ApiErrorCode`)에만 둔다. 핸들러에 문자열을 쓰지 않는다.
- `PessimisticLockingFailureException`은 `spring-tx`(`org.springframework.dao`)에 있다. `app`이 컴파일 시점에 보지 못하므로 `app/build.gradle.kts`에 `implementation("org.springframework:spring-tx")`를 추가한다. DB 구현이 아니라 Spring 공통 예외라 "`app`은 `db`를 모른다" 규칙과 부딪히지 않는다 (아키텍처 규칙의 `app` 허용 의존성에 이미 있다).
- `@WebMvcTest`를 쓰려고 `app`에 `testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")`를 추가한다. Spring Boot 4부터 MVC 테스트 슬라이스가 별도 모듈이다.

**왜 `ResponseEntityExceptionHandler`를 상속하나** (대안: 필요한 예외마다 `@ExceptionHandler`를 직접 만든다)
- Spring MVC 표준 예외는 15종이 넘는다. 직접 만들면 하나를 빠뜨렸을 때 그 예외만 Spring 기본 형식(`code` 없음)으로 나가고, 테스트하지 않은 예외라 알아채기 어렵다.
- 상속하면 상위 클래스가 모든 표준 예외의 상태와 헤더(`Allow` 등)를 정하고, 모든 응답이 `handleExceptionInternal` 한 곳을 지난다. 여기서 code와 문구를 일괄로 채우면 빠지는 예외가 없다. 형식이 다른 검증 실패 두 가지만 따로 오버라이드한다.

### 비즈니스 규칙
- R1. 모든 에러 응답은 `application/problem+json`이고 `status`, `title`, `detail`, `instance`, `code`, `timestamp`가 있다.
- R2. 비즈니스 예외는 `ErrorCode`의 종류(`ErrorKind`)로 상태가 정해지고, `code`는 `ErrorCode` 상수 이름이다. `ErrorKind`는 INVALID 400, NOT_FOUND 404, CONFLICT 409, UNPROCESSABLE 422다.
- R3. 요청 값 검증에 실패하면 400 `INVALID_REQUEST`이고, `errors`에 틀린 항목(`field`)과 이유(`reason`)가 들어간다.
- R4. 요청을 읽지 못하면(깨진 JSON, 타입이 틀린 파라미터) 400 `INVALID_REQUEST`이고, `detail`에 파서 메시지가 나오지 않는다.
- R5. 없는 경로는 404 `RESOURCE_NOT_FOUND`, 다른 메서드는 405 `METHOD_NOT_ALLOWED`와 `Allow` 헤더, 지원하지 않는 `Content-Type`은 415 `UNSUPPORTED_MEDIA_TYPE`이다.
- R6. 재고 행 잠금 대기가 초과되면 503 `LOCK_TIMEOUT`과 `Retry-After: 1`이다.
- R7. 예상하지 못한 예외는 500 `INTERNAL_ERROR`이고, 응답에 원본 메시지, 예외 클래스 이름, 스택이 나오지 않는다.

### 예외 케이스
| 상황 | 결과 |
|---|---|
| 없는 상품 조회 | 404 `PRODUCT_NOT_FOUND` (공통 형식) |
| 형식이 틀린 상품 코드 | 400 `INVALID_PRODUCT_CODE` (공통 형식) |
| `@Valid` 바디 필드 위반 | 400 `INVALID_REQUEST` + `errors` |
| `@RequestParam` 제약 위반 | 400 `INVALID_REQUEST` + `errors` |
| 깨진 JSON, `page=abc` | 400 `INVALID_REQUEST` |
| 없는 경로 / 다른 메서드 / `text/plain` 바디 | 404 `RESOURCE_NOT_FOUND` / 405 `METHOD_NOT_ALLOWED` / 415 `UNSUPPORTED_MEDIA_TYPE` |
| 잠금 대기 초과 | 503 `LOCK_TIMEOUT` |
| DB 제약 위반, 그 밖의 예외 | 500 `INTERNAL_ERROR` |

### 테스트 케이스
| ID | 대상 규칙 | Given | When | Then | 종류 |
|---|---|---|---|---|---|
| TC-20-01 | R1, R2 | 상품 없음 | `GET /api/v1/inventory/NOT-EXIST` | 404, `application/problem+json`, `code` = `"PRODUCT_NOT_FOUND"`, `instance` = 요청 경로, `timestamp`, `detail`에 `NOT-EXIST` | 통합 (HTTP) |
| TC-20-02 | R2 | | `ErrorKind` 4종 매핑 | 400, 404, 409, 422 | 단위 |
| TC-20-03 | R1, R3 | `@NotBlank`, `@Positive` 필드가 있는 바디 | 두 필드 모두 위반 | 400, `INVALID_REQUEST`, `errors` 2건에 각 `field`와 `reason` | 슬라이스 (`@WebMvcTest`) |
| TC-20-04 | R1, R3 | `@RequestParam @Max(100) size` | `size=101` | 400, `INVALID_REQUEST`, `errors[0].field` = `size` | 슬라이스 |
| TC-20-05 | R1, R4 | JSON 바디를 받는 API | `{"name":` (깨진 JSON) | 400, `INVALID_REQUEST`, `detail` = 고정 문구 | 슬라이스 |
| TC-20-06 | R1, R4 | `int page` 파라미터 | `page=abc` | 400, `INVALID_REQUEST` | 슬라이스 |
| TC-20-07 | R1, R5 | | 없는 경로 | 404, `RESOURCE_NOT_FOUND` | 슬라이스 |
| TC-20-08 | R1, R5 | POST 전용 API | GET 요청 | 405, `METHOD_NOT_ALLOWED`, `Allow: POST` | 슬라이스 |
| TC-20-09 | R1, R5 | JSON 바디를 받는 API | `Content-Type: text/plain` | 415, `UNSUPPORTED_MEDIA_TYPE` | 슬라이스 |
| TC-20-10 | R1, R6 | `CannotAcquireLockException`을 던지는 API | 호출 | 503, `LOCK_TIMEOUT`, `Retry-After: 1` | 슬라이스 |
| TC-20-11 | R1, R7 | `RuntimeException("password=secret")`을 던지는 API | 호출 | 500, `INTERNAL_ERROR`, 본문에 `secret`, `RuntimeException` 없음 | 슬라이스 |

- TC-20-03 ~ 11은 `app/src/test`에만 있는 테스트용 컨트롤러로 `@WebMvcTest`에서 확인한다. 각 테스트에서 R1의 공통 필드를 함께 검증한다.
- `code`는 enum 상수가 아니라 문자열 리터럴로 검증한다. enum 이름을 바꾸면 외부 계약이 바뀐 것이므로 테스트가 깨져야 한다.
- TC-20-01은 실제 조회 API로 확인한다. 이슈 완료 조건 "없는 상품 조회(404)가 공통 형식으로 나온다"를 그대로 검증하는 테스트다.

### PR 계획
| PR | 브랜치 | 범위 | 포함 테스트 케이스 |
|---|---|---|---|
| 1 | `feat/20-error-response` | 명세서, `ApiErrorCode`, `ProblemDetailFactory`, `GlobalExceptionHandler` 확장, 의존성 추가, 테스트 (`Closes #20`) | TC-20-01 ~ TC-20-11 |

### 결정 필요
- `LOCK_TIMEOUT`(503) 매핑을 이 이슈에 넣을지. 넣으면 입고, 출고 이슈가 핸들러를 고치지 않고 잠금만 걸면 되고, 두 이슈를 함께 진행해도 핸들러에서 겹치지 않는다. 대신 아직 잠금을 거는 코드가 없는데 매핑이 먼저 생긴다. 추천: 넣는다.

### 변경 이력
