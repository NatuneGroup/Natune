#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (c) 2026 Natune contributors
"""Losslessly recompress an APK before zipalign and historical release signing.

The output is UNSIGNED: rewriting ZIP offsets invalidates existing APK signing
blocks. Never distribute it directly. Every entry, payload and storage method is
preserved, including licenses/notices and stored resources.arsc/native entries.
"""

import argparse
import copy
from pathlib import Path
import shutil
import zipfile

MAX_ENTRY_BYTES = 128 * 1024 * 1024


def recompress_apk(source, destination):
    source, destination = Path(source), Path(destination)
    if source.resolve() == destination.resolve():
        raise ValueError("input and unsigned output must be different paths")
    if destination.exists() and destination.stat().st_size:
        raise ValueError("unsigned output already contains data")
    try:
        with zipfile.ZipFile(source) as original:
            entries = original.infolist()
            if len({entry.filename for entry in entries}) != len(entries):
                raise ValueError("duplicate APK entry names")
            if original.getinfo("resources.arsc").compress_type != zipfile.ZIP_STORED:
                raise ValueError("resources.arsc must stay stored")
            for entry in entries:
                if entry.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
                    raise ValueError("unsupported APK compression method")
                if entry.file_size > MAX_ENTRY_BYTES:
                    raise ValueError("APK entry exceeds recompression budget")
            with zipfile.ZipFile(destination, "w", allowZip64=False) as output:
                output.comment = original.comment
                for entry in entries:
                    output.writestr(copy.copy(entry), original.read(entry), compresslevel=9)
        # ZIP compression ratios can vary. Never enlarge the candidate for this optimization.
        if destination.stat().st_size >= source.stat().st_size:
            shutil.copyfile(source, destination)
        return source.stat().st_size - destination.stat().st_size
    except Exception:
        destination.unlink(missing_ok=True)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("unsigned_output", type=Path)
    args = parser.parse_args()
    try:
        savings = recompress_apk(args.input, args.unsigned_output)
    except (ValueError, KeyError, OSError, zipfile.BadZipFile) as error:
        parser.exit(1, f"APK recompression: {error}\n")
    print(f"APK recompression saved {savings} bytes; output requires zipalign and signing.")


if __name__ == "__main__":
    main()
