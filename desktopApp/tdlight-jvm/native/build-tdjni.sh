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
# The CMake option, target name and license file location are checked against the pinned tree. The script stops on
# a source/layout mismatch rather than guessing. Windows runs in MSYS2 MINGW64, never against an MSVC OpenSSL prefix.

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
# Nonempty arrays also work under macOS's Bash 3.2 with nounset enabled.
CMAKE_HOST_ARGS=(-DCMAKE_BUILD_TYPE=Release)
CMAKE_DEPENDENCY_ARGS=(-DOPENSSL_USE_STATIC_LIBS=ON)
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
        if [ "$HOST_NAME" = macos-arm64 ]; then
            CMAKE_HOST_ARGS+=(-DCMAKE_OSX_ARCHITECTURES=arm64)
        else
            CMAKE_HOST_ARGS+=(-DCMAKE_OSX_ARCHITECTURES=x86_64)
        fi
        ;;
    MINGW64* | MSYS*)
        [ "$(uname -m)" = x86_64 ] || die "the MinGW recipe supports Windows x64 only"
        HOST_NAME="windows-x64"
        LIBRARY_NAME="tdjni.dll"
        CMAKE_HOST_ARGS+=(-G "MinGW Makefiles" "-DCMAKE_EXE_LINKER_FLAGS=-static")
        [ -n "${TDLIGHT_ZLIB_PREFIX:-}" ] && [ -f "$TDLIGHT_ZLIB_PREFIX/lib/libz.a" ] \
            || die "set TDLIGHT_ZLIB_PREFIX to the MinGW zlib prefix containing lib/libz.a"
        CMAKE_DEPENDENCY_ARGS+=(
            "-DZLIB_INCLUDE_DIR=$TDLIGHT_ZLIB_PREFIX/include"
            "-DZLIB_LIBRARY=$TDLIGHT_ZLIB_PREFIX/lib/libz.a"
            -DZLIB_USE_STATIC_LIBS=ON
        )
        ;;
    *)
        die "this recipe covers Linux, macOS and MSYS2 MINGW64"
        ;;
esac

[ -n "${TDLIGHT_OPENSSL_PREFIX:-}" ] && [ -d "$TDLIGHT_OPENSSL_PREFIX" ] \
    || die "set TDLIGHT_OPENSSL_PREFIX to a static OpenSSL prefix built for this host (see the README)"
if [ "$HOST_NAME" = windows-x64 ]; then
    for archive in libcrypto.a libssl.a; do
        [ -f "$TDLIGHT_OPENSSL_PREFIX/lib/$archive" ] || die "missing MinGW static archive $archive"
    done
    CMAKE_DEPENDENCY_ARGS+=(
        "-DOPENSSL_CRYPTO_LIBRARY=$TDLIGHT_OPENSSL_PREFIX/lib/libcrypto.a"
        "-DOPENSSL_SSL_LIBRARY=$TDLIGHT_OPENSSL_PREFIX/lib/libssl.a"
    )
fi

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
cmake "${CMAKE_HOST_ARGS[@]}" "${CMAKE_DEPENDENCY_ARGS[@]}" -S "$SRC" -B "$CMAKE_BUILD" \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DTD_ENABLE_JNI=ON \
    -DOPENSSL_ROOT_DIR="$TDLIGHT_OPENSSL_PREFIX" \
    -DCMAKE_INSTALL_PREFIX="$INSTALL_PREFIX"
cmake --build "$CMAKE_BUILD" --config Release --target install --parallel "$JOBS"

# The Java example locates its codegen binary and API scheme at $SRC/td/bin — that is the layout the stage-one install
# produces, so a copy of the project with td symlinked to the prefix configures without touching the fetched tree.
JNI_SRC="$BUILD/java-src"
rm -rf "$JNI_SRC" "$CMAKE_BUILD-java"
cp -R "$SRC/example/java" "$JNI_SRC"
if [ "$HOST_NAME" = windows-x64 ]; then
    # MSYS symlinks are not directories to native Windows CMake. The installed codegen tool also needs its .exe suffix.
    cp -R "$INSTALL_PREFIX" "$JNI_SRC/td"
    sed -i.bak 's@td/bin/td_generate_java_api@td/bin/td_generate_java_api.exe@g' "$JNI_SRC/CMakeLists.txt"
    rm "$JNI_SRC/CMakeLists.txt.bak"
else
    ln -s "$INSTALL_PREFIX" "$JNI_SRC/td"
fi
# td_jni.cpp calls the td_api JSON helpers, which live in tdjson_private — behind Td::TdJsonStatic — so the
# object-API build must link it alongside Td::TdStatic or the to_json_string symbols stay unresolved.
if ! grep -q 'target_link_libraries(tdjni PRIVATE Td::TdStatic)' "$JNI_SRC/CMakeLists.txt"; then
    die "$JNI_SRC/CMakeLists.txt no longer links Td::TdStatic; review the pinned tdlight revision"
fi
sed -i.bak 's/target_link_libraries(tdjni PRIVATE Td::TdStatic)/target_link_libraries(tdjni PRIVATE Td::TdStatic Td::TdJsonStatic)/' \
    "$JNI_SRC/CMakeLists.txt" && rm "$JNI_SRC/CMakeLists.txt.bak"
# The packaged JVM supplies JNI symbols at load time; on Apple/Linux an absolute libjvm link (or a second JDK's copy
# FindJNI may also emit) would break on machines without that exact JDK. Windows still needs the jvm import library.
cat >> "$JNI_SRC/CMakeLists.txt" <<'CMAKE'
if (NOT WIN32)
  get_target_property(_tdjni_links tdjni LINK_LIBRARIES)
  list(REMOVE_ITEM _tdjni_links ${JAVA_JVM_LIBRARY})
  set_target_properties(tdjni PROPERTIES LINK_LIBRARIES "${_tdjni_links}")
  if (APPLE)
    target_link_options(tdjni PRIVATE "LINKER:-undefined,dynamic_lookup")
  endif()
endif()
if (MINGW)
  set_target_properties(tdjni PROPERTIES PREFIX "")
  target_link_options(tdjni PRIVATE -static -static-libgcc -static-libstdc++)
endif()
CMAKE
cmake "${CMAKE_HOST_ARGS[@]}" "${CMAKE_DEPENDENCY_ARGS[@]}" -S "$JNI_SRC" -B "$CMAKE_BUILD-java" \
    -DTd_DIR="$INSTALL_PREFIX/lib/cmake/Td" \
    -DOPENSSL_ROOT_DIR="$TDLIGHT_OPENSSL_PREFIX" \
    -DCMAKE_PREFIX_PATH="$INSTALL_PREFIX" \
    -DCMAKE_INSTALL_PREFIX="$BUILD/java-output"
cmake --build "$CMAKE_BUILD-java" --config Release --target "$TDLIGHT_JNI_TARGET" --parallel "$JOBS"

LIBRARY_HITS="$(find "$CMAKE_BUILD-java" -name "$LIBRARY_NAME" -type f)"
[ "$(printf '%s\n' "$LIBRARY_HITS" | grep -c .)" = "1" ] \
    || die "expected exactly one $LIBRARY_NAME under $CMAKE_BUILD-java"
if [ "$HOST_NAME" = windows-x64 ]; then
    objdump -p "$LIBRARY_HITS" >"$BUILD/tdjni-imports.txt"
    if grep -Ei 'DLL Name:.*(libgcc|libstdc\+\+|libwinpthread|libssl|libcrypto|zlib)' "$BUILD/tdjni-imports.txt"; then
        die "tdjni.dll imports an unstaged MinGW runtime or media dependency; static linking is required"
    fi
fi

mkdir -p "$STAGE/$HOST_NAME/native" "$STAGE/notices"
rm -f "$STAGE/$HOST_NAME/native/$LIBRARY_NAME"
cp "$LIBRARY_HITS" "$STAGE/$HOST_NAME/native/$LIBRARY_NAME"
cp "$LICENSE_FILE" "$STAGE/notices/TDLight-Boost-1.0-LICENSE.txt"
log "pinned commit $TDLIGHT_COMMIT"
log "staged $STAGE/$HOST_NAME/native/$LIBRARY_NAME"
log "staged $STAGE/notices/TDLight-Boost-1.0-LICENSE.txt"
