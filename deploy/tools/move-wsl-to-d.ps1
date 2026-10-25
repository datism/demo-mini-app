# move-wsl-to-d.ps1
# Script to move docker-desktop WSL distro from C: to D:

$ErrorActionPreference = "Stop"

# Create destination folder
$destDir = "D:\WSL\docker-desktop"
$backupFile = "D:\WSL\docker-desktop-backup.tar"

if (-not (Test-Path "D:\WSL")) {
    New-Item -ItemType Directory -Path "D:\WSL" | Out-Null
}

Write-Host "=============================================" -ForegroundColor Cyan
Write-Host "   WSL DOCKER-DESKTOP RELOCATION TO D: DRIVE " -ForegroundColor Cyan
Write-Host "=============================================" -ForegroundColor Cyan
Write-Host "WARNING: Please make sure Docker Desktop is FULLY CLOSED before proceeding!" -ForegroundColor Yellow
Write-Host "Press any key to continue once Docker Desktop is closed..."
$null = [System.Console]::ReadKey($true)

Write-Host "`n[1/4] Stopping all running WSL instances..." -ForegroundColor Cyan
wsl --shutdown
Start-Sleep -Seconds 2

Write-Host "[2/4] Exporting 'docker-desktop' to $backupFile... (This may take a few minutes)" -ForegroundColor Cyan
wsl --export docker-desktop $backupFile
if ($LASTEXITCODE -ne 0) {
    Write-Host "[FAIL] Export failed." -ForegroundColor Red; exit 1
}

Write-Host "[3/4] Unregistering 'docker-desktop' from C: drive..." -ForegroundColor Cyan
wsl --unregister docker-desktop
if ($LASTEXITCODE -ne 0) {
    Write-Host "[FAIL] Unregister failed." -ForegroundColor Red; exit 1
}

if (-not (Test-Path $destDir)) {
    New-Item -ItemType Directory -Path $destDir | Out-Null
}

Write-Host "[4/4] Importing 'docker-desktop' to $destDir..." -ForegroundColor Cyan
wsl --import docker-desktop $destDir $backupFile --version 2
if ($LASTEXITCODE -ne 0) {
    Write-Host "[FAIL] Import failed." -ForegroundColor Red; exit 1
}

Write-Host "`n[Clean Up] Removing backup tar file..." -ForegroundColor Cyan
Remove-Item -Force $backupFile

Write-Host "`n=============================================" -ForegroundColor Green
Write-Host " SUCCESS! docker-desktop has been moved to $destDir." -ForegroundColor Green
Write-Host " You can now restart Docker Desktop." -ForegroundColor Green
Write-Host "=============================================" -ForegroundColor Green
