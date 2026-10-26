#!/usr/bin/env python3
"""Stage this worktree's exact media closure; never bundle libc, glib, GTK or system drivers."""
import pathlib
import platform
import os
import re
import shutil
import subprocess

root = pathlib.Path(__file__).resolve().parents[2]
build = root / "build"
prefix = build / "native/prefix/lib"
engine = build / "native/engine-build"
# Compose copies the resource directory named after the host's OS and architecture.
arch = "arm64" if platform.machine() in ("aarch64", "arm64") else "x64"
destination = build / f"distribution-resources/linux-{arch}/native"
destination.mkdir(parents=True, exist_ok=True)
subprocess.run(["cmake", "--install", str(engine), "--prefix", str(destination)], check=True)
allowed = ("libavcodec.so.", "libavformat.so.", "libavfilter.so.", "libavutil.so.",
           "libavdevice.so.", "libswresample.so.", "libswscale.so.",
           "libssl.so.", "libcrypto.so.", "libxml2.so.")
copied = set()
for library in sorted(prefix.iterdir()):
    if not library.name.startswith(allowed) or not library.is_file():
        continue
    dynamic = subprocess.run(["readelf", "-d", str(library)], check=True, text=True, capture_output=True).stdout
    match = re.search(r"\(SONAME\).*\[(.*?)\]", dynamic)
    if not match:
        raise RuntimeError(f"Missing SONAME: {library.name}")
    name = match[1]
    if name not in copied:
        shutil.copyfile(library, destination / name)
        copied.add(name)
if not any(name.startswith("libavformat.") for name in copied):
    raise RuntimeError("Missing FFmpeg closure; build native dependencies first")
for library in sorted(destination.iterdir()):
    result = subprocess.run(["ldd", str(library)], check=True, text=True, capture_output=True,
                            env={**os.environ, "LD_LIBRARY_PATH": str(destination)})
    if "not found" in result.stdout:
        raise RuntimeError(result.stdout)
    dynamic = subprocess.run(["readelf", "-d", str(library)], check=True, text=True, capture_output=True).stdout
    if str(root) in dynamic:
        raise RuntimeError(f"Non-relocatable runtime path in {library.name}")
    print(f"Staged native: {library.name}")
notices = build / "distribution-resources/common/notices"
notices.mkdir(parents=True, exist_ok=True)
for source, name in (
    (root.parent / "LICENSE", "ArchiveTune-GPL-3.0.txt"),
    (build / "native/src/ffmpeg-8.1.3/COPYING.LGPLv3", "FFmpeg-LGPL-3.0.txt"),
    (build / "native/src/openssl-3.5.9/LICENSE.txt", "OpenSSL-3.5.9.txt"),
    (build / "native/src/libxml2-2.15.4/Copyright", "libxml2.txt"),
    (build / "native/src/miniaudio-0.11.25/LICENSE", "miniaudio.txt"),
):
    if not source.is_file():
        raise RuntimeError(f"Required license missing: {source}")
    shutil.copyfile(source, notices / name)
integration = build / "distribution-resources/common/integration"
integration.mkdir(parents=True, exist_ok=True)
for name in ("install-uri-handler.sh", "archivetune.png"):
    shutil.copyfile(root / "packaging" / name, integration / name)
