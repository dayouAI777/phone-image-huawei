#Requires -Version 5.1
<#
  QwenPaint 构建脚本
  用法:
    powershell -ExecutionPolicy Bypass -File scripts\build.ps1            # debug apk
    powershell -ExecutionPolicy Bypass -File scripts\build.ps1 -Release   # release apk (未签名)
    powershell -ExecutionPolicy Bypass -File scripts\build.ps1 -Install   # 构建后 adb 安装
    powershell -ExecutionPolicy Bypass -File scripts\build.ps1 -PushModel "D:\models\sd-turbo-q4.gguf"
#>
[CmdletBinding()]
param(
    [switch]$Release,
    [switch]$Install,
    [string]$PushModel = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Info($m) { Write-Host "[build] $m" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "[ ok  ] $m" -ForegroundColor Green }
function Fail($m) { Write-Host "[fail ] $m" -ForegroundColor Red; exit 1 }

if (-not $env:JAVA_HOME) {
    $studioJbr = "C:\Program Files\Android\Android Studio\jbr"
    if (Test-Path (Join-Path $studioJbr "bin\java.exe")) { $env:JAVA_HOME = $studioJbr }
}

if (-not (Test-Path (Join-Path $root "local.properties"))) {
    Fail "local.properties missing. Run scripts\setup.ps1 first."
}
if (-not (Test-Path (Join-Path $root "app\src\main\cpp\third_party\stable-diffusion.cpp\CMakeLists.txt"))) {
    Fail "stable-diffusion.cpp missing. Run scripts\setup.ps1 first."
}

$gradlew = Join-Path $root "gradlew.bat"
if (-not (Test-Path $gradlew)) {
    Info "gradlew.bat not found -> generating via local Gradle"
    $gradle = Get-Command gradle -ErrorAction SilentlyContinue
    if ($gradle) {
        & gradle wrapper --gradle-version 8.9
    } else {
        Fail "No gradlew.bat and no 'gradle' on PATH. Open the project once in Android Studio (it will sync and generate the wrapper), then re-run."
    }
}

$task = if ($Release) { "assembleRelease" } else { "assembleDebug" }
Info "running gradlew $task (first build compiles ggml for arm64, expect 5-15 min)"
& $gradlew $task --no-daemon
if ($LASTEXITCODE -ne 0) { Fail "gradle failed with exit code $LASTEXITCODE" }

$apkDir = if ($Release) { "app\build\outputs\apk\release" } else { "app\build\outputs\apk\debug" }
$apk = Get-ChildItem -Path (Join-Path $root $apkDir) -Filter "*.apk" -ErrorAction SilentlyContinue |
       Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $apk) { Fail "APK not found under $apkDir" }
Ok "APK: $($apk.FullName)  ($([math]::Round($apk.Length/1MB,1)) MB)"

# ---------- 可选: 推模型到手机 ----------
if ($PushModel) {
    if (-not (Test-Path $PushModel)) { Fail "model not found: $PushModel" }
    $adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
    if (-not (Test-Path $adb)) { $adb = "adb" }
    Info "pushing model -> /sdcard/Download/  (then use in-app 导入模型)"
    & $adb push $PushModel "/sdcard/Download/"
    Ok "model pushed"
}

# ---------- 可选: 安装 ----------
if ($Install) {
    $adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
    if (-not (Test-Path $adb)) { $adb = "adb" }
    Info "installing to connected device..."
    & $adb install -r $apk.FullName
    if ($LASTEXITCODE -ne 0) {
        Fail "adb install failed. For 卓易通: copy the APK to the phone and import it inside 卓易通 instead."
    }
    Ok "installed"
}
