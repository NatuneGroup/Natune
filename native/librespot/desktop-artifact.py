#!/usr/bin/env python3
"""Content-addressed optional desktop JNI cache; no builds or network access."""

import argparse
import importlib.util
import json
import struct
from pathlib import Path

RECIPE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("android_artifact", RECIPE / "android-artifact.py")
android = importlib.util.module_from_spec(spec)
spec.loader.exec_module(android)
OUTPUT = RECIPE.parent.parent / "desktopApp/build/generated/librespot"
HOSTS = {"linux-x64": ("libnatune_librespot.so", 62), "linux-arm64": ("libnatune_librespot.so", 183),
         "windows-x64": ("natune_librespot.dll", 0x8664)}
METHODS = ("nativeConnect", "nativeResolve", "nativeGeneration", "nativeSessionValid", "nativeClose")


def check(library, host):
    data = library.read_bytes()
    machine = HOSTS[host][1]
    if host.startswith("linux"):
        if data[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<HH", data, 16) != (3, machine):
            raise ValueError("Desktop JNI ELF architecture mismatch")
    else:
        if data[:2] != b"MZ":
            raise ValueError("Invalid desktop JNI PE")
        offset = struct.unpack_from("<I", data, 0x3c)[0]
        if data[offset:offset + 4] != b"PE\0\0" or struct.unpack_from("<H", data, offset + 4)[0] != machine:
            raise ValueError("Desktop JNI PE architecture mismatch")
    # Export names are retained even with stripped Rust symbols. They must match the reused JVM entry points.
    for method in METHODS:
        symbol = "Java_moe_rukamori_archivetune_spotify_librespot_SpotifyLibrespotNative_" + method
        if symbol.encode() + b"\0" not in data:
            raise ValueError("Desktop JNI contract mismatch: " + method)


def run(command, host):
    root = OUTPUT / host
    library = root / "native" / HOSTS[host][0]
    check(library, host)
    manifest = {"recipe": android.fingerprint(), "host": host, "sha256": android.digest(library)}
    path = root / "manifest.json"
    if command == "record":
        path.write_text(json.dumps(manifest, sort_keys=True) + "\n")
    elif json.loads(path.read_text()) != manifest:
        raise ValueError("Desktop JNI cache does not match this recipe/host/checksum")
    print(host + ": verified optional Librespot JNI")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("record", "validate"))
    parser.add_argument("host", choices=HOSTS)
    args = parser.parse_args()
    run(args.command, args.host)
