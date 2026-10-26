#!/usr/bin/env bash
#
# ArchiveTune (2026)
# © Rukamori — github.com/rukamori
# GPL-3.0 License | Contributors: see git history
# Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
# Portions © vossgraves — github.com/vossgraves
#
# Builds libtdjni for the current host from the tdlight-team/tdlight commit that generated the Java binding in ../src.
# The binding and the native library must come from one commit: a TDLib built from another tree disagrees with the TL
# schema in TdApi.java, and nothing warns at compile time. The script refuses a checkout that is not that commit, a
# missing static OpenSSL prefix, a build that does not yield exactly one tdjni library, and a source tree without the
# Boost license text.
#
# Nothing here downloads anything beyond the pinned source. OpenSSL is an explicit input, so the caller chooses a
# reviewed static build rather than whatever the host carries. Outputs stay under desktopApp/build/tdlight-jvm, which
# Git ignores. Set ARCHIVETUNE_TDLIGHT_BUILD to move the tree and ARCHIVETUNE_TDLIGHT_JOBS to change parallelism.
#
# Status: written from the pin and not yet run. The CMake option, the target name and the license file location are
# taken from the TDLib layout and must be confirmed against the pinned tree on the first real run. The script stops on
# any difference rather than guessing.

set -euo pipefail

# The commit behind Client.java, TdApi.java and the Android libtdjni. Changing it means regenerating the Java binding
# from the new tree first, in the same commit.
TDLIGHT_REPOSITORY="https://github.com/tdlight-team/tdlight.git"
TDLIGHT_COMMIT="2b51b33c8a905be96c40508e68485f8a719e26a3"
TDLIGHT_JNI_TARGET="tdjni"
BOOST_LICENSE_TITLE="Boost Software License - Version 1.0"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPONENT="$(cd "$HERE/.." && pwd)"
BUILD="${ARCHIVETUNE_TDLIGHT_BUILD:-$(cd "$COMPONENT/.." && pwd)/build/tdlight-jvm}"
JOBS="${ARCHIVETUNE_TDLIGHT_JOBS:-2}"
SRC="$BUILD/src"
CMAKE_BUILD="$BUILD/cmake"
STAGE="$BUILD/stage"

log() { printf '[tdlight-jni] %s\n' "$*"; }
die() { printf '[tdlight-jni] error: %s\n' "$*" >&2; exit 1; }

# The host names match the directories that desktopApp/build.gradle.kts stages for each host.
case "$(uname -s)" in
    Linux)
        case "$(uname -m)" in
            x86_64) HOST_NAME="linux-x64" ;;
            aarch64 | arm64) HOST_NAME="linux-arm64" ;;
            *) die "unsupported Linux architecture $(uname -m)" ;;
        esac
        LIBRARY_NAME="libtdjni.so"
        ;;
    Darwin)
        case "$(uname -m)" in
            arm64) HOST_NAME="macos-arm64" ;;
            x86_64) HOST_NAME="macos-x64" ;;
            *) die "unsupported macOS architecture $(uname -m)" ;;
        esac
        LIBRARY_NAME="libtdjni.dylib"
        ;;
    *)
        die "this recipe covers Linux and macOS; on Windows run build-tdjni.ps1, which builds the same commit with MSVC"
        ;;
esac

[ -n "${TDLIGHT_OPENSSL_PREFIX:-}" ] && [ -d "$TDLIGHT_OPENSSL_PREFIX" ] \
    || die "set TDLIGHT_OPENSSL_PREFIX to a static OpenSSL prefix built for this host (see the README)"

# Fetch exactly the pinned commit. The checkout lives under the build tree, so a dirty checkout is a stale build and is
# refused, never cleaned in place.
mkdir -p "$SRC"
if [ ! -d "$SRC/.git" ]; then
    git init --quiet "$SRC"
    git -C "$SRC" remote add origin "$TDLIGHT_REPOSITORY"
fi
git -C "$SRC" fetch --quiet --depth 1 origin "$TDLIGHT_COMMIT"
git -C "$SRC" checkout --quiet --detach "$TDLIGHT_COMMIT"
[ "$(git -C "$SRC" rev-parse HEAD)" = "$TDLIGHT_COMMIT" ] || die "the checkout is not $TDLIGHT_COMMIT"
[ -z "$(git -C "$SRC" status --porcelain)" ] || die "$SRC has local changes; delete that directory and rerun"
if [ -f "$SRC/.gitmodules" ]; then
    git -C "$SRC" submodule update --init --depth 1 --recursive
fi

# The Boost text is copied, never typed in, so the staged notice is the upstream file byte for byte.
# The pinned tree also ships example copies (e.g. example/uwp/LICENSE_1_0.txt); the package notice
# is always the root file.
LICENSE_FILES="$(find "$SRC" -maxdepth 1 -name LICENSE_1_0.txt -type f)"
[ "$(printf '%s\n' "$LICENSE_FILES" | grep -c .)" = "1" ] \
    || die "expected exactly one LICENSE_1_0.txt at the root of $SRC"
LICENSE_FILE="$LICENSE_FILES"
case "$(head -n 1 "$LICENSE_FILE")" in
    "$BOOST_LICENSE_TITLE"*) ;;
    *) die "$LICENSE_FILE does not start with '$BOOST_LICENSE_TITLE'" ;;
esac

# TD_ENABLE_JNI builds the Java bindings' native side. Static OpenSSL keeps the staged library free of a system OpenSSL
# dependency, which the Android build also avoids. Position-independent code is needed to link static archives into a
# shared library. The pinned tree defines tdjni only in the example/java project, which find_package(Td)s an installed
# TDLib — so stage one builds and installs the library, stage two compiles the JNI shim against it.
INSTALL_PREFIX="$BUILD/prefix"
cmake -S "$SRC" -B "$CMAKE_BUILD" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DTD_ENABLE_JNI=ON \
    -DOPENSSL_ROOT_DIR="$TDLIGHT_OPENSSL_PREFIX" \
    -DOPENSSL_USE_STATIC_LIBS=ON \
    -DCMAKE_INSTALL_PREFIX="$INSTALL_PREFIX"
cmake --build "$CMAKE_BUILD" --config Release --target install --parallel "$JOBS"

cmake -S "$SRC/example/java" -B "$CMAKE_BUILD-java" \
    -DCMAKE_BUILD_TYPE=Release \
    -DTd_DIR="$INSTALL_PREFIX/lib/cmake/Td" \
    -DCMAKE_PREFIX_PATH="$INSTALL_PREFIX"
cmake --build "$CMAKE_BUILD-java" --config Release --target "$TDLIGHT_JNI_TARGET" --parallel "$JOBS"

LIBRARY_HITS="$(find "$CMAKE_BUILD-java" -name "$LIBRARY_NAME" -type f)"
[ "$(printf '%s\n' "$LIBRARY_HITS" | grep -c .)" = "1" ] \
    || die "expected exactly one $LIBRARY_NAME under $CMAKE_BUILD-java"

mkdir -p "$STAGE/$HOST_NAME/native" "$STAGE/notices"
rm -f "$STAGE/$HOST_NAME/native/$LIBRARY_NAME"
cp "$LIBRARY_HITS" "$STAGE/$HOST_NAME/native/$LIBRARY_NAME"
cp "$LICENSE_FILE" "$STAGE/notices/TDLight-Boost-1.0-LICENSE.txt"
log "pinned commit $TDLIGHT_COMMIT"
log "staged $STAGE/$HOST_NAME/native/$LIBRARY_NAME"
log "staged $STAGE/notices/TDLight-Boost-1.0-LICENSE.txt"
