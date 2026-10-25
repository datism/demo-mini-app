# Script to stop IMS Data Channel local environment on Windows
Write-Host "Stopping IMS Data Channel local containers..." -ForegroundColor Yellow
docker compose -f deploy/docker-compose.win.yml down
