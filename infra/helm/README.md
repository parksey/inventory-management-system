# Helm

| 경로 | 내용 |
|---|---|
| `inventory/` | 앱 차트: Deployment(기본 3대), Service, 배포마다 한 번 도는 마이그레이션 Job |
| `../argocd/application.yaml` | ArgoCD Application: 이 레포 main의 `infra/helm/inventory`를 `inventory` namespace에 배포 |
| `monitoring/` | 공개 차트에 넣을 values: kube-prometheus-stack(Prometheus, Grafana), Loki, Alloy |

운영 DB(PostgreSQL)는 이미 있다고 보고 차트에 넣지 않는다.

## 배포 흐름 (GitOps)

```
main에 push → CI: 테스트, 차트 검사 → 이미지 빌드, push (태그 = 커밋 SHA)
            → values.yaml의 image.tag를 그 SHA로 바꿔 main에 커밋
            → ArgoCD가 변경 감지 → 마이그레이션 Job → 앱 Pod 롤링 교체
```
- 배포 = main에 머지. 롤백 = values.yaml의 tag를 되돌리는 커밋 (git revert)
- CI는 클러스터에 접근하지 않는다. 클러스터 반영은 ArgoCD가 한다
- 마이그레이션 Job의 `helm.sh/hook: pre-install,pre-upgrade`는 ArgoCD가 Sync 전에 실행하는 hook(PreSync)으로 그대로 쓴다. 실패하면 Sync가 멈추고 기존 Pod는 그대로다

## 처음 한 번 설정

```bash
# 1. GitHub: Settings > Secrets and variables > Actions
#    vars.DOCKERHUB_USERNAME, secrets.DOCKERHUB_TOKEN
#    values.yaml의 <dockerhub-user>도 같은 아이디로 바꾼다

# 2. ArgoCD 설치
kubectl create namespace argocd
kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml

# 3. 레포가 비공개면 ArgoCD가 읽을 수 있게 등록 (읽기 권한만 있는 GitHub 토큰)
kubectl create secret generic inventory-repo -n argocd \
  --from-literal=type=git --from-literal=url=https://github.com/parksey/inventory-management-system.git \
  --from-literal=username=<github 아이디> --from-literal=password=<토큰>
kubectl label secret inventory-repo -n argocd argocd.argoproj.io/secret-type=repository

# 4. 앱 namespace와 DB 계정 Secret (차트는 이름만 참조한다)
kubectl create namespace inventory
kubectl create secret generic inventory-db -n inventory --from-literal=username=<계정> --from-literal=password=<비밀번호>

# 5. Application 등록. 이후로는 git만 바꾸면 된다
kubectl apply -f infra/argocd/application.yaml

# 확인
kubectl get applications -n argocd
kubectl get jobs,pods -n inventory -l app=inventory
```

- 대수, DB 주소 등 설정 변경도 `values.yaml`을 고쳐 머지한다. `kubectl`로 직접 바꾸면 selfHeal이 git 상태로 되돌린다
- 실패한 마이그레이션: `kubectl logs -n inventory job/inventory-migration`

## 관측 스택 설치 (compose `--profile monitoring`과 같은 구성)

```bash
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo add grafana https://grafana.github.io/helm-charts

helm install prometheus prometheus-community/kube-prometheus-stack --version 92.9.0 -n monitoring --create-namespace -f infra/helm/monitoring/kube-prometheus-stack.yaml
helm install loki grafana/loki --version 7.3.0 -n monitoring -f infra/helm/monitoring/loki.yaml
helm install alloy grafana/alloy --version 1.13.1 -n monitoring -f infra/helm/monitoring/alloy.yaml

# 대시보드: compose와 같은 파일. grafana_dashboard=1 라벨이 붙은 ConfigMap을 Grafana가 읽어 간다
kubectl create configmap inventory-dashboard -n monitoring --from-file=infra/monitoring/grafana/dashboards/inventory.json
kubectl label configmap inventory-dashboard -n monitoring grafana_dashboard=1

# Grafana 접속 (admin / 아래 비밀번호), 대시보드 "재고 서비스"
kubectl get secret -n monitoring prometheus-grafana -o jsonpath="{.data.admin-password}" | base64 --decode ; echo
kubectl port-forward svc/prometheus-grafana 3000:80 -n monitoring
```

| compose | k8s |
|---|---|
| Prometheus `dns_sd_configs`로 app 찾기 | `additionalScrapeConfigs`의 inventory job이 `app=inventory` Pod 찾기 |
| Alloy가 Docker 소켓으로 컨테이너 로그 읽기 | Alloy가 k8s API로 `app=inventory` Pod 로그 읽기 |
| Loki, Grafana 컨테이너 | Loki 차트(단일 바이너리), kube-prometheus-stack의 Grafana |

## 로컬 확인 (minikube, ArgoCD 없이 차트만)
ArgoCD는 git의 main을 읽으므로, 로컬에서 만든 이미지로 차트만 확인할 때는 helm으로 직접 설치한다.
```bash
minikube start --memory=4096 --cpus=4
minikube image build -t inventory-app:local -f app/Dockerfile .
minikube image build -t inventory-flyway:local -f support/flyway/Dockerfile .
# 확인용 PostgreSQL 하나
kubectl run postgres --image=postgres:16-alpine --env=POSTGRES_DB=inventory --env=POSTGRES_USER=inventory --env=POSTGRES_PASSWORD=inventory --port=5432
kubectl expose pod postgres --port=5432
kubectl create secret generic inventory-db --from-literal=username=inventory --from-literal=password=inventory
helm install inventory infra/helm/inventory --set image.tag=local --set image.pullPolicy=Never \
  --set image.repository=inventory-app --set migration.image.repository=inventory-flyway --set replicaCount=2
```
