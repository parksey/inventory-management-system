# 기능 명세 — 멀티모듈 프로젝트 초기 세팅 (#1)

### 요구사항 해석
- 기능 코드 없이 앱이 기동하고 PostgreSQL에 연결되는 골격을 만든다.
- 모듈 간 의존 방향(api → domain ← db)은 문서가 아니라 Gradle 의존성 설정으로 강제한다. 규칙을 어긴 import는 컴파일 에러가 나야 한다.
- 프로파일은 local과 prod 두 개다. 프로파일을 지정하지 않으면 local이고, 운영은 `prod`를 명시해야만 운영 설정이 된다.
- 로그는 기반만 둔다: local SQL 로그(p6spy)와, 요청마다 traceId를 MDC에 넣어 추가. 여러 Pod에 흩어진 로그를 요청 단위로 묶기 위해서다.
- 범위 밖: 스키마와 엔티티, 에러 응답, 접근 로그, 운영 JSON 로그, 메트릭, ArchUnit 아키텍처 테스트, 로컬 개발 가이드 문서, CI, 앱 컨테이너화

### 설계

**기술 스택과 버전**

| 항목 | 버전 | 비고 |
|---|---|---|
| Java | 21 (Gradle toolchain) | |
| Gradle | 9.8.1 (wrapper) | Kotlin DSL |
| Spring Boot | 4.1.1 | 착수 시점 최신 GA |
| PostgreSQL | 16 (`postgres:16-alpine`) | 로컬 compose와 Testcontainers 공통 |
| Testcontainers | 2.0.5 | Boot BOM 관리 |
| p6spy-spring-boot-starter | 2.0.1 | Boot 4 지원 라인 |
| springdoc-openapi | 3.1.1 | 의존성만 추가, 설정은 첫 API 이슈에서 |

**모듈과 의존 방향**

실행 모듈은 `app/`, 실행 보조 모듈은 `support/` 아래에 두고, 도메인 모듈은 `{도메인}-domain`과 `{도메인}-db` 쌍으로 루트에 둔다. `app`, `support`는 코드 없는 컨테이너 프로젝트다.

```
 api (bootJar) ──implementation──► inventory-domain ◄──compileOnly───── inventory-db
   ├─ runtimeOnly ──► inventory-db                                          │
   ├─ runtimeOnly ──► support:flyway                                        └─ testRuntimeOnly ──► support:flyway
   └─ runtimeOnly ──► support:p6spy
```

| 모듈 (Gradle 경로) | 이번에 들어가는 것 | 의존성 |
|---|---|---|
| `app/api` (`:app:api`) | `InventoryApplication`, `common/TraceIdFilter`, `application.yml`, `application-local.yml`, `application-prod.yml`, 기동 테스트 | domain(implementation), db, flyway, p6spy(runtimeOnly), webmvc, validation, actuator, springdoc |
| `inventory-domain` | 패키지 디렉토리와 `.gitkeep`만 | jakarta.persistence-api compileOnly (spring-context, spring-tx는 첫 서비스 이슈에서) |
| `inventory-db` | `JpaConfig` (`@EntityScan` domain, `@EnableJpaRepositories` db), `inventory-db.yml`(DataSource, JPA), `inventory-db-prod.yml`(운영 접속 정보, 기본값 없음) | domain(compileOnly, 실행 시점은 api가 넣는다), spring-boot-starter-data-jpa, postgresql(runtimeOnly) |
| `support/flyway` (`:support:flyway`) | `db/migration/.gitkeep` (main에 Java 코드 없음), Flyway 적용 테스트 | spring-boot-starter-flyway, flyway-database-postgresql |
| `support/p6spy` (`:support:p6spy`) | `PrettySqlFormatter`(SQL 정렬), 포매터를 지정하는 `P6SpyFormatterAutoConfiguration`과 `AutoConfiguration.imports` | p6spy-spring-boot-starter, spring-boot-autoconfigure, hibernate-core(둘 다 compileOnly) |

- api는 db와 p6spy를 runtimeOnly로만 가지므로 컴파일 클래스패스에 db 클래스, p6spy 클래스, Spring Data가 없다.
- domain에는 Spring Data, 웹, DB 드라이버가 없다.
- 실행 jar는 api의 bootJar 하나다. plain jar는 끈다.
- 루트 `build.gradle.kts`가 코드가 있는 모듈에만(컨테이너 프로젝트 제외) toolchain, Boot BOM, Lombok, 테스트 공통 설정, `-parameters` 컴파일 옵션을 적용한다.
- 포매터는 AutoConfiguration으로 등록해 모듈을 의존성에 넣기만 하면 적용된다 (component scan에 기대지 않는다). p6spy는 첫 SQL 로깅 때 포매터 인스턴스를 캐시하고 스타터는 초기화 때 기본 포매터를 지정하므로, 빈 생성 전에 실행되는 `BeanFactoryPostProcessor`에서 시스템 프로퍼티 `p6spy.config.logMessageFormat`을 지정한다. 스타터는 이미 정의된 값을 덮어쓰지 않는다.

**프로파일별 설정**

프로파일을 지정하지 않으면 local(`spring.profiles.default: local`), 운영은 `SPRING_PROFILES_ACTIVE=prod`로 명시한다. DataSource와 JPA 설정은 db 모듈의 `inventory-db.yml`에 두고 api `application.yml`이 `spring.config.import`로 가져간다 (프로파일 변형 `inventory-db-prod.yml`도 함께 로드된다).

| 항목 | local (기본) | prod | test |
|---|---|---|---|
| DataSource | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` (기본값은 로컬 compose) | 같은 환경 변수, **기본값 없음** — 빠지면 기동 실패 | Testcontainers `@ServiceConnection` |
| Flyway | 앱 기동 시 실행 (Boot 기본값) | 같음. 마이그레이션을 별도 Job으로 분리할 때 prod만 끈다 | 같음 |
| `ddl-auto` | validate | validate | validate |
| p6spy (`decorator.datasource.enabled`) | true, 정렬된 SQL 로그 출력 | false (DataSource 래핑 자체를 끔) | true, SQL 로그 끔 |
| Swagger (springdoc) | 켜짐 | 꺼짐 | 켜짐 |
| 그 밖에 (공통) | `open-in-view: false`, graceful shutdown, health probes, Hibernate UTC, 앱 패키지 debug 로그(local만) | | |

**traceId (전역 MDC 로깅)**
- `TraceIdFilter`(`OncePerRequestFilter`, 가장 먼저 실행): 요청 헤더 `X-Request-Id`가 형식에 맞으면 이어 쓰고, 없거나 맞지 않으면 UUID를 만든다. MDC `traceId`와 응답 헤더 `X-Request-Id`에 넣고, 요청이 끝나면 MDC에서 지운다.
- 헤더 이름은 nginx, gateway가 흔히 쓰는 `X-Request-Id`로 맞춰 앞단이 생기면 같은 값이 이어지게 한다.
- 헤더 값은 외부 입력이라 ASCII 영숫자, `-`, `_`, 1~64자만 받는다. 개행이 섞이면 로그 한 줄에 가짜 로그를 끼워 넣을 수 있기 때문이다.
- 서블릿은 요청 하나를 스레드 하나에서 처리하므로 MDC(ThreadLocal)로 충분하다. 로그 패턴(`logging.pattern.correlation: "[%X{traceId:-}] "`)으로 모든 로그 줄, p6spy SQL 로그까지 같은 traceId가 찍힌다.
- 직접 만든 필터로 충분하다고 판단해 Micrometer Tracing은 쓰지 않는다 (trace 백엔드로 보내지 않으므로 spanId와 traceparent 전파가 필요 없다).

**로컬 실행 환경**
- 루트 `compose.yaml`에 postgres 서비스 하나 (DB, 계정, 비밀번호 모두 `inventory`, 포트 5432, healthcheck 포함)
- `.editorconfig`로 인코딩, 줄바꿈, 들여쓰기 통일

### 비즈니스 규칙
기능이 없는 이슈라 비즈니스 규칙 대신 구조 규칙을 둔다.
- R1. 앱은 기능 코드 없이 기동하고 PostgreSQL에 연결된다.
- R2. 프로파일을 지정하지 않으면 local로 기동해 SQL 로그가 켜진다. prod는 명시해야만 적용되고, SQL 로그와 Swagger가 꺼지며 DB 접속 환경 변수가 없으면 기동하지 않는다. 마이그레이션은 두 프로파일 모두 앱 기동 시 실행한다.
- R3. api 모듈은 db 모듈 클래스를 컴파일 시점에 참조할 수 없다.
- R4. domain 모듈은 Spring Data를 컴파일 시점에 참조할 수 없다.
- R5. 실행 jar는 api 모듈 하나이고, domain, db, flyway, p6spy 모듈이 그 안에 포함된다.
- R6. local 프로파일의 SQL 로그는 정렬된 SQL과 실행 시간 꼬리줄로 남는다. DDL(create, alter, drop, comment)은 DDL 형식으로, SQL이 없는 이벤트(commit 등)는 카테고리 이름만 남긴다.
- R7. 포매터는 DataSource 래핑이 켜져 있으면(기본값) 앱 기동 첫 SQL부터 적용되고, 꺼져 있으면 지정하지 않는다.
- R8. flyway 모듈의 마이그레이션은 PostgreSQL에 적용된다.
- R9. 모든 요청은 traceId를 하나 가진다. 형식에 맞는 `X-Request-Id`를 받으면 이어 쓰고 아니면 새로 만들며, 응답 헤더로 돌려주고 요청 처리 중 모든 로그 줄에 찍힌다. 요청이 끝나면 MDC에서 지운다.

### 예외 케이스
| 상황 | 결과 |
|---|---|
| DB에 연결할 수 없음 | 기동 실패 (Hikari `connection-timeout` 3초) |
| 마이그레이션 파일이 없음 (스키마 이슈 이전) | 정상. Flyway가 "No migrations found"를 로그로 남기고 기동 |
| 엔티티가 없음 | 정상. `ddl-auto=validate`가 검증할 대상이 없다 |

### 테스트 케이스
| ID | 대상 규칙 | Given | When | Then | 종류 |
|---|---|---|---|---|---|
| TC-1-01 | R1 | Testcontainers PostgreSQL, test 프로파일, 실제 포트로 기동 | `GET /actuator/health` | `200`, `status`가 `UP` (기동 시 Flyway와 JPA가 DB에 연결하고, health에 db 인디케이터가 포함된다) | 통합 |
| TC-1-02 | R6 | statement, `select … where sku = 'SKU-001' order by name` | 포매터로 메시지 생성 | select, from, where, order by가 각각 한 줄, 바인딩 값과 `Execution Time: 17 ms` 포함 | 단위 |
| TC-1-03 | R6 | commit, SQL 없음 | 포매터로 메시지 생성 | `commit`만 출력 | 단위 |
| TC-1-04 | R6 | `left join`이 있는 SELECT | 포매터로 메시지 생성 | `left join`이 한 줄에 유지 | 단위 |
| TC-1-05 | R6 | create table | 포매터로 메시지 생성 | DDL 형식, 실행 시간 포함 | 단위 |
| TC-1-06 | R6 | drop table | 포매터로 메시지 생성 | DDL 형식, 실행 시간 포함 | 단위 |
| TC-1-07 | R6 | alter table | 포매터로 메시지 생성 | DDL 형식, 실행 시간 포함 | 단위 |
| TC-1-08 | R6 | comment on column | 포매터로 메시지 생성 | DDL 형식, 실행 시간 포함 | 단위 |
| TC-1-09 | R6 | 문자열 리터럴 안에 SQL 키워드 | 포매터로 메시지 생성 | 리터럴은 그대로 | 단위 |
| TC-1-10 | R7 | decorator 설정 없음 | 컨텍스트 기동 | 시스템 프로퍼티에 포매터 지정 | 단위(ContextRunner) |
| TC-1-11 | R7 | `enable-logging=false` | 컨텍스트 기동 | 포매터 지정 | 단위(ContextRunner) |
| TC-1-12 | R7 | `decorator.datasource.enabled=false` | 컨텍스트 기동 | 지정하지 않음 | 단위(ContextRunner) |
| TC-1-13 | R7 | DataSource 자동 설정과 p6spy 스타터 | 컨텍스트 기동 후 DataSource 조회 | 스타터 초기화 뒤에도 p6spy 옵션의 포매터가 유지 | 단위(ContextRunner) |
| TC-1-14 | R8 | 테스트용 V1 마이그레이션 | 컨텍스트 기동 | 테이블과 이력 테이블이 생기고 현재 버전 1 (PostgreSQL 지원 의존성 누락 시 실패) | 통합 |
| TC-1-15 | R9 | `X-Request-Id` 없음 | 필터 통과 | UUID traceId가 응답 헤더와 요청 처리 중 MDC에 있다 | 단위 |
| TC-1-16 | R9 | `X-Request-Id: gw-1234_abcd` | 필터 통과 | 같은 값을 응답 헤더와 MDC에 쓴다 | 단위 |
| TC-1-17 | R9 | 개행 포함, 65자, 한글, 공백 헤더 | 필터 통과 | 버리고 새 UUID를 만든다 | 단위 |
| TC-1-18 | R9 | 처리 중 예외 | 필터 통과 | 예외는 그대로 나가고 MDC의 traceId는 지워진다 | 단위 |
| TC-1-19 | R9 | 실제 포트로 기동 | `X-Request-Id: demo-trace-001`로 `GET /actuator/health` | 응답 헤더 `X-Request-Id: demo-trace-001` | 통합 |

**검증 방법** (자동 테스트로 만들기 어려운 규칙)

| 대상 규칙 | 실행 | 기대 결과 |
|---|---|---|
| R9 | local 기동 후 `curl -i -H 'X-Request-Id: demo-trace-001' localhost:8080/actuator/health` (DispatcherServlet debug 로그 켬) | 해당 요청 로그 줄에 `[demo-trace-001]` (응답 헤더는 TC-1-19) |
| R2, R7 | 프로파일 없이 실행, `SPRING_PROFILES_ACTIVE=prod`로 DB 환경 변수 없이/있이 실행 | 프로파일 없음: `default profile: "local"` 로그, Flyway 실행 로그, 첫 SQL부터 정렬된 p6spy 로그. prod(변수 없음): 기동 실패. prod(변수 있음): Flyway 실행 로그만 있고 p6spy 로그 없음, `/swagger-ui.html` 404 |
| R3 | api 모듈에 `import com.logistics.inventory.db.config.JpaConfig;`(또는 `...p6spy.PrettySqlFormatter`)를 넣고 `./gradlew :app:api:compileJava` | 컴파일 실패 (확인 후 되돌림) |
| R4 | domain 모듈에 `JpaRepository`를 상속한 인터페이스를 넣고 `./gradlew :inventory-domain:compileJava` | 컴파일 실패 (확인 후 되돌림) |
| R5 | `./gradlew :app:api:bootJar` 후 `jar tf app/api/build/libs/*.jar \| grep BOOT-INF/lib/` | jar 1개, `inventory-domain`, `inventory-db`, `flyway`, `p6spy` jar 포함 |

### PR 계획
각 PR은 단독으로 머지할 수 있고 자기 범위의 테스트를 포함한다.

| PR | 브랜치 | 범위 | 포함 테스트 케이스 |
|---|---|---|---|
| 1 | `chore/1-multimodule-setup` | 멀티모듈 구성(app/api, domain, db, support/flyway), 모듈 의존 방향, 프로파일별 설정(local 기본, prod 명시), `inventory-db.yml`, 로컬 PostgreSQL compose, 명세서 | TC-1-01, TC-1-14, R2 ~ R5 검증 방법 |
| 2 | `chore/1-p6spy-logging` | support/p6spy 모듈(포매터, AutoConfiguration), 프로파일별 p6spy 설정 | TC-1-02 ~ TC-1-13, R2와 R7 검증 방법 |
| 3 | `chore/1-trace-id-logging` | TraceIdFilter, 로그 패턴의 traceId (`Closes #1`) | TC-1-15 ~ TC-1-19, R9 검증 방법 |

### 결정 필요
- 없음. 모듈 구조는 `inventory-architecture` skill §1을 따른다 (이번 이슈에서 skill §1을 `app/`, `support/` 구조로 갱신).

### 변경 이력
- 2026-10-15: Flyway 테스트를 PostgreSQL 실제 적용(TC-1-14) 하나로 줄임 (location, clean 금지, 비활성 등은 Boot 기본 동작 검증이라 제외), traceId TC를 TC-1-15 ~ 19로 당김, domain의 spring-context와 spring-tx는 코드가 생기는 첫 서비스 이슈로 미룸
- 2026-10-15: R1 health 확인과 R9 응답 헤더 확인을 수동 curl에서 HTTP 통합 테스트로 옮김. TC-1-01은 DataSource 직접 확인 대신 health HTTP 확인으로 바꾸고(기동 성공이 곧 DB 연결), R9 응답 헤더는 TC-1-19로 추가
- 2026-10-15: p6spy 모듈과 전역 MDC(traceId) 로깅을 범위에 추가하고 PR을 3개로 나눔 (로그 형식은 이후 기능 이슈가 모두 쓰는 기반이라 초기 세팅에 포함)
- 2026-10-15: 프로파일을 local(기본)과 prod(명시)로 변경, 마이그레이션은 모든 프로파일에서 앱 기동 시 실행, DataSource와 JPA 설정을 db 모듈 `inventory-db.yml`로 이동, db → domain을 compileOnly로 변경, prod에서 Swagger 끔, api의 spring-tx는 에러 응답 이슈로 미룸
