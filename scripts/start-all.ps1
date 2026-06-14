# ============================================================
# Moment Weaver · 三件套一键启动
# 用法：powershell -ExecutionPolicy Bypass -File scripts/start-all.ps1
# 依赖：JDK 17 在 PATH、Python 3.11、pnpm 已装
# ============================================================

$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
Set-Location $root

function Start-Window {
    param([string]$Title, [string]$Cmd, [string]$Cwd = $root)
    Start-Process powershell -ArgumentList @(
        '-NoExit', '-Command',
        "Set-Location '$Cwd'; Write-Host '=== $Title ===' -ForegroundColor Cyan; $Cmd"
    ) -WindowStyle Normal
    Write-Host "已启动: $Title" -ForegroundColor Green
}

Write-Host "==> 启动 MongoDB (M2 必需 · :27017)" -ForegroundColor Cyan
$mongoExe = 'C:\mongodb\bin\mongod.exe'
if (Test-Path $mongoExe) {
    if (-not (Test-Path 'C:\mongodb-data')) { New-Item -ItemType Directory -Path 'C:\mongodb-data' | Out-Null }
    Start-Window 'MongoDB' "$mongoExe --dbpath C:\mongodb-data"
    Start-Sleep -Seconds 2
} else {
    Write-Host "  [跳过] 未找到 $mongoExe" -ForegroundColor Yellow
    Write-Host "  提示：从 https://www.mongodb.com/try/download/community 下载 zip 解压到 C:\mongodb" -ForegroundColor Yellow
}

Write-Host "==> 启动后端 (Spring Boot · :8080)" -ForegroundColor Cyan
Start-Window 'Spring Boot' 'mvn -pl moment-weaver-app -am spring-boot:run' (Join-Path $root 'backend')

Start-Sleep -Seconds 5

Write-Host "==> 启动 AI (FastAPI · :8000)" -ForegroundColor Cyan
Start-Window 'FastAPI' 'uvicorn app.main:app --reload --port 8000' (Join-Path $root 'ai')

Start-Sleep -Seconds 3

Write-Host "==> 启动前端 (Vite · :5173)" -ForegroundColor Cyan
Start-Window 'Vue Vite' 'pnpm dev' (Join-Path $root 'frontend')

Write-Host "`n三件套启动指令已发出，请关注各窗口输出" -ForegroundColor Yellow
Write-Host "完成后访问 http://localhost:5173/" -ForegroundColor Green
