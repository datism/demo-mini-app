# Script PowerShell tự động tải & lưu các Docker image ngoại tuyến từ máy Windows có Internet
# Cách chạy: Mở PowerShell và gõ: .\save_images.ps1

$ImageDir = "./offline-images"
if (!(Test-Path $ImageDir)) {
    New-Item -ItemType Directory -Path $ImageDir | Out-Null
}

Write-Host "====================================================================="
Write-Host "  Tải và Lưu các Base Images Ngoại tuyến (Môi trường Windows PowerShell)"
Write-Host "====================================================================="

# Danh sách các image cần thiết
$Images = @(
    "golang:1.21.9-alpine3.19",
    "alpine:3.19.1",
    "erlang:26.2.3",
    "erlang:26.2.3-slim",
    "registry.k8s.io/coredns/coredns:v1.14.2",
    "ghcr.io/k8snetworkplumbingwg/multus-cni:snapshot",
    "clickhouse/clickhouse-server:23.3-alpine",
    "qxip/qryn:3.2.38",
    "sipcapture/heplify-server:1.30",
    "grafana/grafana-oss:10.4.2",
    "postgres:14-alpine",
    "sipcapture/homer-app:1.1.4",
    "sipcapture/heplify:2.0.24",
    "fluent/fluent-bit:3.0",
    "redis:7.2.15-alpine",
    "python:3.10-slim",
    "busybox:1.28",
    "tailscale/tailscale:latest"
)

foreach ($img in $Images) {
    # Thay thế các ký tự / và : thành dấu gạch dưới để làm tên file tar
    $filename = $img -replace '[/:]', '_'
    $filepath = "$ImageDir/$filename.tar"
    
    if (Test-Path $filepath) {
        Write-Host "--> [BỎ QUA] $img đã được lưu tại $filepath"
    } else {
        Write-Host "--> [ĐANG TẢI] $img từ Internet..."
        docker pull $img
        Write-Host "--> [ĐANG LƯU] $img thành $filepath..."
        docker save -o $filepath $img
        Write-Host "--> [HOÀN THÀNH] Đã lưu thành công $img!"
    }
}

# Build and save custom postgres-patroni:local image
if (Test-Path "Dockerfile.patroni") {
    Write-Host "--> [BIÊN DỊCH & LƯU] postgres-patroni:local..."
    docker build -t postgres-patroni:local -f Dockerfile.patroni .
    docker save -o "$ImageDir/postgres-patroni_local.tar" postgres-patroni:local
}

Write-Host "====================================================================="
Write-Host "  Tất cả các image đã được lưu thành công tại thư mục: $ImageDir"
Write-Host "====================================================================="
