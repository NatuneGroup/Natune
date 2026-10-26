#!/usr/bin/env python3
"""Stage the host-built macOS media closure.

Every staged dylib is self-contained beside the app image: its install name becomes
@rpath/<soname>, every dependency that pointed inside the native dependency prefix is
rewritten to @rpath/<soname>, and each library carries an @loader_path rpath entry so
the chain resolves next to the loading binary. A package that still references a
build-tree path or a missing library is refused, never shipped.
"""
import pathlib
import platform
import re
import shutil
import subprocess
import sys

root = pathlib.Path(__file__).resolve().parents[2]
build = root / "build"
prefix_lib = build / "native/prefix/lib"
engine = build / "native/engine-build"
# Compose copies the resource directory named after the host's OS and architecture.
arch = "arm64" if platform.machine() in ("aarch64", "arm64") else "x64"
destination = build / f"distribution-resources/macos-{arch}/native"
destination.mkdir(parents=True, exist_ok=True)
subprocess.run(["cmake", "--install", str(engine), "--prefix", str(destination)], check=True)


def run(*command):
    subprocess.run(list(command), check=True)


def otool_lines(binary):
    out = subprocess.run(["otool", "-L", str(binary)], check=True, text=True,
                         capture_output=True).stdout
    # The first line is the file path; each later line is '<path> (compatibility ...)'.
    return [line.strip().split(" ", 1)[0] for line in out.splitlines()[1:] if line.strip()]


def install_name(binary):
    return subprocess.run(["otool", "-D", str(binary)], check=True, text=True,
                          capture_output=True).stdout.splitlines()[-1].strip()


# Stage the versioned media dylibs under the SONAME their dependents record, skipping
# development symlinks and static archives.
sonames = {}
for library in sorted(prefix_lib.iterdir()):
    if library.is_symlink() or not library.is_file() or not library.name.endswith(".dylib"):
        continue
    soname = pathlib.PurePosixPath(install_name(library)).name
    if soname in sonames:
        continue
    shutil.copyfile(library, destination / soname)
    sonames[soname] = destination / soname
if not any(name.startswith("libavformat.") for name in sonames):
    sys.exit("Missing FFmpeg closure; run build-native-deps.sh first")

binaries = [path for path in sorted(destination.iterdir()) if path.is_file()]
for path in binaries:
    run("install_name_tool", "-id", f"@rpath/{path.name}", str(path))
    for dep in otool_lines(path):
        if dep == str(path) or dep.startswith("@"):
            continue
        soname = pathlib.PurePosixPath(dep).name
        if dep.startswith("/usr/lib/") or dep.startswith("/System/Library/"):
            continue
        if soname in sonames or soname in {p.name for p in binaries}:
            run("install_name_tool", "-change", dep, f"@rpath/{soname}", str(path))
        else:
            sys.exit(f"{path.name} depends on {dep}, which is not staged")
    rpath_lines = subprocess.run(["otool", "-l", str(path)], check=True, text=True,
                                 capture_output=True).stdout
    if "@loader_path" not in rpath_lines:
        run("install_name_tool", "-add_rpath", "@loader_path", str(path))

# Re-read every load command: nothing may keep a path inside this worktree.
for path in binaries:
    for dep in otool_lines(path):
        if str(root) in dep or str(build) in dep:
            sys.exit(f"{path.name} keeps a build-tree dependency: {dep}")
        if dep.startswith("/") and not dep.startswith(("/usr/lib/", "/System/Library/")):
            sys.exit(f"{path.name} keeps a non-system dependency: {dep}")
    print(f"Staged native: {path.name}")

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
        sys.exit(f"Required license missing: {source}")
    shutil.copyfile(source, notices / name)
