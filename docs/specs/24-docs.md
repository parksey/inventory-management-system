# 기능 명세 — 문서 정리 (#24)

### 요구사항 해석
- 레포를 처음 보는 사람이 README 하나로 네 가지를 알 수 있어야 한다. 무엇을 만들었는지, 왜 그렇게 만들었는지, 어떻게 실행하는지, 정합성을 어떻게 검증했는지다. 자세한 내용은 README에서 링크로 따라간다.
- 설계 이유는 지금까지 이슈마다 명세서(`docs/specs/`)의 "왜 ~하나" 절에 적었다. ADR은 이 이유를 다시 쓰지 않는다. 결정 하나를 한 쪽으로 요약하고, 비교한 대안과 근거는 명세서의 해당 절로 링크한다. 근거를 두 곳에 쓰면 한쪽만 고쳐지는 일이 생기기 때문이다.
- ERD와 API 문서는 지금 코드와 마이그레이션 SQL 기준으로 새로 쓴다. 명세서는 이슈 당시 계획이라, 뒤 이슈가 바꾼 내용(예: #20의 응답 형식, #21의 인덱스)이 반영되어 있지 않다.
- 문서와 구현이 다른 곳을 함께 정리한다. 문서가 틀린 곳은 이 이슈에서 문서를 고친다. 코드가 문서와 다른 곳은 코드를 바꿔야 하므로 고치지 않고 새 이슈로 제안한다.
- 범위 밖: 기능과 코드 변경, Swagger 어노테이션 추가(코드 변경이라 필요하면 별도 이슈).

### 설계

#### 1. 산출물

| 파일 | 내용 |
|---|---|
| `docs/ddl.sql` | 마이그레이션 SQL을 버전 순서대로 모은 전체 DDL. 원본은 마이그레이션이고, 마이그레이션을 추가하면 함께 고친다 (`inventory-architecture` 체크리스트에 추가) |
| `README.md` | 아래 2의 목차로 다시 쓴다. API 목록과 ERD 그림은 두지 않는다. API는 호출 예시 파일과 Swagger UI가, 테이블은 `docs/ddl.sql`이 보여 주고 README는 링크한다. 같은 표를 두 곳에 두면 한쪽만 고쳐지기 쉽다 |
| `app/http/inventory.http` | API 호출 예시 (IntelliJ HTTP Client, VS Code REST Client). 입고, 출고, 조회, 이력, 상품명 수정과 중복 요청, 422, 409, 404, 400 |
| `docs/adr/README.md` | ADR 목록 (번호, 제목, 관련 이슈) |
| `docs/adr/NNN-*.md` | 아래 3의 ADR 12개 |
| 기존 문서 | 아래 5의 불일치 수정 |

#### 2. README 목차

| 섹션 | 내용 |
|---|---|
| 소개 | 한 문단 정의와 핵심 특징 셋 (여러 서버에서도 재고 정합성 유지, 빌드가 막는 모듈 경계, 운영 구성 재현), 기술 스택 |
| 구조 | 모듈 의존 그림, 모듈별 역할 표(모듈 이름이 폴더 링크)와 의존 규칙, 디렉토리 구조 |
| 배포 구조 (k8s) | #23 명세의 배포 그림(`docs/specs/images/23-helm-deploy.png`)을 그대로 쓰고, 아래에 배포 흐름, 마이그레이션 시점, compose와의 대응, infra 문서 링크 |
| 데이터베이스 | 그림 없이 위치 링크(`docs/ddl.sql`, 마이그레이션 폴더)와 테이블 4개 한 줄 설명. 뒤 절의 "원장", "재고 행"이 어느 테이블인지 알게 하기 위해서 |
| 동시성과 정합성 | 전제(여러 Pod, DB 하나) → 입고, 출고 한 건의 처리 순서(멱등성 키 선점, 상품 확인, 재고 행 잠금, 규칙 판단, 원장 기록) → DB 제약이 마지막으로 막는 것 → 검증 결과(동시성 테스트, k6 수치는 `docs/load-test-result.md`의 값 그대로) |
| 요구사항 해석과 가정 | 요구사항을 어떻게 해석했는지 표, 가정과 그 이유 표 |
| 실행 방법 | 요구 환경, 테스트, 전체 구성, API 호출 예시 파일과 Swagger, 부하 테스트, 관측 스택, 로컬 개발, k8s 문서 링크 |
| 개발 방식 | 요구사항 → 이슈 → 명세서 → PR 흐름, 테스트 케이스 ID 추적, CI와 배포 흐름, 컨벤션 문서 링크 |
| AI 활용 방식 | 아래 4 |
| 문서 | 문서 링크 목록 |

- README는 지도 역할이다. 각 섹션은 요약하고 자세한 내용은 링크로 넘긴다.
- 지금 README의 실행 명령은 점검 결과 맞으므로 그대로 옮긴다.
- ADR을 쓴 뒤(PR 2) 모듈 구조와 동시성 절에 ADR 링크를 더한다.

#### 3. ADR

양식 (`docs/adr/NNN-제목.md`):
```markdown
# ADR-NNN: {결정}
- 상태: 채택
- 관련: #이슈, 명세서 절 링크

## 맥락        — 어떤 문제와 제약이 있었나
## 결정        — 무엇을 택했나 (한두 문장)
## 검토한 대안  — 대안마다 한 줄 요약과 택하지 않은 이유, 자세한 비교는 명세서 링크
## 결과        — 얻은 것, 감수한 것, 다시 검토할 조건
```

| ADR | 결정 | 근거가 있는 명세서 |
|---|---|---|
| 001 | 모듈 구조: 실행 모듈 하나(`app`)와 도메인별 `core`, `db`, `schema`. 의존 방향을 Gradle 의존성(`runtimeOnly`, `compileOnly`)으로 강제 | #1, #7 PR 계획 |
| 002 | 상품과 재고를 테이블 둘로 나눔 | #7 "왜 두 테이블로 나누나" |
| 003 | 재고 변경은 재고 행 잠금(`SELECT ... FOR UPDATE`) 뒤 엔티티가 규칙 판단. 조건부 원자적 UPDATE는 쓰지 않음 | #12 "왜 원자적 UPDATE로 바꾸지 않나", #13 "왜 재고 부족 판단을 잠근 뒤 엔티티에서 하나" |
| 004 | 잠금 대기 상한 3초를 커넥션 초기화 SQL로 걸고, 넘으면 503과 `Retry-After` | #12 "잠금 대기 상한", #20 |
| 005 | 처음 보는 상품은 입고할 때 등록하고, 동시 등록은 `ON CONFLICT DO NOTHING`으로 하나만 남김 | #12 "왜 상품 INSERT는 ON CONFLICT DO NOTHING인가" |
| 006 | 멱등성 키를 별도 테이블에 두고 트랜잭션 맨 앞에서 선점. 내용이 다르면 422, 실패한 요청은 키를 남기지 않음 | #15 "왜 별도 테이블인가", "왜 키를 맨 앞에서 확인하나" |
| 007 | 성공과 에러를 공통 응답 하나로 감싸고, `code`는 `ErrorCode` 상수 이름 | #20 "왜 공통 응답으로 감싸나" |
| 008 | 이력 조회는 `id` 기준 커서, 전체 건수 없음 | #21 "왜 커서 방식으로 나누나" |
| 009 | 마이그레이션을 앱 기동에서 떼어 전용 이미지(compose 서비스, Helm hook Job)로 한 번만 실행 | #17, #23 |
| 010 | 관측은 JSON 로그(Loki)와 메트릭(Prometheus)까지. 트레이스는 넣지 않음 | #22 "트레이스를 넣지 않는 이유" |
| 011 | 배포는 GitOps: CI가 이미지와 tag 커밋, ArgoCD가 클러스터 반영 | #23 "배포" |
| 012 | 회사별 분리: `X-Company-Code` 헤더, 상품 코드와 멱등성 키를 회사 코드와 묶은 유니크와 복합 PK, 다른 회사 상품은 404, 재고와 원장에는 회사 코드를 두지 않음 | #40 |

- 검토한 대안에는 명세서에 실제로 비교해 적은 것만 쓴다.

#### 4. README "AI 활용 방식"
실제로 레포에 있는 것만 쓴다.

| 항목 | 내용 |
|---|---|
| 역할 분담 | PM과 개발자 역할의 결정(이슈 범위, 명세서 승인, 머지)은 사람이, 실행(질문 도출, 명세서 초안, 구현, 테스트, PR)은 AI가 맡는다 (`docs/conventions/workflow.md`) |
| skills (`.claude/skills/`) | `requirement-refinement`(요구사항 → 이슈), `issue-workflow`(이슈 → 명세서 → PR 절차와 TC 추적 스크립트), `inventory-architecture`(모듈 배치와 코드 규칙, 템플릿). 각각 왜 만들었는지 |
| 빌드와 CI가 막는 것 | 모듈 경계는 Gradle 의존성이 컴파일 에러로, TC 누락은 `check-tc-coverage.sh`가, 테스트 실패는 CI가 막는다 |
| 사람이 검토한 것 | 명세서마다 설계 대안 비교, PR 리뷰로 바뀐 규칙(명세서 변경 이력에 남은 것) |

- 레포에 없는 것(CLAUDE.md, 레포 훅, ArchUnit)은 쓰지 않는다.

#### 5. 문서와 구현 불일치

점검 범위: `docs/` 아래 전부, `README.md`, `infra/**/README.md`, `.claude/skills/`. 링크는 모두 살아 있고, 명세서의 TC ID는 모두 테스트 코드에 있다.

**문서를 고친다 (이 이슈)**

| 문서 | 지금 적힌 것 | 실제 |
|---|---|---|
| `README.md` 모듈 표 | `support/flyway`: 공용 기초 데이터 | 기초 데이터는 없다. Flyway 실행 의존성과 마이그레이션 이미지(`Dockerfile`)만 있다 |
| `README.md` | Swagger에 "헤더 설명"이 있다 | 어노테이션이 없어 헤더 이름과 필수 여부만 나온다 |
| `README.md` 3, 8, 69행 | 가운뎃점 나열 | 쉼표로 |
| `infra/README.md:21` | "main/dev에 머지해서는 배포되지 않는다" | main push가 배포를 시작한다. "dev에 머지해서는"으로 |
| `infra/README.md:78` | `deploy: inventory [[ORCA_RICH_MD:...]]` (편집기 잔재) | `deploy: inventory <SHA>` |
| `infra/README.md:63` | `kubectl get jobs,pods -l app=inventory`로 Job 확인 | Job에는 `app` 라벨이 없고, 성공하면 hook 정책으로 지워진다 |
| `infra/helm/README.md:66` | "DB에는 Secret" (문장이 끊김) | 문장 완성 |
| `docs/conventions/git.md` | type `ci`, `deploy`, scope `api` | 실제 커밋은 CI와 배포에 `chore(infra)`를 쓰고, `api`는 `app` 모듈을 가리킨다. 규칙을 실제에 맞춘다 |
| `docs/conventions/workflow.md`, PR 템플릿 | ADR은 `docs/adr/` (아직 없음), 명세서 PR 계획 열 `PR, 범위, 포함 테스트 케이스` | ADR은 이 이슈에서 생긴다. PR 계획 열은 실제 명세서처럼 `PR, 브랜치, 범위, 검증`으로 |
| `docs/conventions/workflow.md`, `issue-workflow` skill | 명세서를 승인 전에 첫 PR 브랜치에 커밋하고 push | 명세서와 구현은 작업 트리에 두고 개발자가 본 뒤 커밋, push는 PR을 올릴 때 |
| `.claude/skills/inventory-architecture/SKILL.md` | Boot 버전 미정, app 의존성에 springdoc 없음, 패키지 배치에 idempotency, transaction, product 서비스 없음, db 테스트와 `@WebMvcTest`와 ArchUnit 언급, 체크리스트의 "스키마 변경은 flyway 모듈" | 지금 코드 기준으로 고친다. `references/` 아래 예시 파일(`build-setup.md`, `domain-templates.md`, `adapter-templates.md`)은 구조를 보여 주는 예시라 코드와 달라도 고치지 않는다 |

**닫힌 이슈의 명세서는 고치지 않는다**

명세서는 그 이슈를 개발하기 전에 승인한 계획이다. 이슈가 닫힌 뒤에 고치면 무엇을 계획했고 PR에서 무엇이 달라졌는지를 더 이상 비교할 수 없다. 그래서 아래 차이는 명세서에 손대지 않고, 지금 동작은 ADR과 README에 코드 기준으로 쓴다.

| 명세서 | 지금과 다른 내용 | 바꾼 이슈 |
|---|---|---|
| #1 | 모듈 경로(`app/api`, `inventory-domain`), prod Flyway와 Swagger, 루트 `compose.yaml`, `/actuator/health` 포트. `X-Request-Id` 앞뒤 공백을 잘라 받는 동작은 적혀 있지 않다 | #7, #17, #22 |
| #6 | CI job이 `build` 하나 | #23 |
| #7 | 응답 형식(ProblemDetail), `/api/actuator/health` | #20, #22 |
| #12, #13 | 멱등성 키를 원장 컬럼으로, 인덱스 `(product_id, created_at)`, 흐름에 멱등성 선처리와 커밋 후 이벤트 없음. #12의 "마이그레이션 잠금 대기도 3초에서 끊긴다"는 #17 이후 local과 test에서만 맞다 | #15, #17, #21, #22 |
| #15 | 62행 "어댑터가 행 수를 `boolean`으로"는 같은 명세 47행과 코드(`Optional<IdempotencyRecord>`)와 다르다 | 명세 안의 불일치 |
| #17 | 50행 "이미지를 레지스트리에 올리지 않는다" | #23 |
| #20 | 103행 "`SERVICE_UNAVAILABLE`이 아니라 `SERVICE_UNAVAILABLE`로"는 앞쪽이 `LOCK_TIMEOUT`인 오타 | 오타 |
| #23 | 129행 "플랫폼은 `linux/amd64`". 지금은 amd64와 arm64 둘 다 만든다 | #38 |

**코드를 고쳐야 한다 (새 이슈로 제안, 이 이슈에서는 고치지 않음)**
1. prod 마이그레이션에 잠금 대기 상한이 없다. flyway 이미지 접속에도 `lock_timeout`을 걸지, 명세 #12의 주장을 local 한정으로 줄일지 정해야 한다.
2. 출고 409 `INSUFFICIENT_STOCK`의 `message`에 내부 PK가 `productId=%d`로 나간다 (`Inventory.java:58`). 명세 #7은 `id`를 노출하지 않는다고 했다.
3. 명세 #15의 "`IdempotencyStoreAdapter`에 READ COMMITTED 전제를 주석으로 남긴다"가 코드에 없다. TC-21-02는 `createdAt`이 null이 아닌지만 본다. 둘 다 작아서 1이나 2와 묶어도 된다.

**규칙과 실제 관행이 다른 것** (`git.md`를 관행에 맞춘다)
- `git.md`는 "Squash merge만"이지만, dev 대상 PR은 모두 merge commit으로 합쳐졌다. 예: #36 → `4aee548`, 부모 2개.

#### 6. 처리 순서
1. 5의 문서 수정과 README (README는 다시 쓰면서 모듈 표와 가운뎃점을 함께 고친다)
2. ADR. 다 쓰면 README와 명세서 링크 자리에 ADR 링크를 더한다

### 검증 방법
문서 이슈라 테스트 케이스 대신 실행해서 확인한다.

| 대상 | 실행 | 기대 결과 |
|---|---|---|
| 완료 조건 1 | 새 폴더에 `git clone` 후 README의 실행 방법을 위에서부터 그대로 실행 (`./gradlew build`, `docker compose up -d --build`, `app/http/inventory.http`를 위에서부터 실행, `./load-test/run.sh load`) | 빌드와 테스트 통과, 예시 파일의 각 요청이 주석에 적은 상태 코드, 부하 테스트 정합성 확인 통과 |
| 완료 조건 2 | 3의 ADR 표와 `docs/adr/` 비교 | 12개 모두 있고, 각 ADR의 명세서 링크가 해당 절을 가리킨다 |
| 링크 | 추적되는 `.md`의 상대 링크와 앵커를 스크립트로 확인 | 깨진 링크 0 |
| Mermaid | push 후 GitHub에서 README 미리보기 | 모듈 그림이 렌더링된다 |
| 사실 일치 | `docs/ddl.sql`을 빈 PostgreSQL에 적용하고, README의 모듈 설명, 처리 순서, 요구사항 해석과 `app/http/inventory.http`의 경로, 헤더를 코드와 대조 | 경로, 상태 코드, 모듈 의존, 컬럼, 제약이 같다 |
| 처음 보는 사람 | 사전 맥락이 없는 서브에이전트에게 README만 주고 "무엇을, 왜, 어떻게 실행, 어떻게 검증"을 답하게 함 | 넷 다 README와 링크만으로 답한다. 막힌 곳은 보완 |
| 가운뎃점 | `git grep -n '·' -- ':!docs/specs' ':!.claude/skills/inventory-architecture/references' ':!*.sh'` | 0건 (닫힌 명세서, 예시 파일, 스크립트는 고치지 않는다) |

### PR 계획
| PR | 브랜치 | 범위 | 검증 |
|---|---|---|---|
| 1 | `docs/24-docs` | 5의 문서 수정, README, 명세서 (`Refs #24`) | 완료 조건 1, 사실 일치, 처음 보는 사람, 링크, 가운뎃점, Mermaid |
| 2 | `docs/24-adr` | ADR 12개와 목록, README와 `workflow.md`에서 ADR 링크 (`Closes #24`) | 완료 조건 2, 링크 |

### 결정 필요
- 없음. 닫힌 이슈의 명세서는 고치지 않음, 머지 방식은 `git.md`를 실제 관행(merge commit)에 맞춤, API는 `app/http/inventory.http`와 Swagger UI가 보여 주고 테이블은 `docs/ddl.sql`이 보여 줌, 명세서는 승인 전에 커밋하지 않도록 `workflow.md`와 `issue-workflow` skill을 고침

### 변경 이력
- 2026-10-17: 결정 필요 4개를 위 내용으로 확정. 명세서 커밋 시점을 PR 1 범위에 추가
- 2026-10-17: 닫힌 이슈의 명세서는 변경 이력도 포함해 고치지 않기로 바꿈. 승인한 계획과 구현을 비교할 수 있게 남기기 위해서. 지금 동작은 ADR, ERD, API 문서, README에 쓴다
- 2026-10-17: README를 먼저 쓰고 ERD, DDL 위치, API 요약을 README에 넣음. `docs/erd.md`, `docs/api.md`는 만들지 않음. PR을 둘로 합침
- 2026-10-17: 전체 DDL을 `docs/ddl.sql`로 모으고 README의 테이블 설명과 마이그레이션 목록을 이 파일 링크로 바꿈
- 2026-10-17: README에서 빠른 시작과 API 섹션을 뺌. 실행 명령은 "실행 방법" 한 곳에 두고, API 목록과 예시는 Swagger UI로
- 2026-10-17: README 구조 섹션을 모듈 그림, 모듈 설명, 프로젝트 구조 순으로. 요청 흐름 그림은 빼고 동시성 전제에 한 줄로. 데이터베이스 섹션은 ERD를 빼고 DDL 파일과 마이그레이션 폴더 링크만
- 2026-10-17: API 호출 예시 `app/http/inventory.http`를 추가하고 README에서 링크. 리뷰(처음 보는 사람)를 반영해 데이터베이스 절에 테이블 한 줄 설명, 처리 순서 제목을 입고와 출고로, 압축된 문장을 풀어 씀
- 2026-10-17: README에 배포 구조 (k8s) 절 추가
- 2026-10-17: dev에 머지된 #38(멀티 아키텍처 이미지), #40(회사별 분리), #41(마이그레이션 Job 제한 시간)을 반영. `docs/ddl.sql`을 마이그레이션을 이어 붙인 것에서 최종 스키마로 바꾸고 마이그레이션 적용 결과와 `pg_dump`로 비교, README와 예시 파일에 회사 코드, ADR 012 추가
- 2026-10-17: README를 개조식으로 바꿈. 배포 구조 그림은 Mermaid 대신 #23 명세의 그림을 씀
