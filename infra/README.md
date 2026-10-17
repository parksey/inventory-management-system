# infra


| 경로                                                       | 내용                                                                        |
| -------------------------------------------------------- | ------------------------------------------------------------------------- |
| `compose.yaml`, `nginx.conf`, `postgres/`, `monitoring/` | 로컬 docker compose 구성: DB, 마이그레이션, APP, nginx, 모니터링                        |
| `helm/`                                                  | k8s 차트와 관측 스택 values. minikube에 helm 구성: [helm/README.md](helm/README.md) |
| `argocd/application.yaml`                                | ArgoCD 설정                                                                 |


명령은 모두 레포 루트에서 실행한다.

## 배포 흐름 (GitOps)

```
main에 push → CI: 테스트, 차트 검사 → 이미지 빌드, Docker Hub에 push (태그 = 커밋 SHA)
            → values.yaml의 image.tag를 그 SHA로 바꿔 main에 커밋
            → ArgoCD가 변경 감지 → 마이그레이션 Job → 앱 Pod 롤링 교체
```

- 배포: main/dev에 머지해서는 배포되지 않는다
- CI는 클러스터에 접근하지 않는다. ArgoCD가 클러스터 안에서 git을 확인해 반영
- 마이그레이션 Job의 `helm.sh/hook: pre-install,pre-upgrade`는 ArgoCD의 PreSync hook으로 실행된다. 실패하면 Sync가 멈추고 기존 Pod는 그대로다
- ArgoCD는 Docker Hub의 이미지 사용. main에 한 번 머지되어 CI가 이미지를 올리기 전에는 values.yaml의 tag가 `initial` 

## 처음 한 번 설정

**1. GitHub** (Settings &gt; Secrets and variables &gt; Actions)

- 변수 `DOCKERHUB_USERNAME`: `parksey` (values.yaml의 image.repository 아이디와 같아야 한다)
- Secret `DOCKERHUB_TOKEN`: Docker Hub &gt; Account settings &gt; Personal access tokens에서 Read &amp; Write로 발급

**2. 클러스터와 관측 스택**: [helm/README.md](helm/README.md)의 1, 2단계

**3. ArgoCD 설치**

```bash
kubectl create namespace argocd
kubectl apply -n argocd --server-side --force-conflicts \
  -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml
kubectl wait -n argocd --for=condition=available deployment --all --timeout=5m
```

**4. 레포 등록** (레포가 비공개라 ArgoCD가 읽으려면 필요하다. 공개 레포면 건너뛴다)

```bash
# GitHub > Settings > Developer settings > Fine-grained tokens: 이 레포만, Contents 읽기 권한
read -s GITHUB_TOKEN                       # 토큰을 붙여 넣고 Enter (화면에 보이지 않는다)
kubectl create secret generic inventory-repo -n argocd \
  --from-literal=type=git \
  --from-literal=url=https://github.com/parksey/inventory-management-system.git \
  --from-literal=username=parksey --from-literal=password="$GITHUB_TOKEN"
kubectl label secret inventory-repo -n argocd argocd.argoproj.io/secret-type=repository
```

**5. DB와 계정 Secret**: [helm/README.md](helm/README.md)의 4단계와 같다 (`inventory` namespace에 Secret `inventory-db`, `database` namespace에 PostgreSQL)

**6. Application 등록**. 이후로는 git만 바꾸면 된다

```bash
kubectl apply -f infra/argocd/application.yaml
kubectl get applications -n argocd         # SYNC STATUS Synced, HEALTH STATUS Healthy
kubectl get jobs,pods -n inventory -l app=inventory
```

- helm/README.md 5단계로 같은 클러스터에 앱을 이미 설치했다면 먼저 `helm uninstall inventory -n inventory`로 지운다. 같은 이름의 리소스를 두 곳에서 관리하게 된다

**ArgoCD 화면** (사용자 admin)

```bash
kubectl get secret -n argocd argocd-initial-admin-secret -o jsonpath="{.data.password}" | base64 --decode ; echo
kubectl port-forward svc/argocd-server -n argocd 8443:443      # https://localhost:8443
```

## 운영

- 설정 변경(대수, DB 주소, 리소스): `infra/helm/inventory/values.yaml`을 고쳐 main에 머지한다. `kubectl`로 직접 바꾸면 selfHeal이 git 상태로 되돌린다
- 롤백: `deploy: inventory [[ORCA_RICH_MD:6960e3226ad13a1d3bd43ca347854bb4:inline-html:%3CSHA%3E]]` 커밋을 `git revert`해 main에 push한다. 스키마는 되돌아가지 않으므로 마이그레이션은 이전 버전 앱과도 동작하게 쓴다(컬럼 삭제, 이름 변경은 배포를 나눠서)
- 실패한 마이그레이션: `kubectl logs -n inventory job/inventory-migration`
- 배포가 멈췄을 때: 마이그레이션 Job은 5분 안에 끝나지 않으면 실패하고 기존 Pod는 유지된다. 원인은 `kubectl describe pod -n inventory -l job-name=inventory-migration`의 Events에서 본다

