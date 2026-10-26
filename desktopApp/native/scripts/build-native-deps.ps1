#
# ArchiveTune (2026)
# © Rukamori — github.com/rukamori
# GPL-3.0 License | Contributors: see git history
# Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
# Portions © vossgraves — github.com/vossgraves
#
# Produces the Windows x64 counterpart of build-native-deps.sh. The media closure is the
# FFmpeg 8.1 LGPL shared build published by BtbN/FFmpeg-Builds (the upstream project has no
# MSVC builds of its own); it ships the MSVC import libraries and headers the engine links
# against plus the DLLs the package stages. TDLight's static OpenSSL and zlib inputs are
# built from the same pinned source releases the Unix recipe uses, with the dynamic CRT so
# they match the rest of the native closure. Every download is checked against a pinned
# SHA-256, and no compiler output is downloaded except the reviewed FFmpeg archive.
#
# Outputs stay under desktopApp/build/native, which Git ignores. Set ARCHIVETUNE_NATIVE_BUILD
# to move the tree.
#
# Status: written for the CI Windows recipe and not yet run on a Windows host. The MSVC build
# steps (vcvars, perl Configure, nmake) follow the OpenSSL 3.x NOTES-WINDOWS.md recipe and the
# zlib CMake documentation; they stop on the first failing command rather than guessing.

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Write-Log([string]$Message) {
    Write-Host "[native-deps] $Message"
}

function Stop-Recipe([string]$Message) {
    throw "[native-deps] error: $Message"
}

function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) {
        Stop-Recipe "$Program exited with status $LASTEXITCODE"
    }
}

if ([System.Environment]::OSVersion.Platform -ne 'Win32NT') {
    Stop-Recipe 'this recipe covers Windows x64; Linux and macOS use build-native-deps.sh'
}
if ([System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture -ne [System.Runtime.InteropServices.Architecture]::X64) {
    Stop-Recipe 'only windows-x64 packages are staged; no Windows arm64 recipe exists'
}

$Component = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$Build = $env:ARCHIVETUNE_NATIVE_BUILD
if (-not $Build) {
    $Build = Join-Path $Component 'build\native'
}
$Downloads = Join-Path $Build 'downloads'
$Src = Join-Path $Build 'src'
$Prefix = Join-Path $Build 'prefix'
$OpensslStatic = Join-Path $Build 'openssl-static'
$ZlibStatic = Join-Path $Build 'zlib-static'
$Logs = Join-Path $Build 'logs'
New-Item -ItemType Directory -Force -Path $Downloads, $Src, $Prefix, $Logs | Out-Null

# FFmpeg 8.1 LGPL shared build. The asset follows the 8.1 branch: its checksum is pinned, so a
# newer upstream rebuild stops the recipe until the pin here is updated (same policy as the
# source pins in build-native-deps.sh). The closure is versioned avcodec-62/avformat-62/avutil-60/
# avfilter-11/avdevice-62/swresample-6/swscale-9 — the exact set the engine links.
$FfmpegZip = 'ffmpeg-n8.1-latest-win64-lgpl-shared-8.1.zip'
$FfmpegUrl = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/$FfmpegZip"
$FfmpegSha256 = '09772a308ccee4304e93ad1137d513e16710e83f56161bc626e2df4e5d3b6b5b'
# Same pins as build-native-deps.sh.
$ZlibVersion = '1.3.2'
$ZlibUrl = "https://zlib.net/zlib-$ZlibVersion.tar.gz"
$ZlibSha256 = 'bb329a0a2cd0274d05519d61c667c062e06990d72e125ee2dfa8de64f0119d16'
$OpensslVersion = '3.5.9'
$OpensslUrl = "https://www.openssl.org/source/openssl-$OpensslVersion.tar.gz"
$OpensslSha256 = '603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a'
$MiniaudioVersion = '0.11.25'
$MiniaudioUrl = "https://raw.githubusercontent.com/mackron/miniaudio/$MiniaudioVersion/miniaudio.h"
$MiniaudioSha256 = 'ac7af4de748b7e26b777f37e01cee313a308a7296a3eb080e2906b320cc55c89'
$MiniaudioLicenseUrl = "https://raw.githubusercontent.com/mackron/miniaudio/$MiniaudioVersion/LICENSE"
$MiniaudioLicenseSha256 = '457f1b500e0adf6bc059edddfa78a2f62012e7c3bb43476c20e0bd23b25ba0eb'

function Get-Verified([string]$Url, [string]$Destination, [string]$Sha256) {
    if (-not (Test-Path -LiteralPath $Destination -PathType Leaf)) {
        Write-Log "downloading $(Split-Path -Leaf $Destination)"
        Invoke-WebRequest -Uri $Url -OutFile "$Destination.part"
        Move-Item "$Destination.part" $Destination
    }
    $actual = (Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $Sha256) {
        Remove-Item -LiteralPath $Destination -Force
        Stop-Recipe "checksum mismatch for $(Split-Path -Leaf $Destination); removed the download"
    }
}

function Expand-TarGz([string]$Archive, [string]$Into) {
    Invoke-Checked 'tar' @('-xzf', $Archive, '-C', $Into)
}

Get-Verified $FfmpegUrl (Join-Path $Downloads $FfmpegZip) $FfmpegSha256
Get-Verified $ZlibUrl (Join-Path $Downloads "zlib-$ZlibVersion.tar.gz") $ZlibSha256
Get-Verified $OpensslUrl (Join-Path $Downloads "openssl-$OpensslVersion.tar.gz") $OpensslSha256
Get-Verified $MiniaudioUrl (Join-Path $Downloads 'miniaudio.h') $MiniaudioSha256
Get-Verified $MiniaudioLicenseUrl (Join-Path $Downloads 'miniaudio.LICENSE') $MiniaudioLicenseSha256

# The FFmpeg shared zip is a self-contained prefix: include/, lib/ (MSVC import libraries), bin/
# (the DLLs the package stages) and LICENSE.txt.
$ffmpegTree = Join-Path $Src 'ffmpeg-win64'
if (-not (Test-Path -LiteralPath (Join-Path $Prefix 'lib\avformat.lib') -PathType Leaf)) {
    Write-Log 'staging the verified FFmpeg shared prefix'
    Remove-Item -Recurse -Force $ffmpegTree -ErrorAction SilentlyContinue
    Expand-Archive -LiteralPath (Join-Path $Downloads $FfmpegZip) -DestinationPath $ffmpegTree
    $inner = Get-ChildItem $ffmpegTree -Directory | Select-Object -First 1
    foreach ($dir in 'include', 'lib', 'bin') {
        Copy-Item -Recurse -Force (Join-Path $inner.FullName $dir) (Join-Path $Prefix $dir)
    }
    Copy-Item -Force (Join-Path $inner.FullName 'LICENSE.txt') (Join-Path $Prefix 'LICENSE.txt')
    Remove-Item -Recurse -Force $ffmpegTree
}
foreach ($required in 'lib\avformat.lib', 'lib\avcodec.lib', 'lib\avutil.lib', 'lib\swscale.lib', 'bin\avformat-62.dll') {
    if (-not (Test-Path -LiteralPath (Join-Path $Prefix $required) -PathType Leaf)) {
        Stop-Recipe "the FFmpeg prefix is missing $required"
    }
}

# The MSVC toolchain comes from Visual Studio 2022, located through vswhere like build-tdjni.ps1.
$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
if (-not (Test-Path -LiteralPath $vswhere -PathType Leaf)) {
    Stop-Recipe 'vswhere.exe was not found; install Visual Studio 2022 with the Desktop development with C++ workload'
}
$vsPath = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (-not $vsPath) {
    Stop-Recipe 'no Visual Studio 2022 installation with the x64 MSVC toolset was found'
}
$vcvars = Join-Path $vsPath 'VC\Auxiliary\Build\vcvars64.bat'
if (-not (Test-Path -LiteralPath $vcvars -PathType Leaf)) {
    Stop-Recipe "missing $vcvars"
}

# zlib: a static MSVC archive for TDLight (zlibstatic.lib). Dynamic CRT keeps one CRT across the closure.
if (-not (Test-Path -LiteralPath (Join-Path $ZlibStatic 'lib\zlibstatic.lib') -PathType Leaf)) {
    Write-Log "building zlib $ZlibVersion (static, MSVC)"
    Expand-TarGz (Join-Path $Downloads "zlib-$ZlibVersion.tar.gz") $Src
    $zlibSrc = Join-Path $Src "zlib-$ZlibVersion"
    $zlibBuild = Join-Path $Src 'zlib-build'
    Remove-Item -Recurse -Force $zlibBuild -ErrorAction SilentlyContinue
    Invoke-Checked 'cmake' @('-S', $zlibSrc, '-B', $zlibBuild,
        '-DCMAKE_BUILD_TYPE=Release', '-DBUILD_SHARED_LIBS=OFF',
        ('-DCMAKE_INSTALL_PREFIX=' + $ZlibStatic),
        '-DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreadedDLL')
    Invoke-Checked 'cmake' @('--build', $zlibBuild, '--config', 'Release', '--target', 'zlibstatic')
    Invoke-Checked 'cmake' @('--install', $zlibBuild, '--config', 'Release')
    if (-not (Test-Path -LiteralPath (Join-Path $ZlibStatic 'include\zlib.h') -PathType Leaf)) {
        New-Item -ItemType Directory -Force -Path (Join-Path $ZlibStatic 'include') | Out-Null
        Copy-Item (Join-Path $zlibSrc 'zlib.h') (Join-Path $ZlibStatic 'include\zlib.h')
        Copy-Item (Join-Path $zlibBuild 'zconf.h') (Join-Path $ZlibStatic 'include\zconf.h')
    }
}

# OpenSSL 3.5.9 static for TDLight, built with MSVC through the documented perl/nmake path. no-asm
# keeps the recipe free of a NASM prerequisite; TDLight's TLS use does not need asm acceleration.
if (-not (Test-Path -LiteralPath (Join-Path $OpensslStatic 'lib\libcrypto.lib') -PathType Leaf)) {
    Write-Log "building static OpenSSL $OpensslVersion for TDLight (MSVC)"
    Expand-TarGz (Join-Path $Downloads "openssl-$OpensslVersion.tar.gz") $Src
    $opensslSrc = Join-Path $Src "openssl-$OpensslVersion"
    $bat = Join-Path $Logs 'build-openssl.bat'
    @(
        ('call "' + $vcvars + '"'),
        ('cd /d "' + $opensslSrc + '"'),
        ('perl Configure VC-WIN64A no-shared no-asm no-tests --prefix="' + $OpensslStatic + '" --openssldir="' + $OpensslStatic + '\ssl"'),
        'nmake',
        'nmake install_sw'
    ) | Set-Content -Path $bat -Encoding ascii
    Invoke-Checked 'cmd.exe' @('/c', $bat) *> (Join-Path $Logs 'openssl-static.log')
    foreach ($required in 'lib\libcrypto.lib', 'lib\libssl.lib', 'include\openssl\ssl.h') {
        if (-not (Test-Path -LiteralPath (Join-Path $OpensslStatic $required) -PathType Leaf)) {
            Stop-Recipe "the static OpenSSL prefix is missing $required; see $Logs\openssl-static.log"
        }
    }
}

$miniaudioDir = Join-Path $Src "miniaudio-$MiniaudioVersion"
New-Item -ItemType Directory -Force -Path $miniaudioDir | Out-Null
Copy-Item (Join-Path $Downloads 'miniaudio.h') (Join-Path $miniaudioDir 'miniaudio.h')
Copy-Item (Join-Path $Downloads 'miniaudio.LICENSE') (Join-Path $miniaudioDir 'LICENSE')

Write-Log "prefix: $Prefix"
Write-Log "static OpenSSL for TDLight: $OpensslStatic"
Write-Log "static zlib for TDLight: $ZlibStatic"
Write-Log "miniaudio: $miniaudioDir\miniaudio.h"
