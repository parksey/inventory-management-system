# 기능 명세 — 앱 컨테이너화 (#17)

### 요구사항 해석
- 앱을 Docker 이미지로 만든다. 이미지는 `prod` 프로파일로 뜬다 (운영과 같은 설정으로 부하 테스트를 돌리기 위해).
- Dockerfile은 이미지를 만드는 모듈에 둔다 (앱은 `app/`, 마이그레이션은 `support/flyway/`). 여러 서비스를 묶어 띄우는 compose와 nginx 설정은 `infra/`에 둔다. 멀티모듈이라 이미지 빌드 기준 폴더는 둘 다 레포 루트다. `infra/`에서 `docker compose up -d --build` 한 번으로 PostgreSQL, 앱 3대, 앞단 nginx가 함께 뜬다.
- 요청은 nginx 주소 하나(`localhost:8080`)로 보내고, nginx가 앱 3대에 라운드로빈으로 나눈다.
- 앱 3대는 같은 PostgreSQL을 쓴다. 마이그레이션은 앱이 아니라 전용 `flyway` 컨테이너가 앱보다 먼저 한 번 적용한다. 운영(Helm)에서 마이그레이션 Job을 먼저 돌리고 앱을 띄우는 구조를 로컬에 그대로 재현한다.
- 로컬 개발(IDE에서 앱 실행)은 지금처럼 `docker compose up -d postgres`로 DB만 띄워 쓴다.

### 설계

**파일**

| 파일 | 내용 |
|---|---|
| `app/Dockerfile` | 빌드 단계(Corretto 21에서 `./gradlew :app:bootJar`) + 실행 단계(Corretto 21에 jar 하나, non-root 사용자) |
| `support/flyway/Dockerfile` | Flyway 공식 이미지에 마이그레이션 SQL을 담은 이미지. 나중에 Helm 마이그레이션 Job이 같은 이미지를 쓴다 |
| `.dockerignore` | `.git`, `build/`, `.gradle/`, `.idea/`, `.claude/`, `.pm-plans/`, `docs/` 제외. 빌드 컨텍스트를 줄이고, 로컬 `build/`가 이미지 빌드에 섞이지 않게 한다 |
| `infra/compose.yaml` | 루트에서 옮긴다. 기존 `postgres`에 `flyway`, `app`(replicas 3), `nginx` 추가 |
| `application-prod.yml` | `spring.flyway.enabled: false` (prod에서는 앱이 마이그레이션하지 않는다) |
| 아키텍처 규칙(`SKILL.md`, `build-setup.md`) | "Helm 도입 시 prod만 끈다"는 설명을 "prod는 끄고 flyway 이미지가 적용한다"로 고친다 |
| `infra/nginx.conf` | `upstream`에 `app:8080` 하나, access log에 `upstream_addr` 기록 |
| `README.md` | 실행 방법 섹션 추가 |

**app/Dockerfile**
```dockerfile
# 빌드 기준 폴더는 레포 루트다 (멀티모듈 Gradle 빌드): docker build -f app/Dockerfile .
FROM amazoncorretto:21-alpine AS build
WORKDIR /workspace
COPY . .
# 테스트는 CI가 맡는다. Gradle 캐시를 빌드 사이에 재사용해 의존성을 다시 받지 않는다
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon :app:bootJar

# Corretto는 JRE 전용 이미지가 없어 JDK 이미지를 그대로 쓴다
FROM amazoncorretto:21-alpine
RUN adduser -S -u 1001 app
USER app
COPY --from=build /workspace/app/build/libs/*.jar /app.jar
ENV SPRING_PROFILES_ACTIVE=prod
# 힙 상한을 컨테이너 메모리 limit의 75%로 (기본 25%), OOM이 나면 반쯤 죽은 채 남지 않고 종료해 재시작되게 한다
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app.jar"]
```
- JDK는 CI(#6)와 같은 Amazon Corretto 21을 쓴다. Corretto는 JRE만 담은 이미지가 없어 실행 단계도 JDK 이미지(alpine)를 쓴다.
- JVM 메모리 옵션은 `JAVA_TOOL_OPTIONS`로 넣어, 배포할 때 환경 변수로 바꿀 수 있게 한다.
  - `-XX:MaxRAMPercentage=75`: JVM은 기본으로 컨테이너 메모리의 25%만 힙으로 쓴다. 예를 들어 limit 1GB면 힙이 256MB라 메모리가 남아도 OOM이 난다. 75%면 힙 768MB, 나머지 256MB는 스레드 스택, 클래스 정보 같은 힙 밖 메모리 몫이다. `-Xmx` 고정값 대신 비율로 두어 limit만 바꾸면 힙도 따라간다.
  - `-XX:+ExitOnOutOfMemoryError`: 기본은 OOM이 나도 JVM이 살아 있어, 메모리가 꽉 찬 채 요청이 계속 실패하거나 일부 스레드만 죽은 상태로 남는다. 바로 종료해 오케스트레이터가 새로 띄우게 한다.
- 테스트는 이미지 빌드에서 돌리지 않는다. 테스트는 CI(#6)가 맡고, `bootJar` 태스크만 실행하므로 테스트가 끼지 않는다.
- Gradle 캐시를 `--mount=type=cache`로 빌드 사이에 재사용해, 소스만 바꾼 재빌드에서 의존성을 다시 받지 않는다.
- Boot의 레이어 분리(`jarmode=tools extract`)는 하지 않는다. 레이어를 나누면 코드만 바뀐 이미지의 push, pull이 작아지는데, 지금은 이미지를 레지스트리에 올리지 않고 로컬에서만 쓴다.

**support/flyway/Dockerfile**
```dockerfile
FROM flyway/flyway:12.4.0
COPY support/flyway/src/main/resources/db/migration /flyway/sql
COPY inventory/schema/src/main/resources/db/migration /flyway/sql
CMD ["migrate"]
```
- 버전은 앱이 쓰는 `flyway-core`(Boot BOM의 12.4.0)와 맞춘다. 로컬(local 프로파일, 앱 기동 시 실행)과 테스트가 쓰는 Flyway와 컨테이너의 Flyway가 갈라지면, 한쪽에서 통과한 마이그레이션이 다른 쪽에서 다르게 동작할 수 있다.
- 마이그레이션 SQL은 두 모듈에 나뉘어 있어(공용 기초 데이터는 `support/flyway`, 재고 테이블은 `inventory/schema`) 둘 다 `/flyway/sql` 한 폴더로 복사한다. 도메인 모듈이 늘면 `COPY` 줄을 하나씩 더한다.
- 접속 정보는 이미지에 넣지 않고 환경 변수(`FLYWAY_URL`, `FLYWAY_USER`, `FLYWAY_PASSWORD`)로 받는다.

**compose 구성**

| 서비스 | 이미지 | 설정 |
|---|---|---|
| `postgres` | `postgres:16-alpine` | 지금 그대로 (5432 노출, healthcheck) |
| `flyway` | `support/flyway/Dockerfile` | `migrate`를 한 번 실행하고 종료한다. postgres가 healthy가 된 뒤 뜬다 |
| `app` | `app/Dockerfile` | `deploy.replicas: 3`, `DB_URL=jdbc:postgresql://postgres:5432/inventory`, `DB_USERNAME`, `DB_PASSWORD`, `SWAGGER_ENABLED=true`. flyway가 **성공 종료**(`service_completed_successfully`)한 뒤에만 뜬다. 호스트 포트는 열지 않는다 (여러 대가 같은 포트를 열 수 없고, 진입은 nginx로만) |
| `nginx` | `nginx:1.27-alpine` | 호스트 `8080` → nginx `80`. app이 뜬 뒤 뜬다 |

- DB 커넥션: 앱마다 풀 10(`DB_POOL_SIZE` 기본값) × 3대 = 30. PostgreSQL 기본 `max_connections` 100 안이다.

**nginx**
```nginx
upstream inventory_app {
    server app:8080;
}
log_format upstream '$remote_addr "$request" $status upstream=$upstream_addr';
```
- `app` 이름을 nginx가 뜰 때 Docker DNS로 풀면 앱 3대의 IP가 모두 나오고, nginx는 그 주소들에 라운드로빈으로 보낸다.
- 앱 컨테이너를 다시 만들어 IP가 바뀌면 nginx도 재시작해야 한다 (`docker compose restart nginx`). nginx가 IP를 뜰 때 한 번만 풀기 때문이다. 주기적으로 다시 푸는 `resolve` 옵션도 있지만, 로컬 재현 환경에서는 `up` 한 번으로 다 같이 뜨므로 쓰지 않는다.
- 재시도: nginx는 앱에 연결이 안 될 때만 다음 앱으로 넘기고, POST처럼 멱등하지 않은 요청은 응답을 받는 중에 실패해도 다시 보내지 않는다 (nginx 기본 동작). 입고가 두 앱에 두 번 들어가는 일이 없어서 별도 설정을 두지 않는다.

**마이그레이션을 한 번만 적용하는 방법** — 전용 컨테이너

`flyway` 컨테이너 하나가 먼저 마이그레이션을 적용하고 종료한다. 앱은 prod에서 Flyway를 끄고(`spring.flyway.enabled: false`), `ddl-auto: validate`로 스키마가 엔티티와 맞는지만 확인한다.
- 예: 빈 DB에서 `up`하면 flyway가 V7_1 ~ V21_1을 적용하고 exit 0으로 끝난다. 그 뒤에 앱 3대가 뜨고, 앱 로그에는 마이그레이션 로그가 없다.
- 마이그레이션 SQL에 오류가 있으면 flyway가 0이 아닌 코드로 끝나고, 앱은 하나도 뜨지 않는다. 잘못된 스키마에서 앱이 요청을 받는 일이 없다.
- local 프로파일과 테스트는 지금처럼 앱 기동 시 Flyway가 돈다. IDE로 개발할 때 마이그레이션을 따로 돌리지 않아도 된다.

비교한 대안 — 앱 기동 시 Flyway 실행 (Spring Boot 기본값):
- 앱 3대가 동시에 떠도 Flyway가 PostgreSQL 잠금(advisory lock)을 잡아 한 대만 적용하고, 나머지는 기다렸다가 "이미 최신"으로 넘어간다. 파일이 늘지 않고 이슈의 완료 조건도 충족한다.
- 택하지 않은 이유: 운영에서 흔히 마이그레이션을 앱과 분리하는 이유가 이 방식의 약점이다.
  - SQL이 하나만 잘못돼도 새 Pod가 전부 기동에 실패하고 재시작을 반복한다. 전용 Job이면 Job만 실패하고 배포가 멈춰 기존 Pod는 계속 요청을 받는다.
  - 인덱스 생성처럼 몇 분 걸리는 마이그레이션은 그동안 Pod가 준비 상태가 되지 않아, k8s가 장애로 보고 마이그레이션 도중에 Pod를 다시 시작할 수 있다.
  - 앱 계정에 테이블을 만들고 바꾸는 권한을 줘야 한다. 분리하면 그 권한은 마이그레이션 계정에만 둘 수 있다.
- compose를 "운영과 비슷한 구성"으로 쓰는 것이 이 이슈의 목적이라, 운영에서 쓸 구조를 지금 compose에 맞춰 두고 Helm 차트에서는 같은 이미지를 Job으로 옮기기만 한다.

### 비즈니스 규칙
- R1. 명령 하나로 postgres, 앱 3대, nginx가 뜬다.
- R2. nginx 주소 하나로 보낸 요청이 앱 3대에 나뉘어 들어간다.
- R3. 앱 3대가 같은 DB를 쓰고, 마이그레이션은 flyway 컨테이너가 앱보다 먼저 한 번만 적용한다.
- R5. 마이그레이션이 실패하면 앱이 뜨지 않는다.
- R4. 앱 컨테이너는 `prod` 프로파일, root가 아닌 사용자로 실행되고, 마이그레이션을 실행하지 않는다.

### 예외 케이스
| 상황 | 결과 |
|---|---|
| 앱이 아직 기동 중일 때 nginx로 요청 | nginx가 연결 실패를 다른 앱으로 넘기고, 모두 실패하면 502 |
| 앱 1대를 내림 (`docker stop`) | nginx가 연결 실패한 앱을 잠시 빼고 나머지 2대로 응답 |
| 마이그레이션이 이미 최신인 DB로 다시 `up` | flyway가 적용할 것 없이 exit 0, 앱 정상 기동 |
| 앱 3대와 IDE의 local 앱을 함께 실행 | 둘 다 호스트 8080을 써서 충돌한다. IDE로 개발할 때는 `docker compose up -d postgres`만 쓴다 (README에 명시) |

### 검증 방법
아래 `docker compose` 명령은 `infra/`에서 실행한다.
컨테이너 구성은 테스트 코드로 만들기 어려워 실제 실행으로 확인한다. 

| 대상 규칙 | 실행 | 기대 결과 |
|---|---|---|
| R1 | `docker compose down -v` 후 `docker compose up -d --build`, `docker compose ps` | postgres, app 3개, nginx running, flyway `Exited (0)` |
| R2 | 상품 입고 1회 후 `GET localhost:8080/api/v1/inventory/{상품코드}` 재고 조회를 30번, `docker compose logs nginx \| grep -o 'upstream=[^ ]*' \| sort \| uniq -c` | 서로 다른 upstream 주소 3개, 각각 10번 안팎 |
| R3, R4 | 빈 DB에서 띄운 직후 `docker compose logs flyway`, `docker compose logs app \| grep -i flyway`, `SELECT version, success FROM flyway_schema_history` | flyway 로그에 마이그레이션 적용, 앱 로그에는 Flyway 실행 없음. 이력은 마이그레이션마다 한 줄, 모두 success |
| R3 | 앱 A로 입고 후 nginx로 조회를 여러 번 | 어느 앱이 받든 같은 재고 |
| R4 | `docker compose exec app id`, 앱 로그 | `uid=1001(app)`, `The following 1 profile is active: "prod"` |
| 메모리 옵션 | `docker compose exec app java -XX:+PrintFlagsFinal -version \| grep -E 'MaxRAMPercentage\|ExitOnOutOfMemoryError'` | `MaxRAMPercentage = 75`, `ExitOnOutOfMemoryError = true` |
| R5 | 임시로 문법 오류가 있는 마이그레이션 SQL을 넣고 `docker compose down -v && docker compose up -d --build` (확인 후 삭제) | flyway `Exited (1)`, app 컨테이너는 시작되지 않음 |
| 예외 | 이미 띄운 상태에서 `docker compose down && docker compose up -d` | flyway 로그 "Schema ... is up to date", exit 0, 앱 정상 |
| 예외 | `docker stop` 으로 앱 1대 내린 뒤 조회 10번 | 모두 200, upstream은 남은 2개 |

### PR 계획
| PR | 브랜치 | 범위 | 검증 |
|---|---|---|---|
| 1 | `chore/17-containerization` | 앱, flyway Dockerfile, `.dockerignore`, compose, nginx 설정, prod Flyway 끄기와 규칙 문서 반영, README 실행 방법, 명세서 (`Closes #17`) | R1 ~ R5, 예외 검증 방법 |

### 결정 필요
- 없음

### 변경 이력
- 2026-10-16: 마이그레이션 실행 위치를 앱 기동에서 전용 flyway 컨테이너로 결정 (운영 구조 재현)
- 2026-10-16: compose와 nginx 설정은 `infra/`로, Dockerfile은 각 모듈(`app/`, `support/flyway/`)로 옮김 (`compose.yaml`은 루트에서 옮기고, 프로젝트 이름은 `name: inventory`로 고정)
- 2026-10-16: 베이스 이미지를 Temurin에서 CI와 같은 Corretto 21(alpine)로 바꿈
- 2026-10-16: JVM 메모리 옵션(MaxRAMPercentage, ExitOnOutOfMemoryError) 추가
