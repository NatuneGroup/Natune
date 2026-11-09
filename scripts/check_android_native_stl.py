#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (c) 2026 Natune contributors
"""Check packaged ELF dependencies and stored arsc without loading native code."""

import argparse
from pathlib import Path
import struct
import zipfile

MAX_LIBRARY_BYTES = 64 * 1024 * 1024


def elf_needed(data):
    """Read DT_NEEDED via program headers, including section-stripped Android ELF files."""
    if data[:4] != b"\x7fELF" or len(data) < 64 or data[5] != 1:
        raise ValueError("expected a little-endian ELF library")
    if data[4] == 2:
        phoff = struct.unpack_from("<Q", data, 32)[0]
        phsize, phnum = struct.unpack_from("<HH", data, 54)
        phformat, dynamicformat = "<IIQQQQQQ", "<qQ"
    elif data[4] == 1:
        phoff = struct.unpack_from("<I", data, 28)[0]
        phsize, phnum = struct.unpack_from("<HH", data, 42)
        phformat, dynamicformat = "<IIIIIIII", "<iI"
    else:
        raise ValueError("unsupported ELF class")
    if phsize < struct.calcsize(phformat) or phoff + phsize * phnum > len(data):
        raise ValueError("invalid ELF program headers")
    loads, dynamics = [], []
    for index in range(phnum):
        values = struct.unpack_from(phformat, data, phoff + index * phsize)
        kind = values[0]
        offset, address, size = (values[2], values[3], values[5]) if data[4] == 2 else (values[1], values[2], values[4])
        if kind in (1, 2) and offset + size > len(data):
            raise ValueError("ELF segment exceeds the library")
        if kind == 1:
            loads.append((offset, address, size))
        elif kind == 2:
            dynamics.append((offset, size))
    tags = {}
    for offset, size in dynamics:
        entrysize = struct.calcsize(dynamicformat)
        if size % entrysize:
            raise ValueError("invalid ELF dynamic segment")
        for position in range(offset, offset + size, entrysize):
            tag, value = struct.unpack_from(dynamicformat, data, position)
            if tag == 0:
                break
            tags.setdefault(tag, []).append(value)
    needed = tags.get(1, [])
    if not needed:
        return set()
    if len(tags.get(5, [])) != 1 or len(tags.get(10, [])) != 1:
        raise ValueError("missing ELF dynamic string table")
    address, size = tags[5][0], tags[10][0]
    for offset, start, length in loads:
        if start <= address and address + size <= start + length:
            position = offset + address - start
            strings = data[position:position + size]
            break
    else:
        raise ValueError("ELF string table is outside file-backed segments")
    result = set()
    for index in needed:
        if index >= len(strings):
            raise ValueError("ELF dependency name is outside the string table")
        end = strings.find(b"\0", index)
        if end == -1:
            raise ValueError("unterminated ELF dependency name")
        result.add(strings[index:end].decode("ascii"))
    return result


def audit_apk(path, expect_static=False):
    with zipfile.ZipFile(path) as archive:
        if archive.getinfo("resources.arsc").compress_type != zipfile.ZIP_STORED:
            raise ValueError("resources.arsc must stay stored")
        libraries = {entry.filename: entry for entry in archive.infolist()
                     if entry.filename.startswith("lib/") and entry.filename.endswith(".so")}
        if not libraries:
            raise ValueError("APK has no native libraries to audit")
        for name, entry in libraries.items():
            if expect_static and name.endswith("/libc++_shared.so"):
                raise ValueError("standard APK unexpectedly packages libc++_shared.so")
            if entry.file_size > MAX_LIBRARY_BYTES:
                raise ValueError(f"native library exceeds audit budget: {name}")
            dependencies = elf_needed(archive.read(entry))
            if "libc++_shared.so" in dependencies:
                sibling = name.rsplit("/", 1)[0] + "/libc++_shared.so"
                if expect_static or sibling not in libraries:
                    raise ValueError(f"unsatisfied shared STL dependency: {name}")
        return len(libraries)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--expect-static", action="store_true")
    args = parser.parse_args()
    try:
        count = audit_apk(args.apk, args.expect_static)
    except (ValueError, KeyError, OSError, zipfile.BadZipFile, struct.error) as error:
        parser.exit(1, f"Android native packaging: {error}\n")
    print(f"Android native packaging: {count} ELF libraries audited; arsc is stored.")


if __name__ == "__main__":
    main()
