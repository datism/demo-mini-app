# deploy-calico-cluster.ps1
# Full automated deployment: Kind cluster + Calico + Multus + all workloads
# Run this script after Docker Desktop is ready

$ErrorActionPreference = "Continue"

# Refresh PATH for kind CLI
$env:Path = [System.Environment]::GetEnvironmentVariable("Path","Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path","User")

Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  PHASE 1: Tao Kind Cluster" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan

# Delete any existing cluster
.\kind.exe delete cluster --name kind 2>$null

# Create new cluster
Write-Host "--> Tao cluster (disableDefaultCNI, podSubnet=172.16.0.0/16)..."
.\kind.exe create cluster --config deploy/kind-config.yaml --name kind --image kindest/node:v1.27.3
if ($LASTEXITCODE -ne 0) { Write-Host "[FAIL] Kind create failed" -ForegroundColor Red; exit 1 }

# Fix kubeconfig port issue on Windows
.\deploy\tools\fix-kubeconfig.ps1

Write-Host "`n============================================" -ForegroundColor Cyan
Write-Host "  PHASE 2: Cai Calico CNI" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan

kubectl create -f https://raw.githubusercontent.com/projectcalico/calico/v3.29.3/manifests/tigera-operator.yaml
Start-Sleep -Seconds 5

# Create custom resources with correct CIDR
$calicoConfig = @"
apiVersion: operator.tigera.io/v1
kind: Installation
metadata:
  name: default
spec:
  calicoNetwork:
    ipPools:
    - cidr: 172.16.0.0/16
      encapsulation: VXLANCrossSubnet
      natOutgoing: Enabled
      nodeSelector: all()
---
apiVersion: operator.tigera.io/v1
kind: APIServer
metadata:
  name: default
spec: {}
"@
$calicoConfig | kubectl apply -f -

Write-Host "--> Doi Calico ready..."
kubectl wait --for=condition=Ready pods --all -n calico-system --timeout=180s

Write-Host "`n============================================" -ForegroundColor Cyan
Write-Host "  PHASE 3: Cai Multus + macvlan" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan

# Load Multus image
Write-Host "--> Load Multus image..."
docker save "ghcr.io/k8snetworkplumbingwg/multus-cni:snapshot" -o deploy/offline-images/multus.tar
docker cp deploy/offline-images/multus.tar kind-control-plane:/root/multus.tar
docker exec kind-control-plane ctr -n k8s.io images import /root/multus.tar
docker exec kind-control-plane rm -f /root/multus.tar

# Install Multus
kubectl apply -f deploy/k8s/install-multus.yaml
Start-Sleep -Seconds 5

# Install macvlan CNI binary
Write-Host "--> Cai macvlan CNI plugin..."
docker exec kind-control-plane sh -c "curl -sSL https://github.com/containernetworking/plugins/releases/download/v1.6.2/cni-plugins-linux-amd64-v1.6.2.tgz | tar -xz -C /opt/cni/bin ./macvlan"

# Create namespace + macvlan NAD
kubectl create namespace demo-mini-app
kubectl apply -f deploy/k8s/k8s-multus-macvlan.yaml

Write-Host "`n============================================" -ForegroundColor Cyan
Write-Host "  PHASE 4: Load images vao Kind" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan

$images = @(
    "erlang-sip:local",
    "go-mf:local",
    "postgres-patroni:local",
    "python:3.10-slim",
    "redis:7.2.15-alpine",
    "clickhouse/clickhouse-server:23.3-alpine",
    "grafana/grafana-oss:10.4.2",
    "postgres:14-alpine",
    "qxip/qryn:3.2.38",
    "sipcapture/heplify-server:1.30",
    "sipcapture/homer-app:1.1.4",
    "sipcapture/heplify:2.0.24",
    "fluent/fluent-bit:3.0",
    "registry.k8s.io/coredns/coredns:v1.14.2",
    "curlimages/curl:latest"
)
foreach ($img in $images) {
    $safe = $img -replace '[/:]', '_'
    Write-Host "--> Load $img" -ForegroundColor Cyan
    docker save $img -o "deploy/offline-images/${safe}.tar" 2>$null
    docker cp "deploy/offline-images/${safe}.tar" "kind-control-plane:/root/${safe}.tar" 2>$null
    docker exec kind-control-plane ctr -n k8s.io images import "/root/${safe}.tar" 2>$null
    docker exec kind-control-plane rm -f "/root/${safe}.tar" 2>$null
    Remove-Item -Force "deploy/offline-images/${safe}.tar" -ErrorAction SilentlyContinue
}

Write-Host "`n============================================" -ForegroundColor Cyan
Write-Host "  PHASE 5: Deploy workloads (replica=1)" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan

# Deploy in order
kubectl apply -f deploy/k8s/k8s-dns-server.yaml
Write-Host "--> Doi DNS Server ready..."
kubectl wait --for=condition=Ready pods -l app=dns-server -n demo-mini-app --timeout=120s

kubectl apply -f deploy/k8s/k8s-redis.yaml
kubectl apply -f deploy/k8s/k8s-deployment.yaml
kubectl apply -f deploy/k8s/k8s-telemetry-env.yaml
kubectl apply -f deploy/k8s/k8s-tailscale.yaml
kubectl apply -f deploy/k8s/k8s-fluent-bit.yaml
kubectl apply -f deploy/k8s/k8s-heplify-agent.yaml

Write-Host "`n--> Doi tat ca pods ready (timeout 5 phut)..."
kubectl wait --for=condition=Ready pods --all -n demo-mini-app --timeout=300s

Write-Host "`n============================================" -ForegroundColor Green
Write-Host "  HOAN THANH! Cluster da san sang." -ForegroundColor Green
Write-Host "============================================" -ForegroundColor Green
kubectl get pods -n demo-mini-app
