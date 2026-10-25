# build-local-images.ps1
# Script to rebuild all custom local images for the project

$ErrorActionPreference = "Stop"

Write-Host "=============================================" -ForegroundColor Cyan
Write-Host "   BUILDING LOCAL PROJECT DOCKER IMAGES      " -ForegroundColor Cyan
Write-Host "=============================================" -ForegroundColor Cyan

# 1. Build erlang-sip:local
Write-Host "`n[1/3] Building erlang-sip:local..." -ForegroundColor Cyan
docker build -t erlang-sip:local ./erlang-sip
if ($LASTEXITCODE -ne 0) {
    Write-Host "[FAIL] Failed to build erlang-sip:local" -ForegroundColor Red
    exit 1
}

# 2. Build go-mf:local
Write-Host "`n[2/3] Building go-mf:local..." -ForegroundColor Cyan
docker build -t go-mf:local ./go-mf
if ($LASTEXITCODE -ne 0) {
    Write-Host "[FAIL] Failed to build go-mf:local" -ForegroundColor Red
    exit 1
}

# 3. Build postgres-patroni:local
Write-Host "`n[3/3] Building postgres-patroni:local..." -ForegroundColor Cyan
docker build -t postgres-patroni:local -f deploy/Dockerfile.patroni ./deploy
if ($LASTEXITCODE -ne 0) {
    Write-Host "[FAIL] Failed to build postgres-patroni:local" -ForegroundColor Red
    exit 1
}

Write-Host "`n=============================================" -ForegroundColor Green
Write-Host " SUCCESS! All local images have been rebuilt." -ForegroundColor Green
Write-Host "=============================================" -ForegroundColor Green
