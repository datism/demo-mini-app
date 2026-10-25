# export-kind-images.ps1
# Export all required images from Kind cluster container to host Docker

$ErrorActionPreference = "Continue"

$exportDir = "deploy/offline-images"
if (!(Test-Path $exportDir)) {
    New-Item -ItemType Directory -Path $exportDir | Out-Null
}

# Map of image references as they exist inside Kind (containerd namespace k8s.io)
$images = @(
    @{ ref = "docker.io/library/python:3.10-slim";                         tag = "python:3.10-slim" },
    @{ ref = "docker.io/library/redis:7.2.15-alpine";                      tag = "redis:7.2.15-alpine" },
    @{ ref = "docker.io/clickhouse/clickhouse-server:23.3-alpine";         tag = "clickhouse/clickhouse-server:23.3-alpine" },
    @{ ref = "docker.io/grafana/grafana-oss:10.4.2";                       tag = "grafana/grafana-oss:10.4.2" },
    @{ ref = "docker.io/library/postgres:14-alpine";                       tag = "postgres:14-alpine" },
    @{ ref = "docker.io/qxip/qryn:3.2.38";                                tag = "qxip/qryn:3.2.38" },
    @{ ref = "docker.io/sipcapture/heplify-server:1.30";                   tag = "sipcapture/heplify-server:1.30" },
    @{ ref = "docker.io/sipcapture/homer-app:1.1.4";                       tag = "sipcapture/homer-app:1.1.4" },
    @{ ref = "docker.io/sipcapture/heplify:2.0.24";                       tag = "sipcapture/heplify:2.0.24" },
    @{ ref = "docker.io/fluent/fluent-bit:3.0";                            tag = "fluent/fluent-bit:3.0" },
    @{ ref = "registry.k8s.io/coredns/coredns:v1.14.2";                   tag = "registry.k8s.io/coredns/coredns:v1.14.2" },
    @{ ref = "ghcr.io/k8snetworkplumbingwg/multus-cni:snapshot";           tag = "ghcr.io/k8snetworkplumbingwg/multus-cni:snapshot" },
    @{ ref = "docker.io/curlimages/curl:latest";                           tag = "curlimages/curl:latest" }
)

$success = 0
$fail = 0

foreach ($img in $images) {
    $safe = $img.tag -replace '[/:]', '_'
    $tarFile = "$exportDir/${safe}.tar"
    $containerTar = "/tmp/${safe}.tar"

    Write-Host "`n--> [EXPORT] $($img.ref)" -ForegroundColor Cyan

    # Export from containerd inside Kind container
    $result = docker exec kind-control-plane ctr -n k8s.io images export $containerTar $img.ref 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Host "    [FAIL] ctr export failed: $result" -ForegroundColor Red
        $fail++
        continue
    }

    # Copy tar from Kind container to host
    docker cp "kind-control-plane:${containerTar}" $tarFile 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "    [FAIL] docker cp failed" -ForegroundColor Red
        $fail++
        continue
    }

    # Load into host Docker
    docker load -i $tarFile 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "    [FAIL] docker load failed" -ForegroundColor Red
        $fail++
        continue
    }

    # Clean up tar inside Kind container
    docker exec kind-control-plane rm -f $containerTar 2>&1 | Out-Null

    Write-Host "    [OK] Exported and loaded: $($img.tag)" -ForegroundColor Green
    $success++
}

Write-Host "`n=====================================================" -ForegroundColor Yellow
Write-Host "  Export hoan thanh: $success thanh cong, $fail that bai" -ForegroundColor Yellow
Write-Host "=====================================================" -ForegroundColor Yellow
