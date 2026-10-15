# 기능 명세 — 공통 에러 응답 형식 (#20)

### 요구사항 해석
- 어떤 이유로 실패하든 에러 응답은 한 가지 형식이다. 클라이언트는 응답 본문의 `code` 하나만 보고 분기한다.
- 지금은 `BusinessException`만 정해진 형식으로 나간다. 그 밖의 오류는 Spring 기본 응답이 나가서 형식이 제각각이다.
  - 없는 경로, 지원하지 않는 메서드, 깨진 JSON, `@Valid` 검증 실패: Spring 기본 `ProblemDetail`이 나가고 `code`가 없다
  - 예상하지 못한 예외: Spring Boot 기본 에러 응답(`timestamp`, `status`, `error`, `path`)이 나가서 형식 자체가 다르다
- 이 이슈에서 성공과 에러 응답을 모두 공통 응답 `ApiResponse` 하나로 맞추고, 검증 실패에는 틀린 항목과 이유를, 500에는 상태 문구만 담는다.
- 성공 응답도 감싸므로 #7 재고 조회 API의 성공 응답 모양이 바뀐다 (`{"productCode": ...}` → `{"status": 200, "code": "SUCCESS", ..., "data": {"productCode": ...}}`).
- 아직 `@Valid`를 쓰는 요청 DTO가 없다. 검증 실패, 메서드 불일치 같은 경우는 테스트에만 있는 컨트롤러로 확인한다. 입고, 출고 API가 생기면 그대로 이 형식을 쓴다.

### 설계

**응답 형식** — 성공과 에러 모두 `ApiResponse`. `Content-Type: application/json`

| 필드 | 값 | 비고 |
|---|---|---|
| `status` | HTTP 상태 코드 | HTTP 응답 상태와 같은 값에서 만든다 |
| `code` | 성공은 `SUCCESS`, 에러는 `PRODUCT_NOT_FOUND`, `BAD_REQUEST` 등 | 클라이언트가 분기하는 값. 도메인 에러는 `ErrorCode` 상수 이름, 그 밖은 HTTP 상태 이름 |
| `message` | 사람이 읽는 메시지 | 성공은 `success`, 비즈니스 예외는 `ErrorCode` 기본 문구 + 식별자(한국어), 그 밖은 HTTP 상태 문구(`Not Found`) |
| `data` | 성공 결과 | 에러면 `null`, 검증 실패면 틀린 항목 목록 `[{"field": ..., "reason": ...}]` |

응답에 넣지 않는 것: 스택트레이스, 예외 클래스 이름, 원본 예외 메시지(SQL, 제약 이름, JSON 파서 메시지 포함). 이런 정보는 로그에만 남긴다.

검증 실패 항목에 클라이언트가 보낸 값(`rejectedValue`)은 넣지 않는다. 클라이언트가 이미 아는 값이고, 비밀번호 같은 값이 들어오면 응답과 로그로 되돌아가기 때문이다. `field`와 `reason`만으로 어떤 값이 왜 틀렸는지 알 수 있다.

**왜 공통 응답으로 감싸나** — 비교한 대안과 같은 404(없는 상품) 예시

1. RFC 9457 `ProblemDetail` (Spring 기본 지원 표준). 성공은 그대로, 에러만 표준 형식
   ```json
   // 200
   { "productCode": "TS-BASIC-BLK-M", "name": "...", "quantity": 5 }
   // 404
   { "type": "about:blank", "title": "Not Found", "status": 404, "detail": "상품을 찾을 수 없습니다. ...",
     "instance": "/api/v1/inventory/NOT-EXIST", "code": "PRODUCT_NOT_FOUND" }
   ```
   성공과 에러의 모양이 달라서, 클라이언트는 HTTP 상태를 먼저 보고 어떤 모양으로 읽을지 정해야 한다.
2. 성공은 그대로, 에러만 `{"error": {"code", "message"}}`로 감싸기. 1과 같은 이유로 클라이언트 파싱이 둘로 나뉜다.
3. **모든 응답을 `{status, code, message, data}`로 감싸기 (선택)**
   ```json
   // 200
   { "status": 200, "code": "SUCCESS", "message": "success", "data": { "productCode": "TS-BASIC-BLK-M", ... } }
   // 404
   { "status": 404, "code": "PRODUCT_NOT_FOUND", "message": "상품을 찾을 수 없습니다. ...", "data": null }
   ```
   클라이언트는 어떤 응답이든 같은 코드로 읽고 `code`로 분기한다.

3을 고른 이유: 클라이언트가 응답을 읽는 코드를 하나로 둘 수 있다. 대신 치르는 비용은 다음과 같다.
- HTTP 상태와 본문 `status`가 같은 값을 두 번 담는다. 핸들러에서 둘을 같은 변수로 만들어 어긋나지 않게 한다.
- Controller마다 `ApiResponse.ok(...)`로 감싸야 한다.
- #7 조회 API의 성공 응답 모양이 바뀐다. 아직 이 API를 쓰는 클라이언트가 없어 지금 바꾼다.

**응답 예시**

성공 (200)
```json
{ "status": 200, "code": "SUCCESS", "message": "success",
  "data": { "productCode": "TS-BASIC-BLK-M", "name": "베이직 반팔 티셔츠 검정 M", "quantity": 5 } }
```

없는 상품 (404)
```json
{ "status": 404, "code": "PRODUCT_NOT_FOUND", "message": "상품을 찾을 수 없습니다. productCode=NOT-EXIST", "data": null }
```

요청 검증 실패 (400)
```json
{
  "status": 400,
  "code": "BAD_REQUEST",
  "message": "Bad Request",
  "data": [
    { "field": "quantity", "reason": "0보다 커야 합니다" },
    { "field": "productCode", "reason": "공백일 수 없습니다" }
  ]
}
```

예상하지 못한 에러 (500)
```json
{ "status": 500, "code": "INTERNAL_SERVER_ERROR", "message": "Internal Server Error", "data": null }
```

**에러 코드**

도메인 에러는 지금 있는 `ErrorCode`(`INVALID_PRODUCT_CODE`, `INVALID_QUANTITY`, `PRODUCT_NOT_FOUND`)를 그대로 쓴다. 입고, 출고의 에러 코드(재고 부족 등)는 그 이슈에서 `ErrorCode`에 한 줄씩 추가하고, `app`은 고치지 않는다. 상태는 `ErrorKind`로 정해지기 때문이다.

도메인 밖 에러는 enum을 따로 두지 않고 HTTP 상태를 그대로 쓴다. `code`는 상태 이름(`HttpStatus.name()`), `message`는 상태 문구(`getReasonPhrase()`)다. 도메인은 HTTP를 모르므로 이 에러들을 `ErrorCode`에 넣지 않는다.

| code | 상태 | 언제 | message |
|---|---|---|---|
| `BAD_REQUEST` | 400 | `@Valid` 검증 실패, 깨진 JSON, 타입이 틀린 파라미터, 필수 파라미터·헤더 누락 | Bad Request |
| `NOT_FOUND` | 404 | 없는 경로 | Not Found |
| `METHOD_NOT_ALLOWED` | 405 (+ `Allow` 헤더) | 경로는 있지만 메서드가 다름 | Method Not Allowed |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | 지원하지 않는 `Content-Type` | Unsupported Media Type |
| `SERVICE_UNAVAILABLE` | 503 (+ `Retry-After: 1`) | 재고 행 잠금 대기 초과 (`PessimisticLockingFailureException`) | Service Unavailable |
| `INTERNAL_SERVER_ERROR` | 500 | 그 밖의 모든 예외 | Internal Server Error |

**왜 도메인 밖 에러용 enum을 두지 않나** (대안: `app`에 `ApiErrorCode` enum을 두고 `LOCK_TIMEOUT`, `INTERNAL_ERROR` 같은 이름과 한국어 문구를 정한다)
- 도메인 밖 에러는 요청 형식이나 서버 상태 문제라 HTTP 상태 이름만으로 뜻이 충분하다. enum을 두면 상태마다 이름과 문구를 한 번 더 정의하고, 상태와 enum 값을 맞추는 매핑도 관리해야 한다.
- 대신 치르는 비용
  - 이 에러들의 `message`는 영어 상태 문구라, 한국어인 도메인 에러 문구와 언어가 섞인다. 화면에 보여 줄 문구는 클라이언트가 `code`로 정한다.
  - 잠금 대기 초과가 `SERVICE_UNAVAILABLE`이 아니라 `SERVICE_UNAVAILABLE`로 나간다. 클라이언트가 할 일(`Retry-After` 뒤 재시도)은 같다.
- 도메인 `ErrorCode` 상수는 `PRODUCT_NOT_FOUND`처럼 도메인 명사를 넣어 HTTP 상태 이름(`NOT_FOUND`)과 겹치지 않게 한다.

- 없는 상품(`PRODUCT_NOT_FOUND`)과 없는 경로(`NOT_FOUND`)는 둘 다 404지만 `code`가 달라서 클라이언트가 구분할 수 있다. 오타로 경로를 잘못 부른 것과 상품이 없는 것은 대응이 다르다.
- 표에 없는 상태(406 등)도 같은 규칙으로 상태 이름이 code가 된다.
- 잠금 대기 초과는 409가 아니라 503이다. 요청 자체는 정상이고 잠깐 몰린 것이라 다시 보내면 성공할 수 있다. 409는 "지금 상태와 충돌해서 다시 보내도 같다"로 읽힌다. `Retry-After`로 다시 보내도 된다는 것을 알린다. 실제 잠금 대기 시간(`lock_timeout`) 설정은 재고를 잠그는 입고, 출고 이슈에서 정하고, 여기서는 예외를 응답으로 바꾸는 것만 한다. 이 매핑을 이 이슈에 미리 두면 입고, 출고 이슈가 핸들러를 고치지 않아 두 이슈를 함께 진행해도 겹치지 않는다.
- `DataIntegrityViolationException`(DB 제약 위반)은 따로 매핑하지 않아 500이 된다. 도메인 검증을 거친 뒤에 제약이 깨졌다면 버그이므로 ERROR 로그로 드러나야 한다.

**클래스** (`app`, `com.logistics.inventory.api.common`)

| 클래스 | 역할 |
|---|---|
| `ApiResponse` (record, 신규) | 공통 응답. `ok(data)`는 Controller가 성공 응답에, `error(...)`는 핸들러가 에러 응답에 쓴다. `error(HttpStatus, data)`는 상태 이름과 문구를 code와 message로 채운다 |
| `GlobalExceptionHandler` (변경) | `ResponseEntityExceptionHandler`를 상속. 아래 표대로 처리 |
| `ErrorStatusMapper` (그대로) | `ErrorKind` → HTTP 상태 |

| 예외 | 처리 | 로그 |
|---|---|---|
| `BusinessException` | `ErrorStatusMapper`로 상태, `ErrorCode` 이름이 code | WARN |
| Spring MVC 표준 예외 (없는 경로, 메서드, 미디어 타입, JSON 파싱, 타입 불일치, 필수 값 누락 등) | 상위 클래스가 상태를 정하고, `handleExceptionInternal` 오버라이드에서 그 상태의 이름과 문구로 code와 message를 채운다. 405의 `Allow` 같은 헤더는 상위 클래스가 넣은 그대로 둔다 | WARN |
| `MethodArgumentNotValidException` (`@Valid @RequestBody`) | 위와 같고, `handleExceptionInternal`에서 `data`에 틀린 항목 (필드 이름, 이유)을 담는다 | WARN |
| `HandlerMethodValidationException` (`@RequestParam @Max` 등) | 위와 같고, `data`에 틀린 항목 (파라미터 이름, 이유)을 담는다 | WARN |
| `PessimisticLockingFailureException` | 503 `SERVICE_UNAVAILABLE` + `Retry-After: 1` | WARN |
| `Exception` | 500 `INTERNAL_SERVER_ERROR`, 상태 문구 | ERROR + 스택 |

- 도메인 에러 문구는 `ErrorCode`에만 둔다. 핸들러에 문구 문자열을 쓰지 않는다.
- `PessimisticLockingFailureException`은 `spring-tx`(`org.springframework.dao`)에 있다. `app`이 컴파일 시점에 보지 못하므로 `app/build.gradle.kts`에 `implementation("org.springframework:spring-tx")`를 추가한다. DB 구현이 아니라 Spring 공통 예외라 "`app`은 `db`를 모른다" 규칙과 부딪히지 않는다 (아키텍처 규칙의 `app` 허용 의존성에 이미 있다).
- `@WebMvcTest`를 쓰려고 `app`에 `testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")`를 추가한다. Spring Boot 4부터 MVC 테스트 슬라이스가 별도 모듈이다.

**왜 `ResponseEntityExceptionHandler`를 상속하나** (대안: 필요한 예외마다 `@ExceptionHandler`를 직접 만든다)
- Spring MVC 표준 예외는 15종이 넘는다. 직접 만들면 하나를 빠뜨렸을 때 그 예외만 다른 형식으로 나간다. 예를 들어 깨진 JSON(`HttpMessageNotReadableException`)을 빠뜨리면 `Exception` 핸들러로 떨어져 500 `INTERNAL_SERVER_ERROR`가 된다. 클라이언트 잘못인데 서버 오류로 응답하고 ERROR 로그까지 남는다.
- 상속하면 상위 클래스가 모든 표준 예외의 상태와 헤더(`Allow` 등)를 정하고, 모든 응답이 `handleExceptionInternal` 한 곳을 지난다. 여기서 code와 문구를 일괄로 채우면 빠지는 예외가 없다. 검증 실패 두 가지도 같은 곳에서 `data`만 채워서, 응답을 만드는 곳이 하나로 남는다.

### 비즈니스 규칙
- R1. 모든 응답은 `application/json`이고 `status`, `code`, `message`, `data` 네 필드다. 본문 `status`는 HTTP 상태와 같다.
- R2. 비즈니스 예외는 `ErrorCode`의 종류(`ErrorKind`)로 상태가 정해지고, `code`는 `ErrorCode` 상수 이름이다. `ErrorKind`는 INVALID 400, NOT_FOUND 404, CONFLICT 409, UNPROCESSABLE 422다.
- R3. 요청 값 검증에 실패하면 400 `BAD_REQUEST`이고, `data`에 틀린 항목(`field`)과 이유(`reason`)가 들어간다.
- R4. 요청을 읽지 못하면(깨진 JSON, 타입이 틀린 파라미터) 400 `BAD_REQUEST`이고, `message`에 파서 메시지가 나오지 않는다.
- R5. 없는 경로는 404 `NOT_FOUND`, 다른 메서드는 405 `METHOD_NOT_ALLOWED`와 `Allow` 헤더, 지원하지 않는 `Content-Type`은 415 `UNSUPPORTED_MEDIA_TYPE`이다.
- R6. 재고 행 잠금 대기가 초과되면 503 `SERVICE_UNAVAILABLE`과 `Retry-After: 1`이다.
- R7. 예상하지 못한 예외는 500 `INTERNAL_SERVER_ERROR`이고, 응답에 원본 메시지, 예외 클래스 이름, 스택이 나오지 않는다.

### 예외 케이스
| 상황 | 결과 |
|---|---|
| 없는 상품 조회 | 404 `PRODUCT_NOT_FOUND` |
| 형식이 틀린 상품 코드 | 400 `INVALID_PRODUCT_CODE` |
| `@Valid` 바디 필드 위반 | 400 `BAD_REQUEST` + `data`에 틀린 항목 |
| `@RequestParam` 제약 위반 | 400 `BAD_REQUEST` + `data`에 틀린 항목 |
| 깨진 JSON, `page=abc` | 400 `BAD_REQUEST` |
| 없는 경로 / 다른 메서드 / `text/plain` 바디 | 404 `NOT_FOUND` / 405 `METHOD_NOT_ALLOWED` / 415 `UNSUPPORTED_MEDIA_TYPE` |
| 잠금 대기 초과 | 503 `SERVICE_UNAVAILABLE` |
| DB 제약 위반, 그 밖의 예외 | 500 `INTERNAL_SERVER_ERROR` |

### 테스트 케이스
| ID | 대상 규칙 | Given | When | Then | 종류 |
|---|---|---|---|---|---|
| TC-20-01 | R1, R2 | 상품 없음 | `GET /api/v1/inventory/NOT-EXIST` | 404, `application/json`, `status` = 404, `code` = `"PRODUCT_NOT_FOUND"`, `message`에 `NOT-EXIST`, `data` = null | 통합 (HTTP) |
| TC-20-02 | R2 | | `ErrorKind` 4종 매핑 | 400, 404, 409, 422 | 단위 |
| TC-20-03 | R1, R3 | `@NotBlank`, `@Positive` 필드가 있는 바디 | 두 필드 모두 위반 | 400, `BAD_REQUEST`, `data` 2건에 각 `field`와 `reason` | 슬라이스 (`@WebMvcTest`) |
| TC-20-04 | R1, R3 | `@RequestParam @Max(100) size` | `size=101` | 400, `BAD_REQUEST`, `data[0].field` = `size` | 슬라이스 |
| TC-20-05 | R1, R4 | JSON 바디를 받는 API | `{"name":` (깨진 JSON) | 400, `BAD_REQUEST`, `message` = `Bad Request` | 슬라이스 |
| TC-20-06 | R1, R4 | `int page` 파라미터 | `page=abc` | 400, `BAD_REQUEST` | 슬라이스 |
| TC-20-07 | R1, R5 | | 없는 경로 | 404, `NOT_FOUND` | 슬라이스 |
| TC-20-08 | R1, R5 | POST 전용 API | GET 요청 | 405, `METHOD_NOT_ALLOWED`, `Allow: POST` | 슬라이스 |
| TC-20-09 | R1, R5 | JSON 바디를 받는 API | `Content-Type: text/plain` | 415, `UNSUPPORTED_MEDIA_TYPE` | 슬라이스 |
| TC-20-10 | R1, R6 | `CannotAcquireLockException`을 던지는 API | 호출 | 503, `SERVICE_UNAVAILABLE`, `Retry-After: 1` | 슬라이스 |
| TC-20-11 | R1, R7 | `IllegalStateException("password=secret")`을 던지는 API | 호출 | 500, `INTERNAL_SERVER_ERROR`, 본문에 `secret`, `IllegalStateException` 없음 | 슬라이스 |

- TC-20-03 ~ 11은 `app/src/test`에만 있는 테스트용 컨트롤러로 `@WebMvcTest`에서 확인한다. 각 테스트에서 R1의 공통 필드를 함께 검증한다.
- `code`는 enum 상수가 아니라 문자열 리터럴로 검증한다. enum 이름을 바꾸면 외부 계약이 바뀐 것이므로 테스트가 깨져야 한다.
- TC-20-01은 실제 조회 API로 확인한다. 이슈 완료 조건 "없는 상품 조회(404)가 공통 형식으로 나온다"를 그대로 검증하는 테스트다.
- #7 조회 API의 성공 응답이 `data` 아래로 들어가므로 TC-7-08의 검증 경로를 `$.data.*`로 바꾼다.

### PR 계획
| PR | 브랜치 | 범위 | 포함 테스트 케이스 |
|---|---|---|---|
| 1 | `feat/20-error-response` | 명세서, `ApiResponse`, `GlobalExceptionHandler` 확장, 조회 API 응답 감싸기, 의존성 추가, 아키텍처 규칙 7장 갱신, 테스트 (`Closes #20`) | TC-20-01 ~ TC-20-11 |

### 결정 필요
- 없음

### 변경 이력
- 잠금 대기 초과(503) 매핑을 이 이슈에 넣기로 확정 (구현 중)
- 검증 실패 두 가지를 각각 오버라이드하지 않고 `handleExceptionInternal`에서 틀린 항목을 더하도록 변경. 응답을 만드는 곳을 하나로 두기 위해서 (구현 중)
- 응답 형식을 RFC 9457 `ProblemDetail`에서 성공, 에러 공통 응답 `ApiResponse`(`status`, `code`, `message`, `data`)로 변경. 클라이언트가 응답 모양 하나만 다루게 하기 위해서. `type`, `title`, `instance`, `timestamp`는 빼고, 검증 실패 항목은 `data`에 담는다 (구현 중)
- 도메인 밖 에러용 `ApiErrorCode` enum을 없애고 HTTP 상태 이름과 문구를 code, message로 쓰도록 변경. 상태 이름만으로 뜻이 충분하고 enum과 상태 매핑을 따로 관리하지 않기 위해서. `LOCK_TIMEOUT`은 `SERVICE_UNAVAILABLE`, `INTERNAL_ERROR`는 `INTERNAL_SERVER_ERROR`가 됨 (구현 중)
