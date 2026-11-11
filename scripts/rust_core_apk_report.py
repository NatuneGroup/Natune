#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Per-ABI native contributions and whole universal Debug APK delta, never installed-size claims."""
import json
from pathlib import Path
import sys
import struct
import zipfile


def entries(path):
    with zipfile.ZipFile(path) as archive:
        return {i.filename: (i.file_size, i.compress_size) for i in archive.infolist()}


def load_segments(data):
    if data[:4] != b"\x7fELF" or data[5] != 1:
        raise ValueError("Expected a little-endian Android ELF")
    if data[4] == 2:
        offset = struct.unpack_from("<Q", data, 32)[0]
        stride, count = struct.unpack_from("<HH", data, 54)
        format_ = "<IIQQQQQQ"
        positions = (2, 3, 7)
    else:
        offset = struct.unpack_from("<I", data, 28)[0]
        stride, count = struct.unpack_from("<HH", data, 42)
        format_ = "<IIIIIIII"
        positions = (1, 2, 7)
    loads = []
    for index in range(count):
        header = struct.unpack_from(format_, data, offset + index * stride)
        if header[0] == 1:
            file_offset, address, alignment = (header[p] for p in positions)
            loads.append({"alignment": alignment, "congruent16KiB": (address - file_offset) % 16384 == 0})
    return loads


def alignment_report(path, names):
    result = {}
    with zipfile.ZipFile(path) as archive, path.open("rb") as file:
        for name in names:
            info = archive.getinfo(name)
            segments = load_segments(archive.read(name))
            file.seek(info.header_offset + 26)
            name_bytes, extra_bytes = struct.unpack("<HH", file.read(4))
            payload_offset = info.header_offset + 30 + name_bytes + extra_bytes
            # Compressed libraries are extracted; only stored libraries require ZIP mmap alignment.
            zip_ok = info.compress_type != zipfile.ZIP_STORED or payload_offset % 16384 == 0
            elf_ok = bool(segments) and all(s["alignment"] >= 16384 and s["congruent16KiB"] for s in segments)
            result[name] = {"loadSegments": segments, "compressed": info.compress_type != zipfile.ZIP_STORED,
                            "zip16KiBOrExtracted": zip_ok, "elf16KiB": elf_ok}
            if not zip_ok or not elf_ok:
                raise SystemExit(f"16 KiB compatibility failed: {name}: {result[name]}")
    return result


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
        "alignment": alignment_report(after, [n for n in names if n not in a and n.endswith(".so")]),
    }
print(json.dumps(report, indent=2))
