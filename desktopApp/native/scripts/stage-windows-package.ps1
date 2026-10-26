#
# ArchiveTune (2026)
# © Rukamori — github.com/rukamori
# GPL-3.0 License | Contributors: see git history
# Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
# Portions © vossgraves — github.com/vossgraves
#
# Stages the host-built Windows media closure beside the app image: the engine install and
# every FFmpeg DLL from the prefix. Windows resolves DLLs from the application directory,
# so no loader-path rewriting exists. Every staged DLL's PE machine type is checked so an
# x64 package can never ship a foreign-architecture binary; a missing engine or FFmpeg
# closure stops the package instead of shipping without playback.

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Stop-Recipe([string]$Message) {
    throw "[native-stage] error: $Message"
}

$Component = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$Build = Join-Path $Component 'build'
$Engine = Join-Path $Build 'native\engine-build'
$PrefixBin = Join-Path $Build 'native\prefix\bin'
$Destination = Join-Path $Build 'distribution-resources\windows-x64\native'
New-Item -ItemType Directory -Force -Path $Destination | Out-Null

& cmake --install $Engine --config Release --prefix $Destination
if ($LASTEXITCODE -ne 0) { Stop-Recipe "cmake --install exited with $LASTEXITCODE" }

$dlls = Get-ChildItem -LiteralPath $PrefixBin -Filter '*.dll' -File -ErrorAction SilentlyContinue
if (-not $dlls) {
    Stop-Recipe "no FFmpeg DLLs under $PrefixBin; run build-native-deps.ps1 first"
}
foreach ($dll in $dlls) {
    Copy-Item -LiteralPath $dll.FullName -Destination (Join-Path $Destination $dll.Name)
}
if (-not (Test-Path -LiteralPath (Join-Path $Destination 'avformat-62.dll') -PathType Leaf)) {
    Stop-Recipe 'the FFmpeg closure is missing avformat-62.dll'
}
if (-not (Test-Path -LiteralPath (Join-Path $Destination 'archivetune_engine.dll') -PathType Leaf)) {
    Stop-Recipe 'the engine install produced no archivetune_engine.dll'
}

function Get-PeMachine([string]$Path) {
    $stream = [System.IO.File]::OpenRead($Path)
    try {
        $reader = New-Object System.IO.BinaryReader($stream)
        $stream.Position = 0x3C
        $peOffset = $reader.ReadUInt32()
        $stream.Position = $peOffset
        if ($reader.ReadUInt32() -ne 0x00004550) { return $null }
        return $reader.ReadUInt16()
    } finally {
        $stream.Close()
    }
}
foreach ($file in Get-ChildItem -LiteralPath $Destination -Filter '*.dll' -File) {
    # 0x8664 = IMAGE_FILE_MACHINE_AMD64; staging is windows-x64 only.
    if ((Get-PeMachine $file.FullName) -ne 0x8664) {
        Stop-Recipe "$($file.Name) is not an x64 binary"
    }
    Write-Host "[native-stage] staged native: $($file.Name)"
}

$notices = Join-Path $Build 'distribution-resources\common\notices'
New-Item -ItemType Directory -Force -Path $notices | Out-Null
$repoRoot = Split-Path -Parent $Component
$srcDir = Join-Path $Build 'native\src'
foreach ($pair in @(
    @((Join-Path $repoRoot 'LICENSE'), 'ArchiveTune-GPL-3.0.txt'),
    @((Join-Path $Build 'native\prefix\LICENSE.txt'), 'FFmpeg-LGPL-3.0.txt'),
    @((Join-Path $srcDir 'openssl-3.5.9\LICENSE.txt'), 'OpenSSL-3.5.9.txt'),
    @((Join-Path $srcDir "miniaudio-0.11.25\LICENSE"), 'miniaudio.txt')
)) {
    if (-not (Test-Path -LiteralPath $pair[0] -PathType Leaf)) {
        Stop-Recipe "required license missing: $($pair[0])"
    }
    Copy-Item -LiteralPath $pair[0] -Destination (Join-Path $notices $pair[1])
}
