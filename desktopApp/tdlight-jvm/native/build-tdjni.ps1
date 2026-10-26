#
# ArchiveTune (2026)
# © Rukamori — github.com/rukamori
# GPL-3.0 License | Contributors: see git history
# Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
# Portions © vossgraves — github.com/vossgraves
#
# Builds tdjni.dll for Windows x64 with MSVC from the tdlight-team/tdlight commit that generated the Java binding in
# ../src. It is the Windows counterpart of build-tdjni.sh and follows the same rules: the binding and the native library
# come from one commit, a checkout that is not that commit is refused, a missing static dependency prefix stops the
# build, and the library must yield exactly one tdjni.dll whose imports stay inside the Windows system set and the MSVC
# runtime. Nothing is downloaded beyond the pinned source. OpenSSL and zlib are explicit inputs, so the caller chooses
# reviewed static builds rather than whatever a machine carries. Windows has no system zlib to rely on.
#
# Outputs stay under desktopApp/build/tdlight-jvm, which Git ignores. Set ARCHIVETUNE_TDLIGHT_BUILD to move the tree and
# ARCHIVETUNE_TDLIGHT_JOBS to change parallelism.
#
# Status: written from the pin and not yet run. The Visual Studio generator name, the CMake cache variables for the
# static dependencies and the dumpbin import set are taken from the TDLib layout and the CMake find modules. They must be
# confirmed on the first real run, and the script stops on any difference rather than guessing.

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# The commit behind Client.java, TdApi.java and the Android libtdjni. Changing it means regenerating the Java binding from
# the new tree first, in the same commit, and rebuilding the Linux, macOS and Windows libraries from that commit.
$TdlightRepository = 'https://github.com/tdlight-team/tdlight.git'
$TdlightCommit = '2b51b33c8a905be96c40508e68485f8a719e26a3'
$TdlightJniTarget = 'tdjni'
$BoostLicenseTitle = 'Boost Software License - Version 1.0'
$HostName = 'windows-x64'
$LibraryName = 'tdjni.dll'

function Write-Log([string]$Message) {
    Write-Host "[tdlight-jni] $Message"
}

function Stop-Recipe([string]$Message) {
    throw "[tdlight-jni] error: $Message"
}

# Windows PowerShell 5.1 does not stop on a failing native command, so every call goes through these helpers.
function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) {
        Stop-Recipe "$Program exited with status $LASTEXITCODE"
    }
}

function Get-Output([string]$Program, [string[]]$Arguments) {
    $output = & $Program @Arguments
    if ($LASTEXITCODE -ne 0) {
        Stop-Recipe "$Program exited with status $LASTEXITCODE"
    }
    return $output
}

# The host names match the directory that desktopApp/build.gradle.kts stages for Windows x64.
if ([System.Environment]::OSVersion.Platform -ne 'Win32NT') {
    Stop-Recipe 'this recipe covers Windows; Linux and macOS use build-tdjni.sh'
}
$hostArchitecture = [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture
if ($hostArchitecture -ne [System.Runtime.InteropServices.Architecture]::X64) {
    Stop-Recipe 'Windows packages are staged as windows-x64 only; no arm64 recipe exists'
}

$Component = Split-Path -Parent $PSScriptRoot
$DesktopApp = Split-Path -Parent $Component
$BuildRoot = $env:ARCHIVETUNE_TDLIGHT_BUILD
if (-not $BuildRoot) {
    $BuildRoot = Join-Path (Join-Path $DesktopApp 'build') 'tdlight-jvm'
}
$Jobs = 2
if ($env:ARCHIVETUNE_TDLIGHT_JOBS) {
    $Jobs = [int]$env:ARCHIVETUNE_TDLIGHT_JOBS
}
$Source = Join-Path $BuildRoot 'src'
$CmakeBuild = Join-Path $BuildRoot 'cmake'
$Stage = Join-Path $BuildRoot 'stage'

# Prerequisites are checked before anything is fetched or built. The static dependency prefixes are explicit inputs.
if (-not $env:TDLIGHT_OPENSSL_PREFIX -or -not (Test-Path -LiteralPath $env:TDLIGHT_OPENSSL_PREFIX -PathType Container)) {
    Stop-Recipe 'set TDLIGHT_OPENSSL_PREFIX to a static OpenSSL 3 prefix built with the dynamic CRT (see the README)'
}
$OpensslPrefix = $env:TDLIGHT_OPENSSL_PREFIX
$OpensslCrypto = Join-Path $OpensslPrefix 'lib\libcrypto.lib'
$OpensslSsl = Join-Path $OpensslPrefix 'lib\libssl.lib'
$OpensslHeader = Join-Path $OpensslPrefix 'include\openssl\ssl.h'
foreach ($required in @($OpensslCrypto, $OpensslSsl, $OpensslHeader)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        Stop-Recipe "missing $required; the static OpenSSL prefix must contain lib\libcrypto.lib, lib\libssl.lib and include\openssl\ssl.h"
    }
}

if (-not $env:TDLIGHT_ZLIB_PREFIX -or -not (Test-Path -LiteralPath $env:TDLIGHT_ZLIB_PREFIX -PathType Container)) {
    Stop-Recipe 'set TDLIGHT_ZLIB_PREFIX to a static zlib prefix built with the dynamic CRT (see the README)'
}
$ZlibPrefix = $env:TDLIGHT_ZLIB_PREFIX
$ZlibHeader = Join-Path $ZlibPrefix 'include\zlib.h'
if (-not (Test-Path -LiteralPath $ZlibHeader -PathType Leaf)) {
    Stop-Recipe "missing $ZlibHeader; the static zlib prefix must contain include\zlib.h"
}
$ZlibLibrary = Join-Path $ZlibPrefix 'lib\zlibstatic.lib'
if (-not (Test-Path -LiteralPath $ZlibLibrary -PathType Leaf)) {
    $ZlibLibrary = Join-Path $ZlibPrefix 'lib\zlib.lib'
}
if (-not (Test-Path -LiteralPath $ZlibLibrary -PathType Leaf)) {
    Stop-Recipe 'the static zlib prefix must contain lib\zlibstatic.lib or lib\zlib.lib'
}

# The JNI side of TD_ENABLE_JNI includes jni.h and the Windows jni_md.h from the JDK that JAVA_HOME names.
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'include\win32\jni_md.h') -PathType Leaf)) {
    Stop-Recipe 'set JAVA_HOME to a JDK 21 installation; its include\win32\jni_md.h is needed for the JNI side'
}

# Visual Studio 2022 supplies the x64 MSVC toolchain, the Visual Studio generator, cmake and dumpbin. vswhere locates it
# so that no particular install path is assumed.
$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
if (-not (Test-Path -LiteralPath $vswhere -PathType Leaf)) {
    Stop-Recipe 'vswhere.exe was not found; install Visual Studio 2022 with the Desktop development with C++ workload'
}
$visualStudio = [string](Get-Output $vswhere @('-latest', '-products', '*', '-version', '[17.0,18.0)', '-requires', 'Microsoft.VisualStudio.Component.VC.Tools.x86.x64', '-property', 'installationPath') | Select-Object -First 1)
if (-not $visualStudio) {
    Stop-Recipe 'Visual Studio 2022 with the x64 MSVC tools was not found'
}
$devShell = Join-Path $visualStudio 'Common7\Tools\Launch-VsDevShell.ps1'
if (-not (Test-Path -LiteralPath $devShell -PathType Leaf)) {
    Stop-Recipe "$devShell was not found; repair the Visual Studio 2022 installation"
}
. $devShell -Arch amd64 -HostArch amd64 -SkipAutomaticLocation

foreach ($tool in @('cmake', 'git', 'dumpbin')) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        Stop-Recipe "$tool is not on PATH after loading the Visual Studio developer environment"
    }
}

# Fetch exactly the pinned commit. The checkout lives under the build tree, so a dirty checkout is a stale build and is
# refused, never cleaned in place. --quiet keeps git progress off stderr, which Windows PowerShell 5.1 can report as an error.
New-Item -ItemType Directory -Force -Path $Source | Out-Null
if (-not (Test-Path -LiteralPath (Join-Path $Source '.git'))) {
    Invoke-Checked 'git' @('init', '--quiet', $Source)
    Invoke-Checked 'git' @('-C', $Source, 'remote', 'add', 'origin', $TdlightRepository)
}
Invoke-Checked 'git' @('-C', $Source, 'fetch', '--quiet', '--depth', '1', 'origin', $TdlightCommit)
Invoke-Checked 'git' @('-C', $Source, 'checkout', '--quiet', '--detach', $TdlightCommit)
$head = ([string](Get-Output 'git' @('-C', $Source, 'rev-parse', 'HEAD'))).Trim()
if ($head -ne $TdlightCommit) {
    Stop-Recipe "the checkout is $head, not $TdlightCommit"
}
$dirty = Get-Output 'git' @('-C', $Source, 'status', '--porcelain')
if ($dirty) {
    Stop-Recipe "$Source has local changes; delete that directory and rerun"
}
if (Test-Path -LiteralPath (Join-Path $Source '.gitmodules') -PathType Leaf) {
    Invoke-Checked 'git' @('-C', $Source, 'submodule', 'update', '--init', '--depth', '1', '--recursive', '--quiet')
}

# The Boost text is copied, never typed in, so the staged notice is the upstream file byte for byte.
# The pinned tree also ships example copies (e.g. example/uwp/LICENSE_1_0.txt); the package notice
# is always the root file.
$licenseHits = @(Get-ChildItem -LiteralPath $Source -File -Filter 'LICENSE_1_0.txt')
if ($licenseHits.Count -ne 1) {
    Stop-Recipe "expected exactly one LICENSE_1_0.txt at the root of $Source"
}
$licenseFile = $licenseHits[0].FullName
$titleLine = Get-Content -LiteralPath $licenseFile -TotalCount 1 -Encoding UTF8
if (-not $titleLine -or -not $titleLine.TrimStart([char]0xFEFF).StartsWith($BoostLicenseTitle, [System.StringComparison]::Ordinal)) {
    Stop-Recipe "$licenseFile does not start with '$BoostLicenseTitle'"
}

# Static OpenSSL and zlib are named explicitly so that FindOpenSSL and FindZLIB cannot pick a DLL import library. The
# dumpbin check below stops the build if a dynamic dependency slips through anyway. The dynamic CRT matches the default
# of the Visual Studio generator and the static dependency builds above.
$cmakeArguments = @(
    '-S', $Source,
    '-B', $CmakeBuild,
    '-G', 'Visual Studio 17 2022',
    '-A', 'x64',
    '-DTD_ENABLE_JNI=ON',
    ('-DOPENSSL_ROOT_DIR=' + $OpensslPrefix),
    '-DOPENSSL_USE_STATIC_LIBS=ON',
    ('-DOPENSSL_INCLUDE_DIR=' + (Join-Path $OpensslPrefix 'include')),
    ('-DOPENSSL_CRYPTO_LIBRARY=' + $OpensslCrypto),
    ('-DOPENSSL_SSL_LIBRARY=' + $OpensslSsl),
    ('-DZLIB_ROOT=' + $ZlibPrefix),
    '-DZLIB_USE_STATIC_LIBS=ON',
    ('-DZLIB_INCLUDE_DIR=' + (Join-Path $ZlibPrefix 'include')),
    ('-DZLIB_LIBRARY_RELEASE=' + $ZlibLibrary)
)
$cmakeArguments += ('-DCMAKE_INSTALL_PREFIX=' + (Join-Path $BuildRoot 'prefix'))
Invoke-Checked 'cmake' $cmakeArguments
Invoke-Checked 'cmake' @('--build', $CmakeBuild, '--config', 'Release', '--target', 'install', '--parallel', [string]$Jobs)

# The pinned tree defines tdjni only in the example/java project, which find_package(Td)s the installed TDLib — so the
# second stage configures that project against the prefix and compiles the JNI shim.
$CmakeBuildJava = Join-Path $BuildRoot 'cmake-java'
# The Java example locates its codegen binary and API scheme at <src>/td/bin — the layout the stage-one install
# produces. Configure a copy of the project with td junctioned to the prefix so the fetched tree stays pristine.
$JniSource = Join-Path $BuildRoot 'java-src'
if (Test-Path $JniSource) { Remove-Item -Recurse -Force $JniSource }
if (Test-Path $CmakeBuildJava) { Remove-Item -Recurse -Force $CmakeBuildJava }
Copy-Item -Recurse (Join-Path $Source 'example/java') $JniSource
New-Item -ItemType Junction -Path (Join-Path $JniSource 'td') -Target (Join-Path $BuildRoot 'prefix') | Out-Null
# td_jni.cpp calls the td_api JSON helpers, which live in tdjson_private — behind Td::TdJsonStatic — so the
# object-API build must link it alongside Td::TdStatic or the to_json_string symbols stay unresolved.
$JniLists = Join-Path $JniSource 'CMakeLists.txt'
$linkLine = (Get-Content -Raw $JniLists)
if (-not $linkLine.Contains('target_link_libraries(tdjni PRIVATE Td::TdStatic)')) {
    Stop-Recipe "$JniLists no longer links Td::TdStatic; review the pinned tdlight revision"
}
$linkLine = $linkLine -replace 'target_link_libraries\(tdjni PRIVATE Td::TdStatic\)', 'target_link_libraries(tdjni PRIVATE Td::TdStatic Td::TdJsonStatic)'
Set-Content -Path $JniLists -Value $linkLine -NoNewline
# Windows needs the jvm import library; leave the example's JAVA_JVM_LIBRARY link untouched here.
$jniArguments = @(
    '-S', $JniSource,
    '-B', $CmakeBuildJava,
    '-G', 'Visual Studio 17 2022',
    '-A', 'x64',
    ('-DTd_DIR=' + (Join-Path $BuildRoot 'prefix/lib/cmake/Td')),
    ('-DCMAKE_PREFIX_PATH=' + (Join-Path $BuildRoot 'prefix')),
    ('-DCMAKE_INSTALL_PREFIX=' + (Join-Path $BuildRoot 'java-output'))
)
Invoke-Checked 'cmake' $jniArguments
Invoke-Checked 'cmake' @('--build', $CmakeBuildJava, '--config', 'Release', '--target', $TdlightJniTarget, '--parallel', [string]$Jobs)

$libraryHits = @(Get-ChildItem -LiteralPath $CmakeBuildJava -Recurse -File -Filter $LibraryName)
if ($libraryHits.Count -ne 1) {
    Stop-Recipe "expected exactly one $LibraryName under $CmakeBuildJava"
}
$library = $libraryHits[0].FullName

# A static build must not import OpenSSL, zlib or any other DLL that the package does not stage. The allowed set is the
# Windows API surface, the universal CRT and the MSVC runtime that the packaged Java runtime is expected to carry.
$dependencyLines = @(Get-Output 'dumpbin' @('/nologo', '/dependents', $library))
$imports = @()
$inImports = $false
foreach ($line in $dependencyLines) {
    if ($line -match 'has the following dependencies') {
        $inImports = $true
        continue
    }
    if (-not $inImports) {
        continue
    }
    if ($line -match '^\s+(\S+\.dll)\s*$') {
        $imports += $Matches[1]
    } elseif ($line.Trim() -ne '') {
        $inImports = $false
    }
}
if ($imports.Count -eq 0) {
    Stop-Recipe 'dumpbin listed no imports for tdjni.dll; its output format differs from the expected one'
}
$allowedImports = '^(api-ms-win-|ext-ms-win-)|^(KERNEL32|ADVAPI32|USER32|GDI32|WS2_32|CRYPT32|BCRYPT|bcryptprimitives|SHELL32|OLE32|OLEAUT32|IPHLPAPI|MSWSOCK|SECUR32|NTDLL|UCRTBASE|VCRUNTIME140[A-Z0-9_]*|MSVCP140[A-Z0-9_]*)\.dll$'
$unexpected = @($imports | Where-Object { $_ -notmatch $allowedImports })
if ($unexpected.Count -gt 0) {
    Stop-Recipe ('tdjni.dll imports ' + ($unexpected -join ', ') + '; a static dependency was linked as a DLL, or a new dependency must be reviewed before it is staged')
}
Write-Log ('imports: ' + ($imports -join ', '))

$hostStage = Join-Path (Join-Path $Stage $HostName) 'native'
$noticeStage = Join-Path $Stage 'notices'
New-Item -ItemType Directory -Force -Path $hostStage | Out-Null
New-Item -ItemType Directory -Force -Path $noticeStage | Out-Null
Remove-Item -LiteralPath (Join-Path $hostStage $LibraryName) -Force -ErrorAction SilentlyContinue
Copy-Item -LiteralPath $library -Destination (Join-Path $hostStage $LibraryName) -Force
Copy-Item -LiteralPath $licenseFile -Destination (Join-Path $noticeStage 'TDLight-Boost-1.0-LICENSE.txt') -Force
Write-Log "pinned commit $TdlightCommit"
Write-Log "staged $(Join-Path $hostStage $LibraryName)"
Write-Log "staged $(Join-Path $noticeStage 'TDLight-Boost-1.0-LICENSE.txt')"
