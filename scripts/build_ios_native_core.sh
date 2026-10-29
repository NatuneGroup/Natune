#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (c) 2026 vossgraves
#
# Builds the static arm64 native closure that the ArchiveTune Xcode app links for the shared iOS playback
# path. It runs only on macOS with full Xcode. It cannot run on Linux or Windows, and nothing in this file has
# been executed on the Linux code-only host.
#
# Per Apple platform the closure is written to shared-data/build/ios-native/<iphoneos|iphonesimulator>/lib:
#   libarchivetune_engine.a   portable core compiled from desktopApp/native/src/archivetune_engine.c and
#                             archivetune_device.c with miniaudio 0.11.25. The JNI bridge, desktop system code
#                             and realtime tests are excluded, and no desktop binary is used.
#   libavfilter.a             FFmpeg 8.1.3 static libraries: avfilter, avformat, avcodec, swscale,
#   libavformat.a             swresample and avutil. The version 3 license set is used, with no nonfree code
#   libavcodec.a              and no programs.
#   libswscale.a
#   libswresample.a
#   libavutil.a
#   libssl.a libcrypto.a      OpenSSL 3.5.9 (TLS for FFmpeg).
#   libxml2.a                 libxml2 2.15.4 (DASH MPD parsing).
#   libz.a                    zlib 1.3.2.
#   libarchivetune_telegram.a TDLib from the tdlight-team/tdlight pin below (TDLib 1.8.66), every static component
#                             library of the tdjson_static target merged into one archive, linked against the
#                             OpenSSL and zlib above. include/td/telegram beside lib/ carries td_json_client.h and
#                             the generated tdjson_export.h that iosApp/Native/ATNativeTelegram.mm compiles against.
# iconv and the C++ runtime come from the iOS SDK. iosApp/Configuration/Native.xcconfig links them with -liconv
# and -lc++. closure.txt beside lib/ records the pins, toolchain, source commit and archive SHA-256 values.
#
# Prerequisites:
#   - An Apple Silicon Mac with full Xcode installed and selected by xcode-select, not only the Command Line
#     Tools. The expected release is Xcode 26.4, the Kotlin 2.4.20-2.4.21 compatibility-matrix candidate.
#     ARCHIVETUNE_XCODE_VERSION names another exact release. ARCHIVETUNE_ALLOW_OTHER_XCODE=1 builds with a
#     different release and marks the output UNVERIFIED.
#   - The iphoneos and iphonesimulator SDKs of that Xcode: xcrun --sdk <name> --show-sdk-path must succeed.
#   - gpg (Homebrew gnupg) for the FFmpeg release signature, and pkg-config (Homebrew pkgconf). GNU make is
#     needed by the FFmpeg configure script. If gmake is installed (Homebrew make), this script links it as
#     make inside its work tree. Apple's bundled make is GNU make 3.81, which may be rejected.
#   - cmake (3.10 or later) and gperf (Homebrew cmake and gperf) for TDLib. TDLib's source generators run once on
#     the build Mac before the cross builds, as TDLib's own iOS example does.
#   - git, curl, tar, perl, awk, shasum and the Xcode toolchain are used as shipped with macOS and Xcode.
#   - A repository path without spaces. Configure and make arguments are space-delimited.
#   - Network access only to the pinned download hosts below. Every download is checked against its pinned
#     SHA-256, and the FFmpeg tarball is also checked against the FFmpeg release signing key. TDLib is fetched
#     from git at its pinned commit, and the checked-out commit must equal the pin.
#
# Usage: scripts/build_ios_native_core.sh [iphoneos|iphonesimulator|all]        (default: all)
# Environment:
#   ARCHIVETUNE_NATIVE_ROOT        output root for ONE platform (default shared-data/build/ios-native/<platform>)
#   ARCHIVETUNE_IOS_NATIVE_BUILD   work tree (default shared-data/build/ios-native-work)
#   ARCHIVETUNE_NATIVE_JOBS        make parallelism (default 3)
#   ARCHIVETUNE_XCODE_VERSION      exact expected Xcode release (default 26.4)
#   ARCHIVETUNE_ALLOW_OTHER_XCODE  set to 1 to accept a different Xcode release (output is UNVERIFIED)
#
# Dependency builds leave stamp files and are skipped on re-run. The portable core and the closure assembly
# are rebuilt from the committed sources every time.

set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE_ROOT="$REPO/desktopApp/native"
WORK="${ARCHIVETUNE_IOS_NATIVE_BUILD:-$REPO/shared-data/build/ios-native-work}"
DOWNLOADS="$WORK/downloads"
JOBS="${ARCHIVETUNE_NATIVE_JOBS:-3}"
XCODE_EXPECTED="${ARCHIVETUNE_XCODE_VERSION:-26.4}"
# Must equal IPHONEOS_DEPLOYMENT_TARGET in iosApp/Configuration/Native.xcconfig.
DEPLOYMENT_TARGET=16.0

# Same pins as desktopApp/native/scripts/build-native-deps.sh. Keep both files in step when a pin changes.
ZLIB_VERSION=1.3.2
ZLIB_SHA256=bb329a0a2cd0274d05519d61c667c062e06990d72e125ee2dfa8de64f0119d16
OPENSSL_VERSION=3.5.9
OPENSSL_SHA256=603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a
LIBXML2_VERSION=2.15.4
LIBXML2_SHA256=98087fd181d9070724f3fbc65c7377db03038eb92bd882374daff44940138821
FFMPEG_VERSION=8.1.3
FFMPEG_SHA256=7138d28c96d9d3e3af4ee3d8cad72741f8ffb40da90c1112235dea3ecd3178a3
FFMPEG_ASC_SHA256=5f2880c7fea01f8694d30cb9d06362f77241ee67ce5926b157a226b18f08a538
FFMPEG_DEVEL_ASC_SHA256=397b3becedcd5a98769967ff1ff8501ddc89f8368b8f766e4701377d7dbaabe5
# Primary fingerprint of the FFmpeg release signing key published at https://ffmpeg.org/download.html
FFMPEG_SIGNING_FPR=FCF986EA15E6E293A5644F10B4322F04D67658D8
MINIAUDIO_VERSION=0.11.25
MINIAUDIO_SHA256=ac7af4de748b7e26b777f37e01cee313a308a7296a3eb080e2906b320cc55c89
MINIAUDIO_LICENSE_SHA256=457f1b500e0adf6bc059edddfa78a2f62012e7c3bb43476c20e0bd23b25ba0eb
# The tdlight pin named in iosApp/Native/ATNativeTelegram.h. The JSON schema the Kotlin session speaks follows it.
TDLIGHT_REPOSITORY=https://github.com/tdlight-team/tdlight.git
TDLIGHT_COMMIT=2b51b33c8a905be96c40508e68485f8a719e26a3

# Archives that iosApp/Configuration/Native.xcconfig links, in dependency order (a dependent precedes its
# dependency). Keep this list in step with the -l flags in that file.
CLOSURE_ARCHIVES="libarchivetune_engine.a libarchivetune_telegram.a libavfilter.a libavformat.a libavcodec.a libswscale.a libswresample.a libavutil.a libssl.a libcrypto.a libxml2.a libz.a"
# Entry points that iosApp/Native/ATNativeAudio.mm calls. The producer rejects a core that does not export them.
CORE_SYMBOLS="ae_engine_create ae_engine_destroy ae_engine_load ae_engine_play ae_engine_pause ae_engine_seek ae_engine_set_speed ae_engine_stop ae_engine_snapshot ae_engine_last_error ae_engine_render_realtime"
# Entry points that iosApp/Native/ATNativeTelegram.mm calls. The producer rejects a TDLib archive without them.
TELEGRAM_SYMBOLS="td_json_client_create td_json_client_send td_json_client_receive td_json_client_execute td_json_client_destroy"

XCODE_UNVERIFIED=0
XCODE_LINE=""
SHA256=()
PLATFORM=""

log() { printf '[ios-native] %s\n' "$*"; }
die() { printf '[ios-native] error: %s\n' "$*" >&2; exit 1; }

case "$REPO" in
    *" "*) die "the repository path contains a space; configure and make arguments need a space-free path" ;;
esac

preflight() {
    [ "$(uname -s)" = "Darwin" ] || die "this producer runs on macOS with full Xcode; this host is $(uname -s), so nothing was built"
    local tool
    for tool in xcodebuild xcrun curl tar perl pkg-config gpg awk git cmake gperf; do
        command -v "$tool" >/dev/null 2>&1 || die "$tool is required; see the prerequisites in this script's header"
    done
    if command -v gmake >/dev/null 2>&1; then
        mkdir -p "$WORK/bin"
        ln -sf "$(command -v gmake)" "$WORK/bin/make"
        PATH="$WORK/bin:$PATH"
        export PATH
    fi
    local version
    XCODE_LINE="$(xcodebuild -version 2>/dev/null | tr '\n' ' ')" || die "xcodebuild cannot run; select a full Xcode with xcode-select"
    version="$(xcodebuild -version 2>/dev/null | awk 'NR == 1 { print $2 }')"
    if [ "$version" != "$XCODE_EXPECTED" ]; then
        if [ "${ARCHIVETUNE_ALLOW_OTHER_XCODE:-0}" != 1 ]; then
            die "Xcode $version is selected; the expected release is $XCODE_EXPECTED (set ARCHIVETUNE_ALLOW_OTHER_XCODE=1 to build an UNVERIFIED closure)"
        fi
        XCODE_UNVERIFIED=1
        log "warning: Xcode $version is not the expected $XCODE_EXPECTED; the closure is UNVERIFIED"
    fi
    if command -v shasum >/dev/null 2>&1; then
        SHA256=(shasum -a 256)
    else
        SHA256=(sha256sum)
    fi
    log "make: $(make --version 2>/dev/null | head -n 1)"
    log "toolchain: $XCODE_LINE"
}

sha256_of() { "${SHA256[@]}" "$1" | awk '{ print $1 }'; }

fetch() {
    local url="$1" dest="$2" sha="$3"
    if [ ! -f "$dest" ]; then
        log "downloading $(basename "$dest")"
        curl --fail --silent --show-error --location \
            --retry 5 --retry-all-errors --retry-delay 2 --retry-max-time 300 \
            --connect-timeout 30 --max-time 180 -o "$dest.part" "$url"
        mv "$dest.part" "$dest"
    fi
    if [ "$(sha256_of "$dest")" != "$sha" ]; then
        rm -f "$dest"
        die "checksum mismatch for $(basename "$dest"); removed the download"
    fi
}

verify_ffmpeg_signature() {
    local tarball="$DOWNLOADS/ffmpeg-$FFMPEG_VERSION.tar.xz"
    local gnupg="$WORK/gnupg"
    local status="$WORK/logs/ffmpeg-verify-status.txt"
    mkdir -p "$gnupg" "$WORK/logs"
    chmod 700 "$gnupg"
    GNUPGHOME="$gnupg" gpg --batch --import "$DOWNLOADS/ffmpeg-devel.asc" >"$WORK/logs/ffmpeg-signature.log" 2>&1 \
        || die "gpg could not import the FFmpeg release key; see $WORK/logs/ffmpeg-signature.log"
    # gpg exits non-zero for a bad signature; the status lines decide, so the exit status is not checked here.
    GNUPGHOME="$gnupg" gpg --batch --status-fd 1 --verify "$tarball.asc" "$tarball" \
        >"$status" 2>>"$WORK/logs/ffmpeg-signature.log" || true
    if grep -q "^\[GNUPG:\] VALIDSIG .* $FFMPEG_SIGNING_FPR\$" "$status"; then
        log "FFmpeg $FFMPEG_VERSION signature verified against $FFMPEG_SIGNING_FPR"
    else
        die "FFmpeg signature verification FAILED; see $WORK/logs/ffmpeg-signature.log"
    fi
}

download_sources() {
    mkdir -p "$DOWNLOADS"
    fetch "https://ffmpeg.org/ffmpeg-devel.asc" "$DOWNLOADS/ffmpeg-devel.asc" "$FFMPEG_DEVEL_ASC_SHA256"
    fetch "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz.asc" \
        "$DOWNLOADS/ffmpeg-$FFMPEG_VERSION.tar.xz.asc" "$FFMPEG_ASC_SHA256"
    fetch "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz" \
        "$DOWNLOADS/ffmpeg-$FFMPEG_VERSION.tar.xz" "$FFMPEG_SHA256"
    fetch "https://zlib.net/zlib-$ZLIB_VERSION.tar.gz" "$DOWNLOADS/zlib-$ZLIB_VERSION.tar.gz" "$ZLIB_SHA256"
    fetch "https://www.openssl.org/source/openssl-$OPENSSL_VERSION.tar.gz" \
        "$DOWNLOADS/openssl-$OPENSSL_VERSION.tar.gz" "$OPENSSL_SHA256"
    fetch "https://download.gnome.org/sources/libxml2/2.15/libxml2-$LIBXML2_VERSION.tar.xz" \
        "$DOWNLOADS/libxml2-$LIBXML2_VERSION.tar.xz" "$LIBXML2_SHA256"
    fetch "https://raw.githubusercontent.com/mackron/miniaudio/$MINIAUDIO_VERSION/miniaudio.h" \
        "$DOWNLOADS/miniaudio.h" "$MINIAUDIO_SHA256"
    fetch "https://raw.githubusercontent.com/mackron/miniaudio/$MINIAUDIO_VERSION/LICENSE" \
        "$DOWNLOADS/miniaudio.LICENSE" "$MINIAUDIO_LICENSE_SHA256"
    verify_ffmpeg_signature
    fetch_tdlight
}

# One shallow checkout of the pinned commit, shared by both platforms. A checkout at any other commit is refused.
fetch_tdlight() {
    local dir="$WORK/tdlight"
    if [ ! -d "$dir/.git" ]; then
        log "fetching tdlight $TDLIGHT_COMMIT"
        rm -rf "$dir.part"
        mkdir -p "$dir.part"
        (cd "$dir.part" && git init -q && git remote add origin "$TDLIGHT_REPOSITORY" && \
            git fetch -q --depth 1 origin "$TDLIGHT_COMMIT" && git checkout -q --detach FETCH_HEAD) \
            >"$WORK/logs/tdlight-fetch.log" 2>&1 || die "tdlight could not be fetched; see $WORK/logs/tdlight-fetch.log"
        mv "$dir.part" "$dir"
    fi
    [ "$(git -C "$dir" rev-parse HEAD)" = "$TDLIGHT_COMMIT" ] \
        || die "the tdlight checkout is not at $TDLIGHT_COMMIT; remove $dir and run again"
    TDLIGHT_SOURCE="$dir"
}

# TDLib generates part of its sources with host tools, which a cross build cannot run. This runs them once on
# the build Mac, into the source tree that both cross builds read.
generate_tdlight_sources() {
    [ -f "$WORK/.stamp-tdlight-generated-$TDLIGHT_COMMIT" ] && return 0
    log "generating TDLib sources on the build Mac"
    mkdir -p "$WORK/tdlight-generate" "$WORK/logs"
    (cd "$WORK/tdlight-generate" && \
        cmake -DTD_GENERATE_SOURCE_FILES=ON "$TDLIGHT_SOURCE" && cmake --build . -j "$JOBS") \
        >"$WORK/logs/tdlight-generate.log" 2>&1 || die "TDLib source generation failed; see $WORK/logs/tdlight-generate.log"
    : >"$WORK/.stamp-tdlight-generated-$TDLIGHT_COMMIT"
}

configure_platform() {
    PLATFORM="$1"
    case "$PLATFORM" in
        iphoneos)
            TARGET_TRIPLE="arm64-apple-ios$DEPLOYMENT_TARGET"
            OPENSSL_TARGET=ios64-xcrun
            ;;
        iphonesimulator)
            TARGET_TRIPLE="arm64-apple-ios$DEPLOYMENT_TARGET-simulator"
            OPENSSL_TARGET=iossimulator-arm64-xcrun
            ;;
        *) die "unknown platform '$PLATFORM' (expected iphoneos or iphonesimulator)" ;;
    esac
    SDK_PATH="$(xcrun --sdk "$PLATFORM" --show-sdk-path)" || die "the $PLATFORM SDK is not installed in $(xcode-select -p 2>/dev/null)"
    SDK_VERSION="$(xcrun --sdk "$PLATFORM" --show-sdk-version)"
    case "$SDK_PATH" in
        *" "*) die "the $PLATFORM SDK path contains a space, which the configure arguments cannot carry" ;;
    esac
    CC="$(xcrun --sdk "$PLATFORM" --find clang)"
    AR="$(xcrun --sdk "$PLATFORM" --find ar)"
    RANLIB="$(xcrun --sdk "$PLATFORM" --find ranlib)"
    NM="$(xcrun --sdk "$PLATFORM" --find nm)"
    # A host that differs from the build machine makes autoconf cross-compile, so it never runs iOS test programs.
    HOST_TRIPLE=arm-apple-darwin
    FLAGS="-target $TARGET_TRIPLE -isysroot $SDK_PATH -O2"
    PWORK="$WORK/$PLATFORM"
    SRC="$PWORK/src"
    PREFIX="$PWORK/prefix"
    OBJ="$PWORK/obj"
    LOGS="$WORK/logs/$PLATFORM"
    OUT="${ARCHIVETUNE_NATIVE_ROOT:-$REPO/shared-data/build/ios-native/$PLATFORM}"
    LIB="$OUT/lib"
    mkdir -p "$SRC" "$PREFIX/lib" "$OBJ" "$LOGS" "$LIB"
    export IPHONEOS_DEPLOYMENT_TARGET="$DEPLOYMENT_TARGET"
    unset MACOSX_DEPLOYMENT_TARGET
}

stamp_done() { [ -f "$PREFIX/.stamp-$1" ]; }
stamp_set() { : >"$PREFIX/.stamp-$1"; }

unpack() {
    local archive="$1" name="$2"
    if [ ! -d "$SRC/$name" ]; then
        tar -xf "$archive" -C "$SRC"
    fi
}

build_zlib() {
    stamp_done zlib && return 0
    log "[$PLATFORM] building zlib $ZLIB_VERSION (static)"
    unpack "$DOWNLOADS/zlib-$ZLIB_VERSION.tar.gz" "zlib-$ZLIB_VERSION"
    (cd "$SRC/zlib-$ZLIB_VERSION" && CC="$CC" CFLAGS="$FLAGS" AR="$AR" RANLIB="$RANLIB" \
        ./configure --prefix="$PREFIX" --static && make -j"$JOBS" && make install) \
        >"$LOGS/zlib.log" 2>&1 || die "zlib failed for $PLATFORM; see $LOGS/zlib.log"
    stamp_set zlib
}

build_openssl() {
    stamp_done openssl && return 0
    log "[$PLATFORM] building OpenSSL $OPENSSL_VERSION ($OPENSSL_TARGET, static)"
    unpack "$DOWNLOADS/openssl-$OPENSSL_VERSION.tar.gz" "openssl-$OPENSSL_VERSION"
    (cd "$SRC/openssl-$OPENSSL_VERSION" && \
        perl Configure LIST >"$LOGS/openssl-targets.txt" && \
        grep -qx "$OPENSSL_TARGET" "$LOGS/openssl-targets.txt" && \
        ./Configure "$OPENSSL_TARGET" --prefix="$PREFIX" --libdir=lib no-shared no-tests no-docs && \
        make -j"$JOBS" build_libs && make install_sw) \
        >"$LOGS/openssl.log" 2>&1 || die "OpenSSL failed for $PLATFORM (target $OPENSSL_TARGET); see $LOGS/openssl.log"
    stamp_set openssl
}

build_libxml2() {
    stamp_done libxml2 && return 0
    log "[$PLATFORM] building libxml2 $LIBXML2_VERSION (static; HTTP, FTP, Python, ICU and LZMA disabled)"
    unpack "$DOWNLOADS/libxml2-$LIBXML2_VERSION.tar.xz" "libxml2-$LIBXML2_VERSION"
    (cd "$SRC/libxml2-$LIBXML2_VERSION" && \
        PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig" CC="$CC" CFLAGS="$FLAGS" \
        LDFLAGS="$FLAGS -L$PREFIX/lib" AR="$AR" RANLIB="$RANLIB" \
        ./configure --host="$HOST_TRIPLE" --prefix="$PREFIX" --disable-shared --enable-static \
        --with-zlib="$PREFIX" --without-python --without-lzma --without-http --without-ftp \
        --without-icu --without-debug && \
        make -j"$JOBS" && make install) \
        >"$LOGS/libxml2.log" 2>&1 || die "libxml2 failed for $PLATFORM; see $LOGS/libxml2.log"
    stamp_set libxml2
}

stage_miniaudio() {
    local dir="$SRC/miniaudio-$MINIAUDIO_VERSION"
    mkdir -p "$dir"
    cp "$DOWNLOADS/miniaudio.h" "$dir/miniaudio.h"
    cp "$DOWNLOADS/miniaudio.LICENSE" "$dir/LICENSE"
}

build_ffmpeg() {
    stamp_done ffmpeg && return 0
    log "[$PLATFORM] building FFmpeg $FFMPEG_VERSION static libraries (version 3 license set, no nonfree code, no programs)"
    unpack "$DOWNLOADS/ffmpeg-$FFMPEG_VERSION.tar.xz" "ffmpeg-$FFMPEG_VERSION"
    (cd "$SRC/ffmpeg-$FFMPEG_VERSION" && \
        PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig" ./configure \
        --prefix="$PREFIX" --enable-cross-compile --target-os=darwin --arch=arm64 \
        --cc="$CC" --ar="$AR" --ranlib="$RANLIB" --nm="$NM" \
        --extra-cflags="$FLAGS -I$PREFIX/include" --extra-ldflags="$FLAGS -L$PREFIX/lib" \
        --enable-static --disable-shared --disable-programs --disable-doc --disable-debug \
        --disable-autodetect --enable-version3 --enable-pthreads --enable-libxml2 \
        --enable-openssl --enable-zlib --disable-x86asm && \
        make -j"$JOBS" && make install) \
        >"$LOGS/ffmpeg.log" 2>&1 || die "FFmpeg failed for $PLATFORM; see $LOGS/ffmpeg.log"
    stamp_set ffmpeg
}

# Cross-builds the tdjson_static target with CMake's own iOS support against the OpenSSL and zlib of this
# platform, then merges every static library the build produced into one archive, so the Xcode link line names a
# single TDLib library whatever component split this TDLib revision uses.
build_telegram() {
    # The stamp names the pin, so a changed pin rebuilds TDLib instead of reusing an older archive.
    stamp_done "telegram-$TDLIGHT_COMMIT" && return 0
    log "[$PLATFORM] building TDLib (tdlight $TDLIGHT_COMMIT, tdjson_static)"
    local build="$PWORK/tdlight-build" archives
    rm -rf "$build"
    mkdir -p "$build"
    (cd "$build" && \
        cmake "$TDLIGHT_SOURCE" -DCMAKE_BUILD_TYPE=MinSizeRel -DCMAKE_SYSTEM_NAME=iOS \
            -DCMAKE_OSX_SYSROOT="$SDK_PATH" -DCMAKE_OSX_ARCHITECTURES=arm64 \
            -DCMAKE_OSX_DEPLOYMENT_TARGET="$DEPLOYMENT_TARGET" -DCMAKE_C_COMPILER="$CC" \
            -DCMAKE_CXX_COMPILER="$(xcrun --sdk "$PLATFORM" --find clang++)" \
            -DOPENSSL_FOUND=1 -DOPENSSL_INCLUDE_DIR="$PREFIX/include" \
            -DOPENSSL_CRYPTO_LIBRARY="$PREFIX/lib/libcrypto.a" -DOPENSSL_SSL_LIBRARY="$PREFIX/lib/libssl.a" \
            -DZLIB_INCLUDE_DIR="$PREFIX/include" -DZLIB_LIBRARY="$PREFIX/lib/libz.a" \
            -DTD_ENABLE_LTO=OFF -DTD_ENABLE_JNI=OFF && \
        cmake --build . --target tdjson_static -j "$JOBS") \
        >"$LOGS/telegram.log" 2>&1 || die "TDLib failed for $PLATFORM; see $LOGS/telegram.log"
    archives="$(find "$build" -name 'lib*.a' -type f | sort | tr '\n' ' ')"
    [ -n "$archives" ] || die "the TDLib build for $PLATFORM produced no static library; see $LOGS/telegram.log"
    rm -f "$PREFIX/lib/libarchivetune_telegram.a"
    # shellcheck disable=SC2086 # the archive paths are space-free, as the repository path is
    xcrun --sdk "$PLATFORM" libtool -static -o "$PREFIX/lib/libarchivetune_telegram.a" $archives \
        >>"$LOGS/telegram.log" 2>&1 || die "the TDLib archives could not be merged for $PLATFORM; see $LOGS/telegram.log"
    mkdir -p "$PREFIX/include/td/telegram"
    cp -f "$TDLIGHT_SOURCE/td/telegram/td_json_client.h" "$PREFIX/include/td/telegram/td_json_client.h"
    cp -f "$build/td/telegram/tdjson_export.h" "$PREFIX/include/td/telegram/tdjson_export.h" \
        || die "the TDLib build for $PLATFORM did not generate tdjson_export.h; see $LOGS/telegram.log"
    stamp_set "telegram-$TDLIGHT_COMMIT"
}

# The portable core is always rebuilt from the committed sources. It has no stamp, so a source change is never
# hidden behind an older archive.
build_core() {
    local include=(
        -I"$SOURCE_ROOT/include"
        -I"$SOURCE_ROOT/src"
        -I"$SRC/miniaudio-$MINIAUDIO_VERSION"
        -I"$PREFIX/include"
    )
    local unit
    : >"$LOGS/core.log"
    log "[$PLATFORM] compiling the portable core from desktopApp/native/src (JNI and desktop system code excluded)"
    rm -f "$OBJ"/*.o
    "$CC" -target "$TARGET_TRIPLE" -isysroot "$SDK_PATH" -O2 -std=gnu11 -fvisibility=hidden -Wall -Wextra \
        "${include[@]}" -c "$SOURCE_ROOT/src/archivetune_engine.c" -o "$OBJ/archivetune_engine.o" >>"$LOGS/core.log" 2>&1 \
        || die "portable core compile failed for archivetune_engine on $PLATFORM; see $LOGS/core.log"
    # miniaudio's Apple device backend is Objective-C; the device unit cannot compile as plain C on this SDK.
    "$CC" -target "$TARGET_TRIPLE" -isysroot "$SDK_PATH" -O2 -x objective-c -fvisibility=hidden -Wall \
        "${include[@]}" -c "$SOURCE_ROOT/src/archivetune_device.c" -o "$OBJ/archivetune_device.o" >>"$LOGS/core.log" 2>&1 \
        || die "portable core compile failed for archivetune_device on $PLATFORM; see $LOGS/core.log"
    rm -f "$PWORK/libarchivetune_engine.a"
    "$AR" rcs "$PWORK/libarchivetune_engine.a" "$OBJ/archivetune_engine.o" "$OBJ/archivetune_device.o"
    "$RANLIB" "$PWORK/libarchivetune_engine.a"
}

copy_notice() {
    if [ -f "$1" ]; then
        cp -f "$1" "$2"
    else
        log "warning: notice file $1 is missing from the source tree; $2 was not written"
    fi
}

assemble() {
    local name
    for name in $CLOSURE_ARCHIVES; do
        if [ "$name" = libarchivetune_engine.a ]; then
            cp -f "$PWORK/$name" "$LIB/$name"
        else
            [ -f "$PREFIX/lib/$name" ] || die "$PREFIX/lib/$name was not produced; see the logs in $LOGS"
            cp -f "$PREFIX/lib/$name" "$LIB/$name"
        fi
    done
    mkdir -p "$OUT/include/td/telegram"
    cp -f "$PREFIX/include/td/telegram/td_json_client.h" "$PREFIX/include/td/telegram/tdjson_export.h" \
        "$OUT/include/td/telegram/"
    mkdir -p "$OUT/licenses"
    copy_notice "$DOWNLOADS/miniaudio.LICENSE" "$OUT/licenses/miniaudio-LICENSE.txt"
    copy_notice "$SRC/ffmpeg-$FFMPEG_VERSION/COPYING.LGPLv3" "$OUT/licenses/ffmpeg-COPYING.LGPLv3.txt"
    copy_notice "$SRC/openssl-$OPENSSL_VERSION/LICENSE.txt" "$OUT/licenses/openssl-LICENSE.txt"
    copy_notice "$SRC/libxml2-$LIBXML2_VERSION/Copyright" "$OUT/licenses/libxml2-Copyright.txt"
    copy_notice "$SRC/zlib-$ZLIB_VERSION/LICENSE" "$OUT/licenses/zlib-LICENSE.txt"
    copy_notice "$TDLIGHT_SOURCE/LICENSE_1_0.txt" "$OUT/licenses/tdlib-LICENSE_1_0.txt"
}

verify_closure() {
    local name symbol
    for name in $CLOSURE_ARCHIVES; do
        [ -s "$LIB/$name" ] || die "closure incomplete: $LIB/$name is missing or empty"
    done
    "$NM" -gU "$LIB/libarchivetune_engine.a" >"$PWORK/core-exports.txt" 2>"$LOGS/nm.log" \
        || die "could not list the core exports; see $LOGS/nm.log"
    for symbol in $CORE_SYMBOLS; do
        grep -q "[[:space:]]_${symbol}\$" "$PWORK/core-exports.txt" \
            || die "libarchivetune_engine.a does not export $symbol; ATNativeAudio.mm cannot link"
    done
    log "[$PLATFORM] the core exports every ABI entry point that ATNativeAudio.mm calls"
    "$NM" -gU "$LIB/libarchivetune_telegram.a" >"$PWORK/telegram-exports.txt" 2>>"$LOGS/nm.log" \
        || die "could not list the TDLib exports; see $LOGS/nm.log"
    for symbol in $TELEGRAM_SYMBOLS; do
        grep -q "[[:space:]]_${symbol}\$" "$PWORK/telegram-exports.txt" \
            || die "libarchivetune_telegram.a does not export $symbol; ATNativeTelegram.mm cannot link"
    done
    [ -s "$OUT/include/td/telegram/td_json_client.h" ] || die "the TDLib header is missing from $OUT/include"
    log "[$PLATFORM] the TDLib archive exports every entry point that ATNativeTelegram.mm calls"
}

write_manifest() {
    local name head dirty
    head="$(git -C "$REPO" rev-parse HEAD 2>/dev/null || echo unknown)"
    dirty="$(git -C "$REPO" status --porcelain -- desktopApp/native 2>/dev/null | wc -l | tr -d ' ')"
    {
        echo "ArchiveTune iOS native closure"
        echo "platform: $PLATFORM"
        echo "target: $TARGET_TRIPLE"
        echo "sdk: $SDK_VERSION"
        echo "toolchain: $XCODE_LINE"
        echo "toolchain-unverified: $XCODE_UNVERIFIED"
        echo "deployment-target: $DEPLOYMENT_TARGET"
        echo "source-commit: $head"
        echo "uncommitted-native-source-entries: $dirty"
        echo "zlib: $ZLIB_VERSION"
        echo "openssl: $OPENSSL_VERSION ($OPENSSL_TARGET)"
        echo "libxml2: $LIBXML2_VERSION"
        echo "ffmpeg: $FFMPEG_VERSION (release signing fingerprint $FFMPEG_SIGNING_FPR)"
        echo "miniaudio: $MINIAUDIO_VERSION"
        echo "tdlight: $TDLIGHT_COMMIT ($TDLIGHT_REPOSITORY)"
        echo "from-sdk (not packaged): -liconv -lc++"
        echo "sha256:"
        for name in $CLOSURE_ARCHIVES; do
            echo "  $name $(sha256_of "$LIB/$name")"
        done
    } >"$OUT/closure.txt"
}

main() {
    local request="${1:-all}" platforms platform
    case "$request" in
        all) platforms="iphoneos iphonesimulator" ;;
        iphoneos|iphonesimulator) platforms="$request" ;;
        *) die "usage: $0 [iphoneos|iphonesimulator|all]" ;;
    esac
    if [ "$request" = all ] && [ -n "${ARCHIVETUNE_NATIVE_ROOT:-}" ]; then
        die "ARCHIVETUNE_NATIVE_ROOT names one platform's output; build iphoneos or iphonesimulator separately"
    fi
    preflight
    mkdir -p "$DOWNLOADS" "$WORK/logs"
    download_sources
    generate_tdlight_sources
    for platform in $platforms; do
        configure_platform "$platform"
        build_zlib
        build_openssl
        build_libxml2
        stage_miniaudio
        build_ffmpeg
        build_telegram
        build_core
        assemble
        verify_closure
        write_manifest
        log "[$PLATFORM] closure ready at $LIB"
    done
    log "done"
}

main "$@"
