# Inventory Management System

상품의 입고 · 출고 · 재고 조회를 제공하는 재고 관리 API.
여러 서버(Pod)가 동시에 요청을 처리해도 재고 정합성이 깨지지 않는 것을 목표로 한다.


## 기술 스택
Java 21 · Spring Boot · Spring Data JPA · PostgreSQL · Flyway · Gradle(Kotlin DSL)

## 모듈 구조

| 모듈 | 역할 |
|---|---|
| `app` | 실행 모듈. 도메인 조립, Controller, 요청과 응답 DTO, 예외 응답 |
| `inventory/core` | 엔티티, VO, Store(저장 포트) 인터페이스, 서비스 |
| `inventory/db` | Store 구현 (JPA, native query) |
| `inventory/schema` | 재고 도메인 테이블 마이그레이션 SQL |
| `support/flyway` | Flyway 실행 설정, 공용 기초 데이터 |
| `support/p6spy` | 로컬 SQL 로깅 (운영에서는 꺼짐) |

## 실행

compose 파일은 `infra/`에 있다. 아래 명령은 `infra/`에서 실행한다 (레포 루트에서는 `docker compose -f infra/compose.yaml ...`).

**전체 구성 (PostgreSQL, 마이그레이션, 앱 3대, nginx)**
```bash
cd infra
docker compose up -d --build      # flyway가 마이그레이션을 적용하고 종료한 뒤 앱 3대가 뜬다
docker compose ps -a              # flyway는 Exited (0), app 3개와 nginx는 Up

# 입고 (상품이 없으면 입고할 때 함께 만들어진다. 시작할 때 들어 있는 상품은 없다)
curl -X POST localhost:8080/api/v1/inventory/SKU-001/inbound \
  -H 'Content-Type: application/json' -H 'X-User-Id: user1' -H "Idempotency-Key: $(uuidgen)" \
  -d '{"quantity":10}'

# 조회를 여러 번 보내면 nginx가 앱 3대에 번갈아 나눠 보낸다
for i in $(seq 6); do curl -s localhost:8080/api/v1/inventory/SKU-001; echo; done
docker compose logs nginx         # upstream=<앱 IP>:8080 이 서로 다른 IP 3개로 번갈아 나오면 분산된 것

docker compose down -v            # 종료하고 DB 데이터까지 지움
```

**부하 테스트 (k6)** — 레포 루트에서 `./load-test/run.sh [load|stress|spike]`. 빈 DB에서 전체 구성을 띄우고, 상품 하나에 입고와 출고를 몰아 보낸 뒤 재고 정합성을 확인한다 (깨지면 exit 1). 결과는 [부하 테스트 결과](docs/load-test-result.md)
- Swagger UI: http://localhost:8080/api/swagger-ui.html (API 목록과 헤더 설명, Try it out으로 직접 호출)
- 앱 컨테이너를 다시 만들면(`up --build` 등) `docker compose restart nginx`로 nginx가 바뀐 앱 주소를 다시 읽게 한다.

**관측 스택 (로그, 메트릭)**
```bash
cd infra
docker compose --profile monitoring up -d --build   # 전체 구성 + Alloy, Loki, Prometheus, Grafana
```
- Grafana: http://localhost:3000 (admin / admin). 첫 화면이 `재고 서비스` 대시보드다 (재고 변경 건수와 수량, 거절 사유, API p95, replica별 처리량, DB 커넥션, 슬로 쿼리, 경고와 에러 로그)
- Prometheus: http://localhost:9090 (`Status > Targets`에 앱 replica가 하나씩 보인다)
- 로그 검색 (Grafana Explore, Loki): `{service="app"} | json | traceId="<응답 헤더 X-Request-Id 값>"`
- 쿼리별 누적 시간: `docker compose exec postgres psql -U inventory -c "SELECT calls, round(total_exec_time) AS ms, query FROM pg_stat_statements ORDER BY total_exec_time DESC LIMIT 10"` (빈 볼륨으로 처음 띄울 때 확장이 만들어진다. 이전 볼륨이면 `down -v` 후 다시 띄운다)
- 앱의 actuator는 관리 포트 8081에만 있고 호스트에 열지 않는다. 컨테이너 안에서 확인: `docker compose exec app wget -qO- localhost:8081/actuator/health/readiness`

**k8s**: `infra/README.md` (ArgoCD 배포 흐름, 처음 설정, 롤백), `infra/helm/README.md` (차트 구성, 관측 스택 설치, minikube로 차트만 확인)

**로컬 개발 (IDE에서 앱 실행)**
```bash
cd infra
docker compose up -d postgres     # DB만 띄운다. 전체 구성과 함께 띄우면 앱과 nginx가 8080 포트를 두고 충돌한다
```
`app` 모듈의 `InventoryApplication`을 IDE에서 실행하거나 `./gradlew :app:bootRun`으로 실행한다. 프로파일 없이 실행하면 local 프로파일로 뜨고, 앱이 기동할 때 마이그레이션을 적용한다.

## 개발 프로세스
- [작업 흐름](docs/conventions/workflow.md) — 요구사항 → 이슈 → 기능 명세서 → PR
- [Git 컨벤션](docs/conventions/git.md) — 브랜치 · 커밋 · PR 규칙
- 기능 명세서: `docs/specs/` — 이슈마다 어떻게 개발할지 (개발 시작 시 작성)
