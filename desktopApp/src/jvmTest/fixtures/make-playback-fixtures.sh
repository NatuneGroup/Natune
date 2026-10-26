#!/usr/bin/env bash
# ArchiveTune (2026)
# © Rukamori — github.com/rukamori
# GPL-3.0 License | Contributors: see git history
# Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
# Portions © vossgraves — github.com/vossgraves
#
# Regenerates the playback test media in ../resources/playback. The committed files are the output
# of this script, so rerunning it should only change them when the recipe changes.
#
# Requirements: python3 and an ffmpeg with the aac, flac, libmp3lame, libvorbis, hls and dash
# components. The tests do not run this script; they read the committed files.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
out="$here/../resources/playback"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

mkdir -p "$out/hls" "$out/dash"

# Four seconds of mono 48 kHz 16-bit PCM. Sample i is ((i % 4096) - 2048) * 8, which fits in 16 bits,
# so the lossless copies decode to exactly ((i % 4096) - 2048) / 4096 as 32-bit floats.
python3 - "$out/ramp-48k.wav" <<'PY'
import array
import sys
import wave

frames = 48_000 * 4
samples = array.array("h", (((i % 4096) - 2048) * 8 for i in range(frames)))
if sys.byteorder == "big":
    samples.byteswap()
with wave.open(sys.argv[1], "wb") as wav:
    wav.setnchannels(1)
    wav.setsampwidth(2)
    wav.setframerate(48_000)
    wav.writeframes(samples.tobytes())
PY

source_wav="$out/ramp-48k.wav"
ffmpeg -hide_banner -loglevel error -y -i "$source_wav" -c:a flac "$out/ramp-48k.flac"
ffmpeg -hide_banner -loglevel error -y -i "$source_wav" -ar 44100 -c:a libmp3lame -b:a 128k "$out/ramp-44k1.mp3"
ffmpeg -hide_banner -loglevel error -y -i "$source_wav" -c:a libvorbis -q:a 3 "$out/ramp-48k.ogg"

ffmpeg -hide_banner -loglevel error -y -i "$source_wav" -c:a aac -b:a 96k \
    -f hls -hls_time 1 -hls_playlist_type vod -hls_segment_type mpegts \
    -hls_segment_filename "$out/hls/seg%02d.ts" "$out/hls/index.m3u8"

ffmpeg -hide_banner -loglevel error -y -i "$source_wav" -c:a aac -b:a 96k \
    -f dash -seg_duration 1 \
    -init_seg_name 'init-stream$RepresentationID$.m4s' \
    -media_seg_name 'chunk-stream$RepresentationID$-$Number%05d$.m4s' \
    "$out/dash/manifest.mpd"

echo "playback fixtures written to $out"
