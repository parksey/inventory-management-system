# Helm


| 경로            | 내용                                                                                        |
| ------------- | ----------------------------------------------------------------------------------------- |
| `inventory/`  | 앱 차트: Deployment(기본 3대), Service, 배포마다 한 번 도는 마이그레이션 Job                                  |
| `monitoring/` | 공개 차트에 넣을 values: kube-prometheus-stack(Prometheus, Grafana), Loki, k8s-monitoring(Alloy) |


이 문서는 minikube에 helm으로 직접 띄워 보는 순서다. ArgoCD로 배포하는 흐름은 [../README.md](../README.md)를 본다.
명령은 모두 레포 루트에서 실행한다.

## 1. 클러스터

Docker Desktop &gt; Settings &gt; Resources &gt; Memory를 18GB 이상으로 먼저 늘린다.

```bash
minikube delete # 이미 있는 minikube는 처음 만든 메모리 크기를 유지하므로 지우고 새로 만든다
minikube start --memory=12288 --cpus=4
kubectl config current-context # minikube
kubectl get nodes # STATUS Ready
```



## 2. 관측 스택

앱보다 먼저 설치한다. 로그 수집기(Alloy)는 자기가 뜬 뒤에 생긴 로그만 읽으므로, 먼저 떠 있어야 앱 기동 로그부터 남는다.

```bash
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo add grafana https://grafana.github.io/helm-charts
helm repo update

# Prometheus, Grafana. 대시보드는 compose와 같은 JSON을 --set-file로 넣는다
helm upgrade --install prometheus prometheus-community/kube-prometheus-stack --version 92.9.0 \
  -n monitoring --create-namespace \
  -f infra/helm/monitoring/kube-prometheus-stack.yaml \
  --set-file grafana.dashboards.inventory.inventory.json=infra/monitoring/grafana/dashboards/inventory.json

helm upgrade --install loki grafana/loki --version 7.3.0 \
  -n monitoring -f infra/helm/monitoring/loki.yaml

helm upgrade --install k8s-monitoring grafana/k8s-monitoring --version 4.5.3 \
  -n monitoring -f infra/helm/monitoring/k8s-monitoring.yaml

kubectl get pods,pvc -n monitoring         # Pod 모두 Running, PVC 두 개(Prometheus, Loki) Bound
```

## 3. 앱 이미지

```bash
minikube image build -t inventory-app:local -f app/Dockerfile .
minikube image build -t inventory-flyway:local -f support/flyway/Dockerfile .
minikube image ls | grep inventory         # 두 이미지
```

- minikube 노드 안에서 빌드하므로 레지스트리에 올리지 않아도 클러스터가 바로 쓴다. 앱 이미지는 안에서 Gradle 빌드를 해서 몇 분 걸린다

## 4. DB와 계정 Secret

```bash
kubectl create namespace inventory
kubectl create namespace database

# 비밀번호는 Secret에 한 번 만들고, DB에는 Secret
kubectl create secret generic inventory-db -n inventory \
  --from-literal=username=inventory --from-literal=password="$(openssl rand -hex 16)"
DB_PASSWORD=$(kubectl get secret inventory-db -n inventory -o jsonpath='{.data.password}' | base64 --decode)
echo ${#DB_PASSWORD}

kubectl run postgres -n database --image=postgres:16-alpine --port=5432 \
  --env=POSTGRES_DB=inventory --env=POSTGRES_USER=inventory --env=POSTGRES_PASSWORD="$DB_PASSWORD"
kubectl expose pod postgres -n database --port=5432

kubectl get pods,svc -n database           # postgres Running, Service postgres
```

## 5. 앱 차트

```bash
helm upgrade --install inventory infra/helm/inventory -n inventory \
  --set image.repository=inventory-app --set migration.image.repository=inventory-flyway \
  --set image.tag=local --set image.pullPolicy=Never

kubectl get pods -n inventory -l app=inventory -w    # 3대 READY 1/1 (Ctrl+C로 종료)
```

- 설치하면 마이그레이션 Job(Helm hook)이 먼저 돌고, 성공해야 앱 Pod를 만든다. 성공한 Job은 지워진다
- 마이그레이션이 실패하면 Job이 남는다: `kubectl logs -n inventory job/inventory-migration`
- 릴리스 이름 `inventory`는 바꾸지 않는다. Pod 라벨 `app=inventory`를 관측 스택이 찾는다

## 6. 확인

```bash
# API (Service로 연결. port-forward는 Pod 하나에만 붙으므로 분산은 확인하지 못한다)
kubectl port-forward svc/inventory -n inventory 8080:80
curl -X POST localhost:8080/api/v1/inventory/SKU-001/inbound \
  -H 'Content-Type: application/json' -H 'X-Company-Code: COMPANY-A' -H 'X-User-Id: user1' -H "Idempotency-Key: $(uuidgen)" \
  -d '{"quantity":10}'
curl -H 'X-Company-Code: COMPANY-A' localhost:8080/api/v1/inventory/SKU-001

# Grafana (admin / 아래 비밀번호), 대시보드 "재고 서비스"
kubectl get secret -n monitoring prometheus-grafana -o jsonpath="{.data.admin-password}" | base64 --decode ; echo
kubectl port-forward svc/prometheus-grafana -n monitoring 3000:80        # http://localhost:3000

# Prometheus: Status > Targets의 inventory job에 앱 Pod 3개가 UP
kubectl port-forward svc/prometheus-kube-prometheus-prometheus -n monitoring 9090:9090   # http://localhost:9090

# 롤링 재시작: 새 Pod가 Ready가 된 뒤 기존 Pod가 하나씩 내려간다
kubectl rollout restart deployment/inventory -n inventory
kubectl rollout status deployment/inventory -n inventory
```

## 정리

```bash
helm uninstall inventory -n inventory
helm uninstall k8s-monitoring loki prometheus -n monitoring
minikube delete                            # 클러스터와 PVC 데이터까지 모두 지운다
```

