# Script to start IMS Data Channel local environment on Windows via Docker Compose
Write-Host "Starting IMS Data Channel local containers for Windows Host (192.168.0.182)..." -ForegroundColor Cyan
docker compose -f deploy/docker-compose.win.yml up -d
if ($LASTEXITCODE -eq 0) {
    Write-Host "`nContainers started successfully! Current Status:" -ForegroundColor Green
    docker compose -f deploy/docker-compose.win.yml ps
} else {
    Write-Host "`nFailed to start containers. Please check Docker Desktop." -ForegroundColor Red
}
