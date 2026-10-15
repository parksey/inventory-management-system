# 기능 명세 — push, PR마다 빌드와 테스트 자동 실행 (#6)

### 요구사항 해석
- dev, main에 push하거나 PR을 열면(새 커밋 push 포함) GitHub Actions가 `./gradlew build`를 실행한다. build에는 전체 테스트(Testcontainers PostgreSQL 포함)가 들어 있다.
- 실패한 테스트는 로그를 열지 않아도 PR의 Checks 화면에서 이름과 실패 메시지로 보인다.
- 같은 PR에 새 커밋이 오면 이전 실행은 취소하고 마지막 것만 돈다.
- Gradle wrapper jar가 공식 배포본과 같은지 확인한다.

### 설계

**파일**: `.github/workflows/ci.yml` 하나

| 항목 | 내용 |
|---|---|
| 트리거 | `push` (브랜치 dev, main), `pull_request` (대상 브랜치 dev, main) |
| 실행 환경 | `ubuntu-latest` (Docker가 기본 설치돼 있어 Testcontainers가 바로 동작) |
| Job | `build` 하나. Checks에 `CI / build`로 표시 |
| 중복 실행 취소 | 같은 PR에 새 커밋이 오면 돌고 있던 이전 실행을 취소한다. dev, main push는 커밋마다 결과가 남아야 해서 취소하지 않는다 |
| 권한 | `contents: read`, `checks: write` (테스트 결과를 Checks에 쓰기 위해) |

**단계**

| 순서 | 단계 | 사용 | 하는 일 |
|---|---|---|---|
| 1 | 코드 받기 | `actions/checkout@v7` | |
| 2 | JDK 21 | `actions/setup-java@v6` (Amazon Corretto) | toolchain과 같은 버전 |
| 3 | Gradle 준비 | `gradle/actions/setup-gradle@v6` | Gradle 캐시, wrapper 검증(`validate-wrappers: true`) |
| 4 | 빌드와 테스트 | `./gradlew build` | |
| 5 | 테스트 결과 표시 | `mikepenz/action-junit-report@v6` (`if: always()`) | `**/build/test-results/test/TEST-*.xml`을 읽어 실패 테스트를 Checks와 코드 줄에 표시 |

### 비즈니스 규칙
- R1. dev, main 대상 PR과 dev, main push에서 빌드와 전체 테스트가 실행된다.
- R2. 테스트가 하나라도 실패하면 CI가 실패하고, 실패한 테스트 이름과 메시지가 PR Checks에 보인다.
- R3. 같은 PR에 연달아 push하면 이전 실행은 취소된다.
- R4. Gradle wrapper jar가 공식 체크섬과 다르면 CI가 실패한다.

### 예외 케이스
| 상황 | 결과 |
|---|---|
| 테스트 결과 파일이 없음 (컴파일 실패 등) | 5단계는 결과 없음으로 넘어가고, CI는 4단계 실패로 표시 |

### 검증 방법
CI 설정은 코드 테스트로 만들 수 없어 실제 실행으로 확인한다.

| 대상 규칙 | 실행 | 기대 결과 |
|---|---|---|
| R1 | 이 PR을 dev 대상으로 연다 | `CI / build`가 실행되고 성공, 테스트 수가 요약에 표시 |
| R2 | 테스트를 일부러 깨뜨린 커밋으로 확인용 draft PR을 열고, 확인 후 닫는다 | `CI / build` 실패, 깨진 테스트 이름과 메시지가 Checks와 해당 줄에 표시 |
| R3 | 확인용 PR에 커밋 두 개를 연달아 push | 앞 실행이 `Cancelled` |
| R4 | (실패 재현은 하지 않음) 4단계 로그 확인 | wrapper 검증 통과 로그 |
| R1 | 머지 후 dev push 실행 확인 | dev 커밋에 ✅ |

### PR 계획
| PR | 브랜치 | 범위 | 검증 |
|---|---|---|---|
| 1 | `chore/6-github-actions` | `ci.yml`, 명세서 (`Closes #6`) | R1 ~ R4 검증 방법 |

### 결정 필요
- 없음

### 변경 이력
