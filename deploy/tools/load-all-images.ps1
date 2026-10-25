# load-all-images.ps1
# Load all required project images into Kind cluster using docker save + ctr import
# (kind load has issues with multi-platform images)

$ErrorActionPreference = "Continue"

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

$success = 0
$fail = 0

foreach ($img in $images) {
    $safe = $img -replace '[/:]', '_'
    $tarPath = "deploy/offline-images/${safe}.tar"

    Write-Host "`n--> [LOAD] $img" -ForegroundColor Cyan

    # Save to tar
    docker save $img -o $tarPath 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "    [FAIL] docker save: $img" -ForegroundColor Red
        $fail++
        continue
    }

    # Copy into Kind container
    docker cp $tarPath "kind-control-plane:/root/${safe}.tar" 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "    [FAIL] docker cp: $img" -ForegroundColor Red
        $fail++
        continue
    }

    # Import into containerd
    $result = docker exec kind-control-plane ctr -n k8s.io images import "/root/${safe}.tar" 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Host "    [FAIL] ctr import: $result" -ForegroundColor Red
        $fail++
    } else {
        Write-Host "    [OK] $img" -ForegroundColor Green
        $success++
    }

    # Cleanup tar inside container
    docker exec kind-control-plane rm -f "/root/${safe}.tar" 2>&1 | Out-Null

    # Cleanup tar on host
    Remove-Item -Force $tarPath -ErrorAction SilentlyContinue
}

Write-Host "`n=====================================================" -ForegroundColor Yellow
Write-Host "  Load hoan thanh: $success thanh cong, $fail that bai" -ForegroundColor Yellow
Write-Host "=====================================================" -ForegroundColor Yellow
