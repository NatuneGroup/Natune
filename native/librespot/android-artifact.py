#!/usr/bin/env python3
"""Small CI-only checks for the prepared JNI cache and the APK that consumes it."""

import argparse
import hashlib
import json
import re
import struct
import subprocess
import zipfile
from pathlib import Path


RECIPE = Path(__file__).resolve().parent
REPO = RECIPE.parent.parent
OUTPUT = REPO / "app/build/generated/librespot/jniLibs"
LIBRARY = "libnatune_librespot.so"
JNI_SOURCE = REPO / "app/src/main/kotlin/moe/rukamori/archivetune/spotify/librespot/SpotifyLibrespotNative.kt"
MACHINES = {"arm64-v8a": (2, 183), "armeabi-v7a": (1, 40), "x86": (1, 3), "x86_64": (2, 62)}


def abis(selection):
    if selection in MACHINES:
        return [selection]
    flavor = selection.rsplit("-", 1)[-1]
    selected = {
        "universal": ["arm64-v8a", "x86_64"], "arm64": ["arm64-v8a"],
        "armeabi": ["armeabi-v7a"], "x86": ["x86"], "x86_64": ["x86_64"],
        "all": list(MACHINES),
    }.get(flavor)
    if selected is None:
        raise ValueError("Unsupported Android ABI selection")
    # AGP combines the TV device's 32-bit filters with the ABI flavor's filters.
    if "tv" in selection.split("-"):
        selected = list(dict.fromkeys(selected + ["armeabi-v7a", "x86"]))
    return selected


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def fingerprint():
    paths = [RECIPE / name for name in ("Cargo.toml", "Cargo.lock", "embedding.patch", "upstream-revision")]
    paths += list(RECIPE.glob("*.sh")) + list(RECIPE.glob("*.py")) + list((RECIPE / "src").glob("*.rs"))
    result = hashlib.sha256()
    for path in sorted(paths):
        result.update(str(path.relative_to(RECIPE)).encode() + b"\0" + path.read_bytes() + b"\0")
    result.update(b"kotlin-jni\0" + JNI_SOURCE.read_bytes())
    return result.hexdigest()


def check_elf(source, abi):
    header = source.read(64)
    elf_class, machine = MACHINES[abi]
    if header[:6] != b"\x7fELF" + bytes([elf_class, 1]) or struct.unpack_from("<HH", header, 16) != (3, machine):
        raise ValueError(f"Invalid JNI ELF architecture for {abi}")
    offset = struct.unpack_from("<Q" if elf_class == 2 else "<I", header, 32 if elf_class == 2 else 28)[0]
    size, count = struct.unpack_from("<HH", header, 54 if elf_class == 2 else 42)
    if not 1 <= count <= 128 or size != (56 if elf_class == 2 else 32):
        raise ValueError(f"Invalid JNI ELF segments for {abi}")
    for index in range(count):
        source.seek(offset + index * size)
        segment = struct.unpack("<IIQQQQQQ" if elf_class == 2 else "<IIIIIIII", source.read(size))
        if segment[0] == 1:  # PT_LOAD: page size and offset must both be 16 KiB compatible.
            file_offset, address = segment[2:4] if elf_class == 2 else segment[1:3]
            if segment[7] < 16384 or file_offset % 16384 != address % 16384:
                raise ValueError(f"JNI ELF is not 16 KiB aligned for {abi}")


def record(selected, toolchain):
    kotlin = JNI_SOURCE.read_text()
    methods = re.findall(r"external fun (native\w+)\(", kotlin)
    required = {"Java_moe_rukamori_archivetune_spotify_librespot_SpotifyLibrespotNative_" + name for name in methods}
    if len(required) != 5:
        raise ValueError("Review the changed Kotlin JNI contract before recording libraries")
    libraries = {}
    for abi in selected:
        library = OUTPUT / abi / LIBRARY
        with library.open("rb") as source:
            check_elf(source, abi)
        symbols = subprocess.check_output([str(toolchain / "llvm-nm"), "--dynamic", "--defined-only", str(library)], text=True)
        if not required.issubset({line.split()[-1] for line in symbols.splitlines() if line.split()}):
            raise ValueError(f"JNI exports do not match Kotlin externals for {abi}")
        libraries[abi] = digest(library)
    (OUTPUT / "manifest.json").write_text(json.dumps({"recipe": fingerprint(), "libraries": libraries}, sort_keys=True) + "\n")


def validate(selected):
    manifest = json.loads((OUTPUT / "manifest.json").read_text())
    if manifest["recipe"] != fingerprint() or set(manifest["libraries"]) != set(selected):
        raise ValueError("Prepared JNI cache does not match this recipe/ABI selection")
    for abi in selected:
        library = OUTPUT / abi / LIBRARY
        with library.open("rb") as source:
            check_elf(source, abi)
        if digest(library) != manifest["libraries"][abi]:
            raise ValueError(f"Prepared JNI cache checksum mismatch for {abi}")
    return manifest


def check_apks(selected, available, root):
    apks = sorted(root.rglob("*.apk"))
    if not apks:
        raise ValueError("No assembled APK to verify")
    manifest = validate(selected) if available else None
    expected = {f"lib/{abi}/{LIBRARY}" for abi in selected} if available else set()
    for apk in apks:
        with zipfile.ZipFile(apk) as archive:
            actual = {name for name in archive.namelist() if name.endswith("/" + LIBRARY)}
            if actual != expected:
                raise ValueError(f"Librespot ABI closure mismatch in {apk.name}")
            for abi in (selected if available else []):
                with archive.open(f"lib/{abi}/{LIBRARY}") as source:
                    if hashlib.file_digest(source, "sha256").hexdigest() != manifest["libraries"][abi]:
                        raise ValueError(f"Packaged JNI differs from prepared JNI in {apk.name} ({abi})")
        print(f"{apk.name}: Librespot " + (", ".join(selected) if available else "unavailable (native preparation failed)"))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["abis", "record", "validate", "apk"])
    parser.add_argument("selection", nargs="?", default="universal")
    parser.add_argument("--toolchain", type=Path)
    parser.add_argument("--available", choices=["true", "false"], default="true")
    parser.add_argument("--apk-root", type=Path, default=REPO / "app/build/outputs/apk")
    args = parser.parse_args()
    selected = abis(args.selection)
    if args.command == "abis":
        print(" ".join(selected))
    elif args.command == "record":
        if args.toolchain is None:
            parser.error("record requires --toolchain")
        record(selected, args.toolchain)
    elif args.command == "validate":
        validate(selected)
    else:
        check_apks(selected, args.available == "true", args.apk_root)
