# 기능 명세 — 로깅, 모니터링 (#22)

### 요구사항 해석
- 앱은 하나지만 같은 앱이 여러 대(replica) 뜬다 (compose는 3대, 나중에 Helm). 운영자가 두 가지를 할 수 있어야 한다.
  - 요청 하나를 찾아 따라가기: 어느 replica가 처리했고, 어떤 로그를 남겼고, 느렸다면 어느 쿼리였는지
  - 처리 현황을 수치로 보기: 입고, 출고 건수와 수량, 재고 부족 거절 건수, 처리 시간
- **JSON 로그**: `prod`에서는 로그 한 줄이 JSON 객체 하나이고, 요청 중에 남긴 로그에는 `traceId` 필드가 있다.
- **살아 있는지, 준비됐는지**: 두 확인 주소가 각각 응답한다.
- **수치**: Prometheus 형식으로 노출하고, 로컬에서는 compose로 Prometheus, Loki, Grafana를 함께 띄워 화면으로 본다.
- **슬로 쿼리**: 기준 시간을 넘은 SQL을 그 요청의 traceId와 함께 남기고, 쿼리별 누적 비용은 DB에서 본다.
- 트레이스(Tempo, Jaeger 같은 저장소로 span 전송)는 하지 않는다. 이유는 아래 "트레이스를 넣지 않는 이유"에 적었다.

### 설계

```
           app ×3   :8080 서비스 / :8081 관리 (health, prometheus)
   로그     stdout(ECS JSON) ──► Alloy ──push──► Loki ┐
   메트릭   Prometheus ──pull :8081 (replica마다)─────┴──► Grafana
   슬로쿼리 앱: Hibernate 슬로 쿼리 로그 (traceId 포함)
            DB: pg_stat_statements (쿼리별 누적)
```

**바꾸는 파일**

| 파일 | 변경 |
|---|---|
| `application.yml` | 관리 포트 8081, actuator 노출 `health,info,prometheus`, `http.server.requests` 구간 히스토그램 |
| `application-prod.yml` | `logging.structured.format.console: ecs`, `service.node-name: ${HOSTNAME}` |
| `inventory-db.yml` | `hibernate.log_slow_query: ${SLOW_QUERY_MS:1000}` |
| `app/build.gradle.kts` | `runtimeOnly` micrometer-registry-prometheus |
| `InventoryService` | 원장 저장 뒤 `StockChange`를 Spring 이벤트로 발행 |
| `StockChangeMetrics` (app, 새 파일) | 커밋 뒤 이벤트를 받아 건수, 수량을 세고 재고 변경 로그를 남긴다 |
| `GlobalExceptionHandler` | 비즈니스 예외마다 `inventory.business.error{code}` 1 증가 |
| 기존 테스트 | health를 부르던 TC-1-01, TC-1-19는 actuator가 관리 포트로 옮겨가 경로를 바꾼다 (TC-1-01은 관리 포트 health, TC-1-19는 서비스 포트의 아무 경로). 슬라이스 테스트(`@WebMvcTest`)에는 `SimpleMeterRegistry`를 넣는다 |
| `infra/compose.yaml` | postgres `pg_stat_statements`, `monitoring` 프로필로 Loki, Alloy, Prometheus, Grafana |
| `infra/monitoring/`, `infra/postgres/init.sql` | 수집, 저장, 대시보드 설정 |
| 아키텍처 규칙 (`SKILL.md`), README | "로그와 지표" 절, 관측 스택 실행 방법 |

#### 1. 로그

- `prod`만 Boot 내장 구조화 로깅의 ECS 형식을 쓴다. `TraceIdFilter`가 MDC에 넣는 `traceId`가 JSON 필드로 그대로 들어가서 코드 변경이 없다.
- 줄마다 어느 replica인지 보이게 `service.node.name`에 컨테이너 호스트명을 넣는다. 예: `"service":{"name":"inventory","node":{"name":"087a66490e93"}}`
- `local`, `test`는 지금처럼 사람이 읽는 한 줄 형식이다.
- 재고가 바뀌면 커밋 뒤에 INFO 한 줄을 남긴다: `재고 변경: type=OUTBOUND, productId=1, amount=3, before=10, after=7`

형식 비교:

| 형식 | 내용 | 판단 |
|---|---|---|
| **ecs (선택)** | Elastic Common Schema. `@timestamp`, `log.level`, `service.name`처럼 필드 이름이 표준으로 정해져 있다 | Loki는 JSON이면 어느 필드든 꺼내 쓰고, 나중에 Elasticsearch로 옮겨도 이름을 그대로 알아본다 |
| logstash | Logstash 인코더 형식 (`level`, `logger_name`) | Logstash 파이프라인을 쓸 때 맞는 형식인데 우리는 쓰지 않는다 |
| gelf | Graylog 형식 | Graylog를 쓸 때만 의미가 있다 |

수집: 앱은 stdout에만 쓰고, Alloy가 Docker 컨테이너 로그를 읽어 Loki로 보낸다. 앱이 Loki 주소를 알 필요가 없고, 수집기가 죽어도 앱은 영향을 받지 않는다.
- Loki 라벨은 `service`(app, postgres, nginx), `container`(replica), `level` 세 가지만 둔다. 라벨 조합 하나가 Loki의 저장 단위(스트림) 하나라서, 값이 요청마다 달라지는 `traceId`를 라벨로 두면 요청 수만큼 스트림이 생긴다. traceId는 검색으로 찾는다: `{service="app"} | json | traceId="..."`
- 수집 시각이 아니라 앱이 찍은 `@timestamp`를 로그 시각으로 쓴다. 수집이 밀려도 시간축이 어긋나지 않는다.

#### 2. 메트릭

| 보고 싶은 것 | 지표 | 어디서 |
|---|---|---|
| 입고, 출고로 재고가 바뀐 건수 | `inventory.stock.changes{type}` Counter | `StockChangeMetrics` (커밋 뒤) |
| 입고, 출고 수량 | `inventory.stock.quantity{type}` DistributionSummary (합계, 건수, 최대) | 같음 |
| 거절 건수 (재고 부족 등) | `inventory.business.error{code}` Counter | `GlobalExceptionHandler` |
| API 요청 건수, 처리 시간, p95 | `http.server.requests{uri, status}` (Boot 자동) + 구간 히스토그램 | Boot |
| DB 커넥션 사용, 대기 | `hikaricp.connections.active`, `.pending` (Boot 자동) | Boot |

재고 변경을 HTTP 요청 수가 아니라 커밋된 변경으로 세는 이유:
- 같은 멱등성 키로 다시 보낸 요청은 재고를 바꾸지 않고 200을 받는다. 출고 1건을 네트워크 문제로 3번 보내면 `http.server.requests`의 200은 3건이지만 실제 출고는 1건이다.
- 수량(오늘 출고된 총 수량)은 요청 건수로는 알 수 없다.
- 트랜잭션 안에서 세면 커밋이 실패했을 때 DB에는 없는 변경이 카운터에만 올라간다. `@TransactionalEventListener(AFTER_COMMIT)`로 커밋된 것만 센다.

어디서 셀지 비교:

| 안 | 예 | 판단 |
|---|---|---|
| **도메인은 이벤트 발행, app이 셈 (선택)** | `InventoryService`가 `eventPublisher.publishEvent(change)`, app의 `StockChangeMetrics`가 받아서 셈 | 이벤트 발행은 이미 쓰는 spring-context에 있어 domain 의존성이 그대로다. 멱등 재요청은 이벤트 전에 `return`하므로 저절로 빠진다 |
| 서비스에서 `MeterRegistry` 직접 사용 | `InventoryService.outbound` 안에서 `counter.increment()` | domain에 Micrometer 의존성이 생긴다 (규칙상 domain은 jakarta.persistence, spring-context, spring-tx만) |
| HTTP 지표만 사용 | `http.server.requests{uri=.../outbound, status=200}` | 위의 재요청, 수량 문제 |

이벤트로는 새 record를 만들지 않고 원장이 기록하는 `StockChange`(type, 수량, 전후 재고)를 그대로 보낸다. 지표와 로그에 필요한 값이 이미 다 있다.

거절은 롤백되어 커밋 이벤트가 없으므로 예외 핸들러에서 센다. 상태 코드(409)가 아니라 에러 코드로 세서 다른 409 에러와 섞이지 않는다.

태그 값은 개수가 정해진 것만 쓴다: `type`은 INBOUND, OUTBOUND, `code`는 `ErrorCode` 상수 이름. 상품 코드 같은 요청 값을 태그에 넣으면 값마다 시계열이 생겨 앱 메모리와 Prometheus 저장량이 계속 는다.

노출과 수집:
- `/actuator/prometheus`를 Prometheus가 15초마다 긁어 간다(pull). 긁기에 실패하면 그 자체가 `up=0`으로 남아 따로 확인하지 않아도 드러난다.
- Prometheus는 compose 서비스 이름 `app`을 DNS로 풀어 replica를 모두 찾는다(`dns_sd_configs`). replica 수를 바꿔도 설정을 고치지 않는다. replica는 `instance` 라벨(IP:8081)로 나뉜다.
- `sample_limit: 10000`: 태그 실수로 시계열이 폭증하면 일부만 받지 않고 수집 전체를 실패시켜 `up=0`으로 바로 드러나게 한다.

#### 3. 관리 포트 8081

actuator(health, prometheus)는 서비스 포트와 다른 8081에서만 응답한다.

| 안 | 예 | 판단 |
|---|---|---|
| **분리 (선택)** | 8080 요청이 몰려 톰캣 스레드가 모두 차도 8081은 별도 스레드로 지표와 헬스에 응답한다 | 장애가 난 바로 그때 지표가 끊기지 않는다. 8081은 외부에 열지 않아 actuator를 인증 없이 둘 수 있다 |
| 8080 그대로 (`/api/actuator`) | 스레드가 차면 Prometheus 수집도 줄을 서다 타임아웃 → 가장 필요한 순간에 그래프가 빈다 | 외부에 열린 포트에 지표가 같이 열린다 |

대가: 8080 스레드만 꽉 찬 상태에서도 8081의 readiness는 UP이다. 이걸 막으려면 스레드 포화를 헬스에 넣어야 하는데, Helm probe를 정하는 #23에서 판단한다.

#### 4. 살아 있는지, 준비됐는지

`management.endpoint.health.probes.enabled: true`(기존)로 `:8081/actuator/health/liveness`, `/readiness`가 있다. 둘 다 DB 상태를 넣지 않는다(Boot 기본).
- liveness에 DB를 넣으면 DB 장애 때 모든 Pod가 재시작을 반복하고, DB가 돌아온 뒤에도 기동 시간만큼 늦게 복구된다.
- readiness에 DB를 넣어도 DB는 모든 Pod가 같이 쓰는 하나라 모든 Pod가 동시에 빠진다. 클라이언트 입장에서 500이 503으로 바뀔 뿐 처리되는 요청은 없고, DB가 잠깐 느리기만 해도 Pod가 줄줄이 빠지는 위험이 생긴다.

#### 5. 슬로 쿼리

| 층 | 방법 | 보이는 것 | 못 보는 것 |
|---|---|---|---|
| 앱 | `hibernate.log_slow_query: 1000` (추가 의존성 없음) | 기준을 넘은 SQL이 `org.hibernate.SQL_SLOW` 로그로 남고, 요청 스레드에서 남아 **traceId**가 붙는다 | Hibernate를 거치지 않은 쿼리 |
| DB | `pg_stat_statements` | 쿼리별 호출 수와 누적 시간 (N+1, 풀스캔을 숫자로) | 개별 요청 |

- 기준 1000ms는 락 대기 상한(`lock_timeout = 3s`)보다 짧다. 행 잠금을 오래 기다린 `SELECT ... FOR UPDATE`도 슬로 쿼리로 잡히고, traceId로 그 요청의 다른 로그까지 찾을 수 있다. 예: `Slow query took 1211 milliseconds [select ... from inventory i1_0 where i1_0.product_id=? for no key update of i1_0]`
- 기준은 환경 변수 `SLOW_QUERY_MS`로 바꾼다.

DB의 슬로 로그(`log_min_duration_statement`)는 켜지 않는다.
- 우리 쿼리는 모두 Hibernate를 거쳐 앱의 슬로 쿼리 로그에 traceId와 함께 남는다. DB 로그는 같은 쿼리를 traceId 없이 한 번 더 남기는 사본이다. 예: 락을 1.2초 기다린 `FOR UPDATE` 하나가 앱 로그와 postgres 로그에 각각 찍힌다.
- DB 로그만 잡는 것은 Hibernate를 거치지 않은 쿼리(직접 접속한 psql, Flyway)인데, 서비스 요청이 아니다.
- `pg_stat_statements`는 남긴다. 느린 쿼리 하나가 아니라 "어떤 쿼리가 전체적으로 시간을 많이 쓰나"(호출 수 × 시간)에 답해서 앱 로그와 겹치지 않는다.

#### 6. 로컬 관측 스택 (`docker compose --profile monitoring`)

| 서비스 | 이미지 | 역할 |
|---|---|---|
| `alloy` | grafana/alloy | Docker 소켓으로 이 compose 프로젝트 컨테이너의 로그를 읽어 Loki로 보낸다. app만 JSON을 파싱해 `level`을 라벨로 |
| `loki` | grafana/loki | 로그 저장 (7일) |
| `prometheus` | prom/prometheus | 지표 수집, 저장. 호스트 9090 |
| `grafana` | grafana/grafana | 데이터소스와 `재고 서비스` 대시보드를 파일로 자동 등록. 호스트 3000 |

- 프로필로 나눠 `docker compose up -d`만 하면 지금처럼 앱 구성만 뜬다. 관측 스택은 필요할 때만 띄운다.
- 운영 배포(k8s)에서는 Helm 차트로 관측 스택을 설치하지만, 부하 테스트(#18)는 compose에서 자유롭게 돌리므로 compose에도 같은 대시보드를 둔다. 부하를 걸면서 락 대기(슬로 쿼리), 커넥션 대기, p95를 바로 본다.
- 대시보드 패널: 재고 변경 건수, 수량, 거절 사유별 건수, 입고와 출고 API p95, replica별 처리량, DB 커넥션 사용과 대기, 슬로 쿼리 로그, 경고와 에러 로그

#### 트레이스를 넣지 않는 이유

트레이스는 요청 하나가 거친 구간(span)들의 시작 시각과 걸린 시간을 저장해, "이 느린 요청이 어느 구간에서 시간을 썼나"에 답한다.
- 가장 쓸모 있는 경우는 서비스끼리 호출하는 구조에서 어느 서비스가 느렸는지 찾는 것인데, 우리 앱은 DB 하나만 부른다.
- 요청 안의 구간은 다른 수단으로 대부분 보인다: 느린 요청은 `http.server.requests`, 느린 쿼리와 그 요청은 슬로 쿼리 로그와 traceId, 쿼리별 누적 비용은 `pg_stat_statements`
- 넣으려면 Tracing 브리지, OTLP 전송, JDBC 계측 의존성과 트레이스 저장소가 필요하고, 서비스 구간 span을 domain 의존성 없이 만들 방법도 정해야 한다.
- 다른 서비스를 부르게 되거나 지금 수단으로 원인을 못 찾는 일이 생기면 그때 넣는다. 로그 형식과 수집 경로는 그대로 두고 traceId 값만 트레이싱이 만든 값으로 바뀐다.

### 비즈니스 규칙
- R1. `prod`의 로그는 한 줄이 JSON 하나이고, 요청 중 남긴 로그에 그 요청의 `traceId` 필드가 있다.
- R2. liveness, readiness가 관리 포트에서 각각 200, `UP`으로 응답하고, 서비스 포트에는 actuator가 없다.
- R3. 지표를 Prometheus 형식으로 노출한다.
- R4. 커밋된 입고, 출고마다 type별 변경 건수가 1, 수량 합계가 그 수량만큼 늘고, 재고 변경 로그가 남는다. 멱등성 키로 다시 들어온 요청은 세지 않는다.
- R5. 비즈니스 에러로 거절된 건수를 에러 코드별로 센다.
- R6. 기준 시간을 넘은 SQL은 그 요청의 traceId와 함께 슬로 쿼리 로그로 남는다.

### 예외 케이스
| 상황 | 결과 |
|---|---|
| `X-Request-Id` 없이 요청 | `TraceIdFilter`가 UUID를 만들어 로그와 응답 헤더에 같은 값을 쓴다 (기존 동작) |
| 요청 밖에서 남긴 로그 (기동, 종료) | JSON이지만 `traceId` 필드가 없다 |
| 재고 부족으로 롤백 | 커밋 이벤트가 없어 변경 건수는 그대로, 거절 건수만 는다 |
| 비즈니스 에러가 아닌 거절 (형식 검증 400, 락 대기 초과 503) | `inventory.business.error`에는 안 잡히고 `http.server.requests`의 `status`로 본다 |

### 테스트 케이스
| ID | 대상 규칙 | Given | When | Then | 종류 |
|---|---|---|---|---|---|
| TC-22-01 | R1 | `test`, `prod` 프로파일로 기동 | `X-Request-Id: trace-22-01`로 없는 경로 요청 (WARN 로그) | 그 traceId가 든 줄이 모두 JSON으로 읽히고 `$.traceId`가 `trace-22-01` | 통합 |
| TC-22-02 | R2 | 기동 완료 | 관리 포트 `GET /actuator/health/liveness` | 200, `UP` | 통합 |
| TC-22-03 | R2 | 기동 완료 | 관리 포트 `GET /actuator/health/readiness` | 200, `UP` | 통합 |
| TC-22-04 | R2 | 기동 완료 | 서비스 포트 `GET /api/actuator/health` | 404 | 통합 |
| TC-22-05 | R3 | 재고 5 | 입고 3 후 관리 포트 `GET /actuator/prometheus` | `inventory_stock_changes_total{type="INBOUND"}`, `http_server_requests_seconds_bucket`이 있다 | 통합 |
| TC-22-06 | R4 | 재고 5 | 입고 3 | INBOUND 변경 건수 +1, 수량 합계 +3, `재고 변경: type=INBOUND, ... before=5, after=8` 로그 | 통합 |
| TC-22-07 | R4 | 재고 5 | 출고 2 | OUTBOUND 변경 건수 +1, 수량 합계 +2 | 통합 |
| TC-22-08 | R4 | 키 K로 입고 3 완료 | 같은 키 K로 입고 3 | 200, INBOUND 변경 건수 그대로 | 통합 |
| TC-22-09 | R5 | 재고 5 | 출고 6 | `INSUFFICIENT_STOCK` 거절 건수 +1, OUTBOUND 변경 건수 그대로 | 통합 |
| TC-22-10 | R6 | 다른 트랜잭션이 재고 행을 1.5초 잠금 | `X-Request-Id: trace-22-10`로 출고 1 | 200, `trace-22-10`이 든 줄에 `Slow query took ... for no key update` | 통합 |

- 지표는 테스트끼리 같은 컨텍스트를 공유해 앞 테스트 값이 남으므로 요청 전후 차이로 검증한다.
- 로그 형식은 JVM 전역 설정이라, 같은 JVM에서 prod 컨텍스트가 먼저 뜨면 뒤 테스트의 로그도 JSON이 된다. 로그를 보는 테스트는 형식과 무관하게 내용만 검증한다.

### 검증 방법
compose 관측 스택은 테스트 코드로 만들기 어려워 실제로 띄워 확인한다 (`infra/`에서).

| 대상 | 실행 | 기대 결과 |
|---|---|---|
| 수집 대상 | `docker compose --profile monitoring up -d --build` 후 Prometheus `Status > Targets` | `inventory` job에 replica 3개, 모두 UP |
| 지표 | 입고 10, 출고 3, 출고 100(부족) 후 `sum by (type) (inventory_stock_changes_total)`, `sum by (code) (inventory_business_error_total)` | INBOUND 1, OUTBOUND 1, INSUFFICIENT_STOCK 1 |
| replica 구분 | `docker compose logs app` | JSON 줄의 `service.node.name`이 replica마다 다르다 |
| 슬로 쿼리 | psql로 `BEGIN; SELECT * FROM inventory FOR UPDATE; SELECT pg_sleep(2); COMMIT;` 동안 `X-Request-Id: slow-demo-1`로 출고 | 앱 로그에 `Slow query took` + `traceId: slow-demo-1` |
| 로그 수집 | Grafana Explore(Loki) `{service="app"} \| json \| traceId="slow-demo-1"` | 슬로 쿼리 로그, 재고 변경 로그 |
| 대시보드 | Grafana 첫 화면 `재고 서비스` | 모든 패널에 데이터 |

### PR 계획
| PR | 브랜치 | 범위 | 포함 테스트 케이스 |
|---|---|---|---|
| 1 | `chore/22-logging-monitoring` | 위 파일 전부, 명세서 (`Closes #22`) | TC-22-01 ~ TC-22-10, 검증 방법 |

앱 설정, 코드, compose 스택을 한 PR에 둔다. compose 스택만 따로 머지하면 볼 지표가 없고, 앱만 머지하면 확인할 화면이 없어 둘이 함께 있어야 검증이 된다.

### 범위 밖
- 트레이스 (위 이유)
- 운영 수집기 구성(DaemonSet, ServiceMonitor), probe 포트와 스레드 포화 헬스: Helm 차트 작업(#23)
  - 앱 쪽(probe 주소, `/actuator/prometheus`, stdout JSON, `HOSTNAME` 노드명)은 k8s에서 그대로 쓴다. 다시 만드는 것은 compose 전용 수집 설정(Prometheus `dns_sd_configs`, Alloy `discovery.docker`)뿐이다
  - k8s의 Alloy에서도 Pod 라벨을 `service` 라벨로 붙이면 대시보드의 로그 패널(`{service="app"}`)을 고치지 않고 쓴다
- 서비스 메시(Istio): 사이드카가 요청 지표와 span을 만들어 주므로, 들이게 되면 트레이스를 그때 다시 검토한다
- 알림 규칙

### 결정 필요
- 없음

### 변경 이력
- 2026-10-16: 범위를 JSON 로그, 헬스, 수치에서 슬로 쿼리, 비즈니스 지표(커밋 뒤 이벤트), 관리 포트 분리, compose 관측 스택까지로 넓힘. 트레이스는 넣지 않기로 함
