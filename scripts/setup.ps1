#Requires -Version 5.1
<#
  QwenPaint 环境准备脚本
  用法:  powershell -ExecutionPolicy Bypass -File scripts\setup.ps1
  可选:  -AndroidSdk "C:\Users\you\AppData\Local\Android\Sdk"  -NdkVersion 27.0.12077973
         -SkipSdk  跳过 SDK 组件安装
         -SkipSdcpp 跳过推理内核拉取
#>
[CmdletBinding()]
param(
    [string]$AndroidSdk = "",
    [string]$NdkVersion = "27.0.12077973",
    [switch]$SkipSdk,
    [switch]$SkipSdcpp
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

function Info($m) { Write-Host "[setup] $m" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "[ ok  ] $m" -ForegroundColor Green }
function Warn($m) { Write-Host "[warn ] $m" -ForegroundColor Yellow }
function Fail($m) { Write-Host "[fail ] $m" -ForegroundColor Red; exit 1 }

Info "project root: $root"

# ---------- 1. JDK ----------
$javaHome = $env:JAVA_HOME
$studioJbr = "C:\Program Files\Android\Android Studio\jbr"
if (-not $javaHome -or -not (Test-Path (Join-Path $javaHome "bin\java.exe"))) {
    if (Test-Path (Join-Path $studioJbr "bin\java.exe")) {
        $javaHome = $studioJbr
        Info "using Android Studio bundled JDK: $javaHome"
    } else {
        Fail "JDK 17 not found. Install Android Studio (bundled JBR is enough) or set JAVA_HOME to a JDK 17."
    }
}
$env:JAVA_HOME = $javaHome
Ok "JAVA_HOME = $javaHome"

# ---------- 2. Android SDK ----------
if (-not $AndroidSdk) {
    $candidates = @()
    if ($env:ANDROID_HOME)     { $candidates += $env:ANDROID_HOME }
    if ($env:ANDROID_SDK_ROOT) { $candidates += $env:ANDROID_SDK_ROOT }
    $candidates += (Join-Path $env:LOCALAPPDATA "Android\Sdk")
    foreach ($c in $candidates) {
        if ($c -and (Test-Path (Join-Path $c "platform-tools"))) { $AndroidSdk = $c; break }
    }
    if (-not $AndroidSdk) {
        foreach ($c in $candidates) {
            if ($c -and (Test-Path $c)) { $AndroidSdk = $c; break }
        }
    }
}

if (-not $AndroidSdk -or -not (Test-Path $AndroidSdk)) {
    Fail "Android SDK not found. Install Android Studio first: https://developer.android.com/studio"
}
Ok "Android SDK = $AndroidSdk"

# 写 local.properties（路径用正斜杠，properties 里反斜杠是转义符）
$lp = Join-Path $root "local.properties"
$sdkProp = $AndroidSdk.Replace('\', '/')
Set-Content -Path $lp -Value "sdk.dir=$sdkProp" -Encoding ASCII
Ok "wrote local.properties"

# ---------- 3. SDK 组件 ----------
if (-not $SkipSdk) {
    $sdkmanager = Get-ChildItem -Path (Join-Path $AndroidSdk "cmdline-tools") -Filter "sdkmanager.bat" -Recurse -ErrorAction SilentlyContinue |
                  Select-Object -First 1
    if (-not $sdkmanager) {
        Warn "sdkmanager not found (cmdline-tools missing)."
        Warn "Install via Android Studio -> SDK Manager, or download command-line tools."
        Warn "Skipping automatic component install."
    } else {
        Info "accepting licenses..."
        1..40 | ForEach-Object { "y" } | & $sdkmanager.FullName --sdk_root="$AndroidSdk" --licenses 2>&1 | Out-Null

        $packages = @(
            "platform-tools",
            "platforms;android-35",
            "cmake;3.22.1",
            "ndk;$NdkVersion"
        )
        Info "installing: $($packages -join ', ')"
        & $sdkmanager.FullName --sdk_root="$AndroidSdk" @packages
        if ($LASTEXITCODE -ne 0) { Fail "sdkmanager failed with exit code $LASTEXITCODE" }
        Ok "SDK components ready"
    }
}

# ---------- 4. 推理内核 ----------
$sdcpp = Join-Path $root "app\src\main\cpp\third_party\stable-diffusion.cpp"
if (-not $SkipSdcpp) {
    if (-not (Test-Path (Join-Path $sdcpp "CMakeLists.txt"))) {
        Info "cloning stable-diffusion.cpp (with submodules, ~80MB)..."
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $sdcpp) | Out-Null
        git clone --recursive --depth 1 https://github.com/leejet/stable-diffusion.cpp $sdcpp
        if ($LASTEXITCODE -ne 0) { Fail "git clone failed. Check network / proxy." }
    } else {
        Info "stable-diffusion.cpp already present, updating submodules"
        Push-Location $sdcpp
        git submodule update --init --recursive
        Pop-Location
    }

    $hdr = Join-Path $sdcpp "include\stable-diffusion.h"
    if (-not (Test-Path $hdr)) { Fail "header missing: $hdr" }
    $txt = Get-Content $hdr -Raw
    if ($txt -notmatch "sd_img_gen_opt_create") {
        Fail "stable-diffusion.cpp API is too old (no sd_img_gen_opt_create). Pull latest master."
    }
    Ok "reasoning core ready (API check passed)"
}

# ---------- 5. 模型 ----------
$models = Join-Path $root "models"
New-Item -ItemType Directory -Force -Path $models | Out-Null
Info "model folder: $models"
Write-Host ""
Write-Host "Next steps" -ForegroundColor Cyan
Write-Host "  1) put a .gguf / .safetensors model anywhere on your PC (see README for sources)"
Write-Host "  2) build:   powershell -ExecutionPolicy Bypass -File scripts\build.ps1"
Write-Host "  3) install: powershell -ExecutionPolicy Bypass -File scripts\build.ps1 -Install"
Write-Host ""
Ok "setup finished"
