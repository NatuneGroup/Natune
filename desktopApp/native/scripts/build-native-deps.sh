#!/usr/bin/env bash
#
# ArchiveTune (2026)
# © Rukamori — github.com/rukamori
# GPL-3.0 License | Contributors: see git history
# Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
# Portions © vossgraves — github.com/vossgraves
#
# Fetches, verifies and builds the pinned native media closure used by the desktop playback
# engine: zlib 1.3.2, OpenSSL 3.5.9, libxml2 2.15.4 (DASH demuxer XML) and FFmpeg 8.1.3 shared
# libraries, plus the miniaudio 0.11.25 single header for device I/O. Every download is checked
# against a pinned SHA-256, and the FFmpeg tarball is also checked against the FFmpeg release
# signing key. Outputs stay under desktopApp/build/native, which Git ignores.
#
# Re-running is cheap: finished steps leave a stamp file and are skipped. Set
# ARCHIVETUNE_NATIVE_BUILD to move the tree, and ARCHIVETUNE_NATIVE_JOBS to change parallelism.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../../.." && pwd)"
BUILD="${ARCHIVETUNE_NATIVE_BUILD:-$REPO/desktopApp/build/native}"
JOBS="${ARCHIVETUNE_NATIVE_JOBS:-3}"
DOWNLOADS="$BUILD/downloads"
SRC="$BUILD/src"
PREFIX="$BUILD/prefix"
LOGS="$BUILD/logs"

ZLIB_VERSION=1.3.2
ZLIB_SHA256=bb329a0a2cd0274d05519d61c667c062e06990d72e125ee2dfa8de64f0119d16
OPENSSL_VERSION=3.5.9
# Matches the sidecar published next to the tarball at openssl.org.
OPENSSL_SHA256=603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a
LIBXML2_VERSION=2.15.4
# Matches the sha256sum sidecar published at download.gnome.org.
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

log() { printf '[native-deps] %s\n' "$*"; }

fetch() {
    local url="$1" dest="$2" sha="$3"
    if [ ! -f "$dest" ]; then
        log "downloading $(basename "$dest")"
        curl -fsSL -o "$dest.part" "$url"
        mv "$dest.part" "$dest"
    fi
    if ! printf '%s  %s\n' "$sha" "$dest" | sha256sum -c --status; then
        rm -f "$dest"
        log "checksum mismatch for $(basename "$dest"); removed the download"
        exit 1
    fi
}

verify_ffmpeg_signature() {
    local tarball="$DOWNLOADS/ffmpeg-$FFMPEG_VERSION.tar.xz"
    local gnupg="$BUILD/gnupg"
    if ! command -v gpg >/dev/null 2>&1; then
        log "gpg is required to verify the FFmpeg release signature"
        exit 1
    fi
    mkdir -p "$gnupg"
    chmod 700 "$gnupg"
    GNUPGHOME="$gnupg" gpg --batch --import "$DOWNLOADS/ffmpeg-devel.asc" >"$LOGS/ffmpeg-signature.log" 2>&1
    if GNUPGHOME="$gnupg" gpg --batch --status-fd 1 --verify "$tarball.asc" "$tarball" \
        2>>"$LOGS/ffmpeg-signature.log" | grep -q "^\[GNUPG:\] VALIDSIG .* $FFMPEG_SIGNING_FPR\$"; then
        log "FFmpeg $FFMPEG_VERSION signature verified against $FFMPEG_SIGNING_FPR"
    else
        log "FFmpeg signature verification FAILED; see $LOGS/ffmpeg-signature.log"
        exit 1
    fi
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
    log "building zlib $ZLIB_VERSION"
    unpack "$DOWNLOADS/zlib-$ZLIB_VERSION.tar.gz" "zlib-$ZLIB_VERSION"
    (cd "$SRC/zlib-$ZLIB_VERSION" && ./configure --prefix="$PREFIX" --shared && make -j"$JOBS" && make install) \
        >"$LOGS/zlib.log" 2>&1
    stamp_set zlib
}

build_openssl() {
    stamp_done openssl && return 0
    log "building OpenSSL $OPENSSL_VERSION"
    unpack "$DOWNLOADS/openssl-$OPENSSL_VERSION.tar.gz" "openssl-$OPENSSL_VERSION"
    (cd "$SRC/openssl-$OPENSSL_VERSION" && ./Configure linux-x86_64 --prefix="$PREFIX" --libdir=lib \
        shared no-tests no-docs && make -j"$JOBS" && make install_sw) >"$LOGS/openssl.log" 2>&1
    stamp_set openssl
}

build_libxml2() {
    stamp_done libxml2 && return 0
    log "building libxml2 $LIBXML2_VERSION (HTTP, FTP, Python and ICU loading disabled)"
    unpack "$DOWNLOADS/libxml2-$LIBXML2_VERSION.tar.xz" "libxml2-$LIBXML2_VERSION"
    (cd "$SRC/libxml2-$LIBXML2_VERSION" && PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig" ./configure \
        --prefix="$PREFIX" --enable-shared --disable-static --with-zlib="$PREFIX" \
        --without-python --without-lzma --without-http --without-ftp --without-icu --without-debug \
        && make -j"$JOBS" && make install) >"$LOGS/libxml2.log" 2>&1
    stamp_set libxml2
}

build_ffmpeg() {
    stamp_done ffmpeg && return 0
    log "building FFmpeg $FFMPEG_VERSION shared libraries (version 3 license set, no nonfree code)"
    unpack "$DOWNLOADS/ffmpeg-$FFMPEG_VERSION.tar.xz" "ffmpeg-$FFMPEG_VERSION"
    (cd "$SRC/ffmpeg-$FFMPEG_VERSION" && PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig" ./configure \
        --prefix="$PREFIX" --enable-shared --disable-static --disable-programs --disable-doc \
        --disable-debug --disable-autodetect --enable-version3 --enable-pthreads \
        --enable-libxml2 --enable-openssl --enable-zlib --disable-x86asm \
        --extra-cflags="-I$PREFIX/include" --extra-ldflags="-L$PREFIX/lib -Wl,-rpath-link,$PREFIX/lib" \
        && make -j"$JOBS" && make install) >"$LOGS/ffmpeg.log" 2>&1
    stamp_set ffmpeg
}

stage_miniaudio() {
    local dir="$SRC/miniaudio-$MINIAUDIO_VERSION"
    mkdir -p "$dir"
    cp "$DOWNLOADS/miniaudio.h" "$dir/miniaudio.h"
    cp "$DOWNLOADS/miniaudio.LICENSE" "$dir/LICENSE"
}

main() {
    mkdir -p "$DOWNLOADS" "$SRC" "$PREFIX" "$LOGS"
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
    stage_miniaudio

    build_zlib
    build_openssl
    build_libxml2
    build_ffmpeg

    log "FFmpeg avformat $(PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig" pkg-config --modversion libavformat)"
    log "prefix: $PREFIX"
    log "miniaudio: $SRC/miniaudio-$MINIAUDIO_VERSION/miniaudio.h"
}

main "$@"
