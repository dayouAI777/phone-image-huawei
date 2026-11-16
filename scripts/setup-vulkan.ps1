#Requires -Version 5.1
<#
  准备 Vulkan(GPU) 构建所需的宿主工具链。
  只在需要 -Pqwenpaint.vulkan=true 时才需要跑；纯 CPU 构建不需要。

  做两件事：
    1. 下载 MinGW-w64(msvcrt 版) 到 tools/mingw-msvcrt，用来编 shader 生成器
       vulkan-shaders-gen.exe（ggml 的交叉编译要求宿主上跑一个生成器）
    2. 校验 NDK 自带的 glslc.exe（编译 .comp -> .spv）

  用法: powershell -ExecutionPolicy Bypass -File scripts\setup-vulkan.ps1
#>
[CmdletBinding()]
param(
    [string]$Mirror = "https://ghfast.top/"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$tools = Join-Path $root "tools"
$dest = Join-Path $tools "mingw-msvcrt"

function Ok($m)   { Write-Host "[ ok  ] $m" -ForegroundColor Green }
function Info($m) { Write-Host "[setup] $m" -ForegroundColor Cyan }
function Fail($m) { Write-Host "[fail ] $m" -ForegroundColor Red; exit 1 }

# ---------- 1. NDK glslc ----------
$ndk = Join-Path $tools "android-sdk\ndk\27.0.12077973"
$glslc = Join-Path $ndk "shader-tools\windows-x86_64\glslc.exe"
if (-not (Test-Path $glslc)) {
    Fail "glslc.exe not found under $ndk\shader-tools. Re-run scripts\setup.ps1 to install the NDK."
}
Ok "glslc: $glslc"

# ---------- 2. MinGW (msvcrt) ----------
$gxx = Join-Path $dest "mingw64\bin\g++.exe"
if (Test-Path $gxx) {
    Ok "MinGW already present: $gxx"
} else {
    if (-not (Test-Path $tools)) { New-Item -ItemType Directory -Path $tools | Out-Null }

    $url = "${Mirror}https://github.com/niXman/mingw-builds-binaries/releases/download/15.2.0-rt_v13-rev0/x86_64-15.2.0-release-posix-seh-msvcrt-rt_v13-rev0.7z"
    $archive = Join-Path $tools "mingw-msvcrt.7z"
    Info "downloading MinGW-w64 (msvcrt, ~95 MB) ..."
    & curl.exe -L --ssl-no-revoke --retry 5 --retry-all-errors -o $archive $url
    if ($LASTEXITCODE -ne 0) { Fail "download failed: $url" }

    $sevenZip = Join-Path $tools "7zx\x64\7za.exe"
    if (-not (Test-Path $sevenZip)) {
        $sevenZip = Join-Path $tools "7za.exe"
    }
    if (-not (Test-Path $sevenZip)) {
        Fail "need 7za.exe to unpack the .7z (put it in tools\7zx\x64\7za.exe or tools\7za.exe)"
    }

    Info "extracting ..."
    & $sevenZip x $archive "-o$dest" -y | Out-Null
    if (-not (Test-Path $gxx)) { Fail "extraction did not produce $gxx" }
    Ok "MinGW installed: $gxx"
}

& $gxx --version | Select-Object -First 1
Write-Host ""
Write-Host "Now build the GPU variant:" -ForegroundColor Yellow
Write-Host "  gradle :app:assembleRelease -Pqwenpaint.vulkan=true"
