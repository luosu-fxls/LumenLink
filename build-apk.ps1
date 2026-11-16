#!/usr/bin/env pwsh
# 涓€閿噸寤?LumenLink APK銆?#
# 鍓嶇疆锛?toolchain/ 涓嬫湁 jdk / gradle-8.9 / android-sdk锛堟湰浠撳簱宸茶嚜甯︼紝瑙?README銆屾湰鍦扮幆澧冦€嶏級銆?# 鐢ㄦ硶锛歱owershell -File .\build-apk.ps1
#
# 杈撳嚭锛歞ist\LumenLink-<version>.apk锛堢敤鍥哄畾瀵嗛挜绛惧悕锛屽彲闀挎湡瀹夎鍗囩骇锛?
$ErrorActionPreference = 'Continue'
$root = (Get-Location).Path
if (-not (Test-Path (Join-Path $root 'LumenLink'))) { $root = Split-Path -Parent $MyInvocation.MyCommand.Path }
if (-not (Test-Path (Join-Path $root 'LumenLink'))) {
    Write-Host "[ERROR] run this from the repo root (folder containing LumenLink/)" -ForegroundColor Red
    exit 1
}

$tc     = Join-Path $root '.toolchain'
$jdk    = Join-Path $tc 'jdk'
$sdk    = Join-Path $tc 'android-sdk'
$gradle = Join-Path $tc 'gradle-8.9\bin\gradle.bat'
$proj   = Join-Path $root 'LumenLink'
$ks     = Join-Path $tc 'lumenlink-release.jks'

foreach ($p in @($jdk, $sdk, $gradle)) {
    if (-not (Test-Path $p)) {
        Write-Host "[ERROR] missing toolchain component: $p" -ForegroundColor Red
        Write-Host "        see README section 'Build' for how to set it up" -ForegroundColor Yellow
        exit 1
    }
}

$env:JAVA_HOME         = $jdk
$env:ANDROID_HOME      = $sdk
$env:ANDROID_SDK_ROOT  = $sdk
$env:GRADLE_USER_HOME  = Join-Path $tc 'gradle-home'

$localProps = Join-Path $proj 'local.properties'
if (-not (Test-Path $localProps)) {
    Set-Content -LiteralPath $localProps -Value ('sdk.dir=' + $sdk.Replace('\','\\')) -Encoding ASCII
}

Write-Host "[1/4] gradle assembleDebug" -ForegroundColor Cyan
$gradleOut = Join-Path $env:TEMP 'lumen_build.txt'
$gradleErr = Join-Path $env:TEMP 'lumen_build_err.txt'
$bp = Start-Process -FilePath $gradle -ArgumentList @(
    '-p', $proj, ':app:assembleDebug', '--no-daemon', '--console=plain'
) -NoNewWindow -Wait -PassThru -RedirectStandardOutput $gradleOut -RedirectStandardError $gradleErr

if ($bp.ExitCode -ne 0) {
    Write-Host "[FAIL] gradle build failed" -ForegroundColor Red
    Get-Content $gradleErr -Raw -ErrorAction SilentlyContinue
    Write-Host "--- last output ---" -ForegroundColor Yellow
    (Get-Content $gradleOut -ErrorAction SilentlyContinue | Select-Object -Last 30) -join "`n"
    exit 1
}

$srcApk = Join-Path $proj 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $srcApk)) { Write-Host "[ERROR] apk not produced" -ForegroundColor Red; exit 1 }

Write-Host "[2/4] zipalign" -ForegroundColor Cyan
$bt = Join-Path $sdk 'build-tools\35.0.0'
if (-not (Test-Path $bt)) {
    $latest = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory -ErrorAction SilentlyContinue | Select-Object -Last 1
    if ($latest) { $bt = $latest.FullName }
}
$aligned = Join-Path $tc 'aligned.apk'
Remove-Item $aligned -ErrorAction SilentlyContinue
$zp = Start-Process -FilePath (Join-Path $bt 'zipalign.exe') -ArgumentList @('-p','-f','4',"`"$srcApk`"","`"$aligned`"") `
    -NoNewWindow -Wait -PassThru -RedirectStandardOutput (Join-Path $env:TEMP 'lz.txt') -RedirectStandardError (Join-Path $env:TEMP 'lz_e.txt')
if ($zp.ExitCode -ne 0 -or -not (Test-Path $aligned)) { Write-Host "[ERROR] zipalign failed" -ForegroundColor Red; exit 1 }

Write-Host "[3/4] sign" -ForegroundColor Cyan
if (-not (Test-Path $ks)) {
    Write-Host "      generating keystore (first run)" -ForegroundColor Yellow
    Start-Process -FilePath (Join-Path $jdk 'bin\keytool.exe') -ArgumentList @(
        '-genkeypair','-keystore',"`"$ks`"",'-alias','lumenlink','-keyalg','RSA','-keysize','2048',
        '-validity','10950','-storepass','lumenlink','-keypass','lumenlink',
        '-dname','CN=LumenLink, OU=Dev, O=LumenLink, L=NA, ST=NA, C=CN'
    ) -NoNewWindow -Wait | Out-Null
}

$version = (Select-String -Path (Join-Path $proj 'app\build.gradle.kts') -Pattern 'val appVersionName = "([^"]+)"' |
    Select-Object -First 1).Matches.Groups[1].Value
if (-not $version) { Write-Host "[ERROR] cannot read appVersionName from build.gradle.kts" -ForegroundColor Red; exit 1 }
$outDir = Join-Path $root 'dist'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$outApk = Join-Path $outDir "LumenLink-$version.apk"
Remove-Item $outApk -ErrorAction SilentlyContinue

$sp = Start-Process -FilePath (Join-Path $bt 'apksigner.bat') -ArgumentList @(
    'sign','--ks',"`"$ks`"",'--ks-key-alias','lumenlink','--ks-pass','pass:lumenlink','--key-pass','pass:lumenlink',
    '--v1-signing-enabled','true','--v2-signing-enabled','true','--v3-signing-enabled','true',
    '--out',"`"$outApk`"","`"$aligned`""
) -NoNewWindow -Wait -PassThru -RedirectStandardOutput (Join-Path $env:TEMP 'sg.txt') -RedirectStandardError (Join-Path $env:TEMP 'sg_e.txt')
if ($sp.ExitCode -ne 0 -or -not (Test-Path $outApk)) {
    Write-Host "[ERROR] signing failed" -ForegroundColor Red
    Get-Content (Join-Path $env:TEMP 'sg_e.txt') -Raw -ErrorAction SilentlyContinue
    exit 1
}

Write-Host "[4/4] verify" -ForegroundColor Cyan
$vp = Start-Process -FilePath (Join-Path $bt 'apksigner.bat') -ArgumentList @('verify','--print-certs',"`"$outApk`"") `
    -NoNewWindow -Wait -PassThru -RedirectStandardOutput (Join-Path $env:TEMP 'vf.txt') -RedirectStandardError (Join-Path $env:TEMP 'vf_e.txt')
if ($vp.ExitCode -ne 0) { Write-Host "[WARN] signature verification reported a problem" -ForegroundColor Yellow }

$hash = (Get-FileHash $outApk -Algorithm SHA256).Hash
$sizeMb = (Get-Item $outApk).Length / 1MB

# ── 版本号重叠守卫 ────────────────────────────────────────────────────────
# 系统只认 versionCode。若新包的 code 不大于已装包的 code，安装会被直接拒绝，
# 表现就是"改了却看不到"（历史上 0.20.0/0.20.1 都用了 25 就是这个后果）。
# 这里拿 dist 里所有历史包做比对，发现重叠立刻报错，不让坏包出厂。
$newAapt = $null
foreach ($cand in (Get-ChildItem (Join-Path $sdk 'build-tools') -Directory -ErrorAction SilentlyContinue)) {
    $a = Join-Path $cand.FullName 'aapt.exe'
    if (Test-Path $a) { $newAapt = $a; break }
}
$newCode = 0
if ($newAapt) {
    $probe = Join-Path $env:TEMP 'lumen_probe.txt'
    Start-Process -FilePath $newAapt -ArgumentList @('dump','badging',"`"$outApk`"") -RedirectStandardOutput $probe -NoNewWindow -Wait | Out-Null
    $line = (Get-Content $probe | Select-String "^package:").Line
    if ($line -match "versionCode='(\d+)'") { $newCode = [int]$matches[1] }
}
if ($newCode -le 0) { Write-Host "[WARN] cannot read versionCode from built apk" -ForegroundColor Yellow }

$clash = @()
if ($newAapt -and $newCode -gt 0) {
    foreach ($old in (Get-ChildItem $outDir -Filter *.apk -ErrorAction SilentlyContinue)) {
        if ($old.FullName -eq $outApk) { continue }
        $o = Join-Path $env:TEMP ('lumen_old_' + $old.BaseName + '.txt')
        Start-Process -FilePath $newAapt -ArgumentList @('dump','badging',"`"$($old.FullName)`"") -RedirectStandardOutput $o -NoNewWindow -Wait | Out-Null
        $ol = (Get-Content $o | Select-String "^package:").Line
        if ($ol -match "versionCode='(\d+)' versionName='([^']*)'") {
            $oc = [int]$matches[1]; $on = $matches[2]
            if ($oc -eq $newCode) { $clash += "versionCode $newCode 与 $($old.Name) (v$on) 相同" }
            elseif ($oc -gt $newCode) { $clash += "$($old.Name) (v$on, code $oc) 的 code 比新包 $newCode 还大" }
        }
    }
}
if ($clash.Count -gt 0) {
    Write-Host ""
    Write-Host "[FAIL] 版本号冲突，禁止出厂：" -ForegroundColor Red
    $clash | ForEach-Object { Write-Host "       - $_" -ForegroundColor Red }
    Write-Host "       修 build.gradle.kts 里的 appVersionName（versionCode 会自动推导）" -ForegroundColor Yellow
    exit 1
}

Write-Host ""
Write-Host "[OK] APK ready" -ForegroundColor Green
Write-Host ("     path   : dist\LumenLink-" + $version + ".apk")
Write-Host ("     version: code " + $newCode + " / name " + $version)
Write-Host ("     size   : {0:N2} MB" -f $sizeMb)
Write-Host ("     sha256 : " + $hash)























