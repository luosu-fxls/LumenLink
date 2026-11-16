#!/usr/bin/env pwsh
# LumenLink 协议栈自测：启动假相机 -> 跑分帧验证 -> 收尾。
# 用法（从仓库根目录）：pwsh -File ./LumenLink/tools/ptpsim/run_tests.ps1
# 需要 bun（Cherry 的 mise 环境已自带）。
#
# 注意：本脚本保持纯 ASCII 输出，避免 Windows PowerShell 5.1 在
# 代码页不匹配时把中文字面量解析坏（那会让脚本直接语法错误）。

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

$bun = (Get-Command bun -ErrorAction SilentlyContinue).Source
if (-not $bun) {
    $candidates = New-Object System.Collections.ArrayList
    if ($env:USERPROFILE) { [void]$candidates.Add((Join-Path $env:USERPROFILE '.cherrystudio\bin\bun.exe')) }
    if ($env:LOCALAPPDATA) { [void]$candidates.Add((Join-Path $env:LOCALAPPDATA 'mise\installs\bun\latest\bun.exe')) }
    if ($env:APPDATA) { [void]$candidates.Add((Join-Path $env:APPDATA 'CherryStudio\mise\installs\bun\latest\bun.exe')) }
    foreach ($p in $candidates) { if (Test-Path -LiteralPath $p) { $bun = $p; break } }
}
if (-not $bun) { Write-Host "[ERROR] bun not found"; exit 1 }

$port = if ($env:PTP_PORT) { $env:PTP_PORT } else { 15740 }
$env:PTP_PORT = "$port"

$simOut = Join-Path $env:TEMP 'lumen_sim_out.txt'
$simErr = Join-Path $env:TEMP 'lumen_sim_err.txt'
$cliOut = Join-Path $env:TEMP 'lumen_cli_out.txt'
$cliErr = Join-Path $env:TEMP 'lumen_cli_err.txt'

$sim = Start-Process -FilePath $bun `
    -ArgumentList @('run', (Join-Path $here 'ptp_camera.ts')) `
    -NoNewWindow -PassThru `
    -RedirectStandardOutput $simOut -RedirectStandardError $simErr
Start-Sleep -Seconds 2

if ($sim.HasExited) {
    Write-Host "[ERROR] simulator failed to start" -ForegroundColor Red
    Get-Content $simErr -Raw -ErrorAction SilentlyContinue
    exit 1
}
Write-Host ("[sim] running, pid=" + $sim.Id + " port=" + $port) -ForegroundColor Cyan

$exitCode = 1
try {
    $cli = Start-Process -FilePath $bun `
        -ArgumentList @('run', (Join-Path $here 'client_test.ts')) `
        -NoNewWindow -Wait -PassThru `
        -RedirectStandardOutput $cliOut -RedirectStandardError $cliErr

    Get-Content $cliOut -Raw -Encoding UTF8
    $errText = Get-Content $cliErr -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
    if ($errText) { Write-Host ("--- client stderr ---`n" + $errText) -ForegroundColor Yellow }

    $exitCode = $cli.ExitCode
    if ($exitCode -ne 0) {
        Write-Host "--- simulator log ---" -ForegroundColor Yellow
        Get-Content $simOut -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
    }
}
finally {
    if (-not $sim.HasExited) { Stop-Process -Id $sim.Id -Force -ErrorAction SilentlyContinue }
}

if ($exitCode -eq 0) { Write-Host "[OK] all tests passed" -ForegroundColor Green }
exit $exitCode

