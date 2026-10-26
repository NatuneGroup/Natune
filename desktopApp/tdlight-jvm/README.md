# desktopApp/tdlight-jvm

The desktop TDLight SDK component. It holds the generated `org.drinkless.tdlib` binding (`Client`, `TdApi`) as a plain
JVM library, the notice that goes with it, and the recipes for the matching desktop `libtdjni`. It does not depend on
Android, and the Android app does not depend on it.

## Pins

| Item | Value |
|---|---|
| Source | https://github.com/tdlight-team/tdlight |
| Commit | `2b51b33c8a905be96c40508e68485f8a719e26a3` |
| Base | TDLib 1.8.66 |
| License | Boost Software License 1.0, see `notices/TDLight-Boost-1.0.txt` |

`src/main/java/org/drinkless/tdlib/` is a byte copy of `app/src/main/java/org/drinkless/tdlib/` (`Client.java`,
`TdApi.java`). The Android copy stays the Android pin and is not modified here. A schema bump changes both copies in one
commit: replace both files from the new commit's generated binding, move the commit in both recipes, and rebuild the
native library for every host from that commit. The binding and the library must always come from the same commit,
because a mismatch fails at run time with no compile-time signal.

## How the desktop uses it

- `desktopApp/src/jvmMain/kotlin/moe/rukamori/archivetune/desktop/playback/TdlightFileOperations.kt` implements
  `TdlibFileOperations` over an injected `Client`.
- `TdlightNativeLibrary.ensureLoaded()` loads `libtdjni` by absolute path from the packaged `native/` resources, and
  falls back to the name. Call it before the first `Client` is created.
- The session owner creates the client with `Client.create`, supplies its handlers, and closes it by sending
  `TdApi.Close`. One client serves one Telegram session. The adapter never creates or closes a client.
- When the library was loaded by path, the vendored `Client` static initializer still tries the name, prints one
  `UnsatisfiedLinkError` trace, and carries on. The native methods then resolve against the library already loaded.

## Building libtdjni

Each host has one recipe. Both build the `tdjni` target of the pinned commit and stage the same two files under
`desktopApp/build/tdlight-jvm/stage/`: the host's library, and `notices/TDLight-Boost-1.0-LICENSE.txt`, copied verbatim
from the pinned tree's `LICENSE_1_0.txt`.

| Host | Recipe | Staged library |
|---|---|---|
| Linux x86_64 | `native/build-tdjni.sh` | `stage/linux-x64/native/libtdjni.so` |
| macOS arm64 | `native/build-tdjni.sh` | `stage/macos-arm64/native/libtdjni.dylib` |
| macOS x86_64 | `native/build-tdjni.sh` | `stage/macos-x64/native/libtdjni.dylib` |
| Windows x64 | `native/build-tdjni.ps1` | `stage/windows-x64/native/tdjni.dll` |

The packaging task in `desktopApp/build.gradle.kts` refuses to stage a desktop package until the host's library and the
notice both exist. It names the recipe for the host that is missing.

### Linux and macOS

```
TDLIGHT_OPENSSL_PREFIX=/path/to/static/openssl bash desktopApp/tdlight-jvm/native/build-tdjni.sh
```

The caller supplies a static OpenSSL prefix built for the host, so the staged library carries no system OpenSSL
dependency. The script refuses a dirty checkout, a checkout that is not the pinned commit, and a build that does not
yield exactly one library. Outputs stay under `desktopApp/build/tdlight-jvm`, which Git ignores.

### Windows x64

From a PowerShell prompt in the repository root:

```
$env:TDLIGHT_OPENSSL_PREFIX = 'C:\deps\openssl-static'
$env:TDLIGHT_ZLIB_PREFIX = 'C:\deps\zlib-static'
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21'
powershell -ExecutionPolicy Bypass -File desktopApp\tdlight-jvm\native\build-tdjni.ps1
```

Prerequisites. The script checks each one before it fetches or builds anything:

- Visual Studio 2022 (17.x) with the Desktop development with C++ workload, including the x64 MSVC tools. The script
  finds it with `vswhere` and loads its developer environment, which provides `cmake`, `dumpbin` and the Visual Studio
  generator.
- Git on `PATH`.
- A JDK 21 in `JAVA_HOME`. Its `include\win32\jni_md.h` is needed for the JNI side.
- A static OpenSSL 3 build for x64 MSVC, with the dynamic CRT (`/MD`). `TDLIGHT_OPENSSL_PREFIX` must contain
  `lib\libcrypto.lib`, `lib\libssl.lib` and `include\openssl\ssl.h`. OpenSSL's own Windows instructions produce this
  with `perl Configure VC-WIN64A no-shared --prefix=<dir>`, `nmake` and `nmake install_sw`.
- A static zlib for x64 MSVC, with the dynamic CRT. Windows has no system zlib, so the caller supplies it.
  `TDLIGHT_ZLIB_PREFIX` must contain `include\zlib.h` and `lib\zlibstatic.lib` or `lib\zlib.lib`.
- Any generator tool that the pinned tree's own build requires. The CMake configure step stops with the name of a
  missing tool.

The script writes the pinned commit's `tdjni.dll` to `desktopApp/build/tdlight-jvm/stage/windows-x64/native/` and the
Boost notice to `stage/notices/`. It stops, without staging, when the checkout is not the pinned commit or has local
changes, when the build does not yield exactly one `tdjni.dll`, and when `dumpbin /dependents` lists an import outside
the Windows API set, the universal CRT, and the MSVC runtime (`VCRUNTIME140*`, `MSVCP140*`). A third-party import means a
static dependency was linked as a DLL. Staging that DLL is then a packaging decision, not an automatic copy.

The MSVC runtime imports are allowed because the packaged Java runtime normally carries those DLLs. The first real run
must confirm that the packaged runtime contains them. If it does not, staging them is a packaging decision that brings a
Microsoft redistribution notice with it.

## Status

Source only. Neither recipe has been run, and no native artifact, digest or build result exists yet. The CMake options,
the target name, the license file location, the static OpenSSL and zlib variables, the Visual Studio generator name and
the dumpbin import set come from the pin, the TDLib layout and the CMake find modules. Each recipe stops on any mismatch
rather than guessing, and the first real run on each host is the confirmation.
