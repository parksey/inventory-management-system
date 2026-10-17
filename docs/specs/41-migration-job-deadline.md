# 기능 명세 — 마이그레이션 Job이 이미지를 못 받으면 제한 시간 뒤 실패 (#41)

### 요구사항 해석
- 마이그레이션 Job(Helm hook, ArgoCD PreSync)은 이미지를 받지 못하면 `ImagePullBackOff`로 기다린다. k8s는 이것을 실패로 세지 않아 `backoffLimit: 1`도 소용이 없고, Job은 끝나지 않는다.
- ArgoCD는 hook이 끝나야 sync를 끝낸다. sync가 끝나지 않으면 main에 새 tag가 들어와도 새 revision으로 넘어가지 못한다.
- sync가 실패해도 ArgoCD는 같은 revision으로 재시도한다. 이 클러스터의 ArgoCD(v3.5)는 재시도 횟수를 정하지 않으면 5번 재시도한다(실행 중이던 sync의 `operation.retry.limit: 5`로 확인). 재시도마다 Job을 다시 만들고 다시 기다린다.
- 그래서 두 가지를 정한다: Job이 기다릴 수 있는 최대 시간, 실패한 sync를 같은 revision으로 몇 번 다시 해 볼지.
- 정상적으로 오래 걸리는 경우(DB가 막 재시작해 접속을 몇 번 다시 시도하는 경우)는 그 시간 안에 끝나야 한다.

### 설계

**변경 파일**

| 파일 | 변경 |
|---|---|
| `infra/helm/inventory/templates/migration-job.yaml` | `activeDeadlineSeconds: 300`, flyway 인자에 `-connectRetriesInterval=10` |
| `infra/argocd/application.yaml` | `syncPolicy.retry.limit: 1` |
| `infra/README.md` | 운영에 "배포가 멈췄을 때" 항목 |

**1. Job 제한 시간 5분** (`activeDeadlineSeconds: 300`)
- Job이 시작하고 5분이 지나면 k8s가 Pod를 멈추고 Job을 `DeadlineExceeded`로 실패시킨다. 이미지를 못 받아 기다리는 시간도 여기에 포함된다.
- Job이 실패하면 hook이 실패하고 sync도 실패로 끝난다. Deployment는 바뀌지 않으므로 기존 앱 Pod가 그대로 서비스한다.

**2. DB 접속 재시도 간격 상한 10초** (`-connectRetriesInterval=10`)
- 지금은 `-connectRetries=10`만 있어서 flyway가 접속 실패 시 1, 2, 4, 8, 16, 32, 64, 120, 120, 120초를 기다린다. 다 합치면 약 8분이라 5분 제한 안에 들어가지 않는다.
- 간격 상한을 10초로 두면 1, 2, 4, 8, 10, 10, 10, 10, 10, 10초, 합 약 75초다. DB가 1분 남짓 늦게 떠도 마이그레이션이 성공하고, 그보다 오래 DB가 없으면 flyway가 먼저 실패로 끝난다.
- 정상 마이그레이션 자체는 몇 초다. 5분은 이미지 받기(수십 초)와 접속 재시도(최대 약 75초)를 넉넉히 덮는다.

| 제한 시간 | 이미지를 못 받을 때 | 정상인데 느릴 때 |
|---|---|---|
| 2분 | 빨리 실패한다 | 처음 이미지 받기 + DB 재시도가 겹치면 정상 배포도 실패할 수 있다 |
| **5분 (선택)** | 5분 뒤 실패 | 위 경우도 여유 있게 끝난다 |
| 15분 | 15분 동안 배포가 멈춘다 | 여유는 크지만 재시도까지 합치면 30분 넘게 묶인다 |

**3. ArgoCD 재시도 1번** (`syncPolicy.retry.limit: 1`)
- 실패한 sync는 같은 revision으로 다시 시도한다. 재시도가 다 끝나야 다음 자동 sync가 최신 revision(새 tag)으로 시작한다.
- 재시도 1번이면 새 tag로 넘어가기까지 최대 약 13분이다: 첫 시도 5분 + 재시도 5분 + ArgoCD의 git 확인 주기 3분.

| 재시도 | 새 revision으로 넘어가기까지 (최대) | 일시적 실패(DB 재시작 중 등)에 대한 대응 |
|---|---|---|
| 기본값 5번 | 약 33분 | 5번 다시 해 본다 |
| **1번 (선택)** | 약 13분 | 한 번 다시 해 본다. DB 재시도 75초와 합쳐 몇 분간의 일시적 문제는 넘긴다 |
| 0번 | 약 8분 | 일시적 실패에도 바로 포기하고, 같은 revision은 다시 자동 sync되지 않아 사람이 Sync를 눌러야 한다 |

**4. README**
`infra/README.md` 운영 항목에 적는다: 이미지를 못 받으면 5분 뒤 sync가 실패하고 기존 Pod는 유지된다. 원인(`kubectl describe pod`의 이미지 에러)을 고쳐 main에 반영하면 다시 배포된다. 바로 다시 하려면 ArgoCD 화면의 Sync.

### 비즈니스 규칙
- R1. 받을 수 없는 이미지로 배포하면 마이그레이션 Job이 5분 뒤 실패로 끝나고, 기존 앱 Pod는 그대로 Ready다.
- R2. DB가 늦게 떠도 약 75초 안에 접속되면 마이그레이션은 성공한다.
- R3. sync가 실패한 뒤 새 원하는 상태(새 tag)가 들어오면 Terminate 없이 새 상태로 sync가 시작된다.

### 예외 케이스
| 상황 | 결과 |
|---|---|
| 마이그레이션 SQL 자체가 실패 | 지금과 같다. flyway가 실패로 끝나고 `backoffLimit: 1`로 한 번 더 시도한 뒤 Job 실패 |
| 이미 실행 중인 sync (이 변경 전에 시작된 것) | 옛 설정으로 만들어진 Job이라 제한 시간이 없다. 이번 배포에서 한 번은 Terminate가 필요하다 |

### 검증 방법
k8s와 ArgoCD 설정이라 테스트 코드 대신 minikube에서 실행으로 확인한다.

| 규칙 | 실행 | 기대 결과 |
|---|---|---|
| - | CI `helm` job (lint, template, kubeconform) | 통과. 렌더링 결과에 `activeDeadlineSeconds: 300`, `-connectRetriesInterval=10` |
| R1 | 빈 namespace에 `helm install deadline-check infra/helm/inventory -n deadline-check --set image.tag=does-not-exist --timeout 10m` | 약 5분 뒤 helm이 `DeadlineExceeded`로 실패. Job 상태 Failed |
| R2 | DB Pod를 지운 직후 마이그레이션 Job 실행 (Job이 DB 재연결을 기다리는 동안 DB를 다시 띄움) | flyway가 재시도 끝에 접속해 Job 완료 |
| R3 | 이 브랜치를 가리키는 임시 Application을 `image.tag=does-not-exist` 파라미터로 만들고, sync가 실패한 뒤(약 10분) 파라미터를 다른 tag로 바꾼다 | Terminate 없이 새 sync가 시작되고, 새 Job의 이미지가 바뀐 tag다 |

### PR 계획
| PR | 브랜치 | 범위 | 검증 |
|---|---|---|---|
| 1 | `fix/41-migration-job-deadline` | 명세서, Job 제한 시간과 flyway 재시도 간격, Application 재시도, README (`Closes #41`) | CI, R1, R2, R3 |

### 결정 필요
- 없음

### 변경 이력
