# ============================================================
# Moment Weaver · 环境检查脚本
# 用法：powershell -ExecutionPolicy Bypass -File scripts/check-env.ps1
# ============================================================

$ErrorActionPreference = 'Continue'
$checks = @()
$fail = 0

function Test-Tool {
    param([string]$Name, [string]$Cmd, [string]$Pattern, [string]$Label)
    try {
        $out = & $Cmd 2>$null
        $hit = ($out -join "`n") -match $Pattern
        if ($hit) {
            $ver = ($out | Select-Object -First 1).ToString().Trim()
            $script:checks += [PSCustomObject]@{ Name = $Name; Status = 'OK'; Version = $ver }
        } else {
            $script:checks += [PSCustomObject]@{ Name = $Name; Status = 'FAIL'; Version = "未匹配 $Pattern" }
            $script:fail++
        }
    } catch {
        $script:checks += [PSCustomObject]@{ Name = $Name; Status = 'FAIL'; Version = $_.Exception.Message }
        $script:fail++
    }
}

function Test-Port {
    param([string]$Name, [int]$Port)
    $tcp = New-Object System.Net.Sockets.TcpClient
    try {
        $tcp.BeginConnect('127.0.0.1', $Port, $null, $null) | Out-Null
        $ok = $tcp.Connected
        if (-not $ok) { [System.Threading.Thread]::Sleep(200); $tcp.Connect('127.0.0.1', $Port) }
        $script:checks += [PSCustomObject]@{ Name = $Name; Status = 'OK'; Version = "port $Port" }
    } catch {
        $script:checks += [PSCustomObject]@{ Name = $Name; Status = 'FAIL'; Version = "port $Port 不可达" }
        $script:fail++
    } finally {
        $tcp.Close()
    }
}

Write-Host "==> 检查运行时版本" -ForegroundColor Cyan
Test-Tool 'JDK 17'        'java'           '17'  'java -version'
Test-Tool 'Maven'         'mvn'            'Apache Maven'  'mvn -v'
Test-Tool 'Node.js 20'    'node'           'v20'  'node -v'
Test-Tool 'pnpm (推荐)'    'pnpm'           '\d'  'pnpm -v'
Test-Tool 'Python 3.11'   'python'         '3\.11'  'python --version'
Test-Tool 'uv (推荐)'      'uv'             '\d'  'uv --version'

Write-Host "`n==> 检查中间件端口" -ForegroundColor Cyan
Test-Port 'MySQL'    3306
Test-Port 'MongoDB'  27017
Test-Port 'Redis'    6379

Write-Host "`n========== 结果 ==========" -ForegroundColor Cyan
$checks | Format-Table -AutoSize Name, Status, Version

if ($fail -gt 0) {
    Write-Host "`n$fail 项检查失败，请按上方提示修复" -ForegroundColor Red
    exit 1
} else {
    Write-Host "`n所有检查通过 ✅" -ForegroundColor Green
    exit 0
}
