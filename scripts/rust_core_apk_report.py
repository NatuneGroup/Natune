#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Per-ABI native contributions and whole universal Debug APK delta, never installed-size claims."""
import json
from pathlib import Path
import sys
import zipfile


def entries(path):
    with zipfile.ZipFile(path) as archive:
        return {i.filename: (i.file_size, i.compress_size) for i in archive.infolist()}


before, after = map(Path, sys.argv[1:])
a, b = entries(before), entries(after)
abis = ("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
report = {
    "artifact": "GmsMobileUniversalDebug",
    "universalApkDeltaBytes": after.stat().st_size - before.stat().st_size,
    "perAbiNativeDelta": {},
}
for abi in abis:
    names = sorted(n for n in a.keys() | b.keys() if n.startswith(f"lib/{abi}/"))
    report["perAbiNativeDelta"][abi] = {
        "uncompressedBytes": sum(b.get(n, (0, 0))[0] - a.get(n, (0, 0))[0] for n in names),
        "compressedBytes": sum(b.get(n, (0, 0))[1] - a.get(n, (0, 0))[1] for n in names),
        "addedLibraries": {n: b[n] for n in names if n not in a},
    }
print(json.dumps(report, indent=2))
