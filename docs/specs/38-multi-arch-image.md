# 기능 명세 — CI 이미지를 amd64, arm64로 빌드 (#38)

### 요구사항 해석
- CI(`image` job)는 GitHub의 ubuntu 러너(amd64)에서 `docker/build-push-action`으로 이미지를 만든다. 플랫폼을 지정하지 않아 러너와 같은 linux/amd64 이미지만 올라간다.
- Apple Silicon Mac의 minikube 노드는 linux/arm64라, 이 이미지에서 실행할 버전을 찾지 못한다 (`no matching manifest for linux/arm64/v8`). 로컬에서 `minikube image build`로 만든 이미지는 노드 안에서 빌드되어 arm64라 이 문제가 드러나지 않았다.
- 앱, flyway 이미지를 linux/amd64와 linux/arm64 두 플랫폼으로 만들어, 같은 태그 하나로 어느 노드에서든 맞는 쪽을 받게 한다.
- 이미지 빌드 시간은 지금(앱 이미지 job 약 2분)과 비교해 크게 늘지 않아야 한다.

### 설계

**멀티 플랫폼 이미지**
태그 하나(`inventory-app:<SHA>`)가 플랫폼별 이미지 목록(manifest list)을 가리킨다. 노드는 자기 플랫폼에 맞는 이미지를 골라 받는다. 태그 형식, values.yaml 갱신, ArgoCD 흐름은 그대로다.

**변경 파일**

| 파일 | 변경 |
|---|---|
| `.github/workflows/ci.yml` `image` job | `docker/setup-qemu-action@v4` 추가, `build-push-action`에 `platforms: linux/amd64,linux/arm64` |
| `app/Dockerfile` | 빌드 단계를 `FROM --platform=$BUILDPLATFORM amazoncorretto:21-alpine AS build`로 |
| `support/flyway/Dockerfile` | 변경 없음 (베이스 `flyway/flyway:12.4.0`이 amd64, arm64를 모두 제공하고, 파일 복사만 한다) |

**앱 이미지의 빌드 단계를 러너 플랫폼으로 고정하는 이유**
- 플랫폼만 추가하면 arm64 이미지의 모든 단계가 QEMU 에뮬레이션으로 돈다. 빌드 단계의 Gradle 빌드(컴파일, bootJar)가 에뮬레이션되면 몇 배 느려진다.
- jar는 플랫폼과 상관없는 파일이다. 빌드 단계를 `$BUILDPLATFORM`(러너, amd64)으로 고정하면 Gradle 빌드는 한 번만 네이티브로 돌고, 두 플랫폼의 실행 단계가 같은 jar를 복사한다.
- 실행 단계는 플랫폼별로 만든다. arm64 쪽에서 에뮬레이션되는 것은 `adduser` 한 줄과 파일 복사뿐이다.
- 베이스 `amazoncorretto:21-alpine`은 amd64, arm64를 모두 제공한다.

| 방식 | 동작 | 판단 |
|---|---|---|
| **빌드 단계는 러너 플랫폼, 실행 단계만 플랫폼별 (선택)** | Gradle 빌드 1번(네이티브) + 실행 단계 2개 | 빌드 시간이 거의 늘지 않는다. Dockerfile 한 줄과 CI 두 줄 |
| 모든 단계를 QEMU로 | Gradle 빌드 2번, 그중 arm64는 에뮬레이션 | 변경은 CI 두 줄뿐이지만 앱 이미지 빌드가 수 배 느려진다 |
| arm64 러너로 따로 빌드해 manifest 합치기 | 플랫폼마다 job을 나눠 네이티브 빌드 후 목록으로 묶는다 | 가장 빠르지만 job과 합치는 단계가 늘고, 무료 플랜의 비공개 레포에서는 GitHub 제공 arm64 러너를 쓸 수 없다 |

### 비즈니스 규칙
- R1. main push로 올라간 앱, flyway 이미지 태그는 linux/amd64, linux/arm64 이미지를 모두 가리킨다.
- R2. arm64 노드에서 ArgoCD 배포가 마이그레이션 Job 완료 후 앱 Pod 3대 Ready까지 진행된다.
- R3. 앱 이미지 job 시간이 지금(약 2분)의 두 배를 넘지 않는다.

### 예외 케이스
| 상황 | 결과 |
|---|---|
| 베이스 이미지가 한 플랫폼만 제공 | 빌드가 실패한다. 지금 두 베이스 모두 두 플랫폼을 제공함을 확인했다 |

### 검증 방법
CI 설정과 Dockerfile 변경이라 테스트 코드 대신 실행으로 확인한다.

| 규칙 | 실행 | 기대 결과 |
|---|---|---|
| R1 (머지 전) | `docker buildx build --platform linux/amd64,linux/arm64 -f app/Dockerfile .` 와 flyway도 같은 명령 | 두 플랫폼 모두 빌드 성공 |
| R1 (머지 후) | `docker buildx imagetools inspect parksey/inventory-app:<SHA>` (flyway도) | `linux/amd64`, `linux/arm64` 두 줄 |
| R2 | 진행 중인 sync를 중단(`argocd app terminate-op inventory`)하고 다시 sync. `kubectl get jobs,pods -n inventory` | Job 완료, 앱 Pod 3대 `1/1`, Application `Synced`/`Healthy` |
| R3 | main CI의 `image (inventory-app, ...)` job 시간 | 4분 이내 |

R2에서 sync를 중단하는 이유: 지금 진행 중인 sync는 arm64 이미지가 없는 revision에 고정된 채 마이그레이션 Job을 기다리고 있어서, 새 tag가 main에 들어와도 저절로 넘어가지 않는다.

### PR 계획
| PR | 브랜치 | 범위 | 검증 |
|---|---|---|---|
| 1 | `fix/38-multi-arch-image` | 명세서, CI `image` job, `app/Dockerfile` (`Closes #38`) | R1(머지 전). R1(머지 후), R2, R3는 dev → main 머지 후 |

### 결정 필요
- 없음

### 변경 이력
