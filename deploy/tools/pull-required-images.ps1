# pull-required-images.ps1
# Pull all required images that failed to export from Kind cluster.
# These images will be loaded into the new Kind cluster later via `kind load`.

$ErrorActionPreference = "Continue"

$images = @(
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
    "ghcr.io/k8snetworkplumbingwg/multus-cni:snapshot",
    "curlimages/curl:latest"
)

$success = 0
$fail = 0

foreach ($img in $images) {
    Write-Host "`n--> [PULL] $img" -ForegroundColor Cyan
    docker pull $img 2>&1
    if ($LASTEXITCODE -eq 0) {
        Write-Host "    [OK] $img" -ForegroundColor Green
        $success++
    } else {
        Write-Host "    [FAIL] $img" -ForegroundColor Red
        $fail++
    }
}

Write-Host "`n=====================================================" -ForegroundColor Yellow
Write-Host "  Pull hoan thanh: $success thanh cong, $fail that bai" -ForegroundColor Yellow
Write-Host "=====================================================" -ForegroundColor Yellow
