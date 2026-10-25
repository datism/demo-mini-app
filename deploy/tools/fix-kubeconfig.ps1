# fix-kubeconfig.ps1
# Tu dong cap nhat kubeconfig sau khi reboot.
# Kind cluster gan port ngau nhien moi lan Docker khoi dong lai,
# script nay doc port thuc te tu Docker va ghi vao kubeconfig.

$ErrorActionPreference = "Stop"

# Lay port mapping thuc te cua kind-control-plane container
$portMapping = docker port kind-control-plane 6443 2>$null
if (-not $portMapping) {
    Write-Host "[ERROR] Container 'kind-control-plane' khong ton tai hoac chua chay." -ForegroundColor Red
    Write-Host "        Hay khoi dong Docker Desktop truoc." -ForegroundColor Yellow
    exit 1
}

# Trich xuat port tu ket qua (vd: "0.0.0.0:58564" hoac "127.0.0.1:58564")
$actualPort = ($portMapping -split ":")[-1]

# Doc port hien tai trong kubeconfig
$currentServer = kubectl config view --minify -o jsonpath='{.clusters[0].cluster.server}' 2>$null
$currentPort = ($currentServer -split ":")[-1]

if ($actualPort -eq $currentPort) {
    Write-Host "[OK] Kubeconfig da dung port $actualPort. Khong can thay doi." -ForegroundColor Green
} else {
    Write-Host "[FIX] Port thay doi: $currentPort -> $actualPort" -ForegroundColor Yellow
    kubectl config set-cluster kind-kind --server="https://127.0.0.1:$actualPort" | Out-Null
    Write-Host "[OK] Da cap nhat kubeconfig thanh cong: https://127.0.0.1:$actualPort" -ForegroundColor Green
}

# Kiem tra ket noi
Write-Host ""
Write-Host "Dang kiem tra ket noi cluster..." -ForegroundColor Cyan
kubectl cluster-info 2>&1
