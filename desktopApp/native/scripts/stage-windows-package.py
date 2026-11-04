#!/usr/bin/env python3
"""Stage this worktree's exact media closure for Windows x64.

Walks the PE import table of every staged DLL and copies the ones this build produced from the
dependency prefix. Windows system libraries are an allowlist that is never bundled; anything else
that is neither staged nor a known system DLL fails the stage instead of shipping.
"""
import importlib
import os
import pathlib
import shutil
import subprocess
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
# The import walk and the system-library allowlist live once, in the verifier the release workflow also runs.
native_deps = importlib.import_module("windows-native-deps")

root = pathlib.Path(__file__).resolve().parents[2]
build = root / "build"
prefix = build / "native/prefix"
engine = build / "native/engine-build"
destination = build / "distribution-resources/windows-x64/native"
destination.mkdir(parents=True, exist_ok=True)
subprocess.run(["cmake", "--install", str(engine), "--prefix", str(destination)], check=True)

def imports(library):
    machine, direct, delayed = native_deps.imports(library)
    # A delay import is resolved on first call, but a package without it still fails at run time.
    return list(direct) + [name for name in delayed if name not in direct]


prefix_files = {p.name.lower(): p for p in prefix.glob("**/*.dll") if p.is_file()}
# The MinGW toolchain's runtime (winpthreads, libgcc, libstdc++) and the libraries it supplies to the prefix builds
# (libiconv for libxml2) live in its bin directory, not the prefix. A DLL the prefix lacks may come from there, and is
# named in the log; one found in neither still fails the audit.
mingw_bin = pathlib.Path(os.environ["ARCHIVETUNE_MINGW_BIN"]) if os.environ.get("ARCHIVETUNE_MINGW_BIN") else None
mingw_files = {p.name.lower(): p for p in mingw_bin.glob("*.dll") if p.is_file()} if mingw_bin else {}
from_toolchain = []
staged = set()
queue = [p for p in destination.iterdir() if p.name.lower().endswith(".dll")]
while queue:
    library = queue.pop()
    for dep in imports(library):
        key = dep.lower()
        if key in staged or native_deps.is_system(key):
            continue
        source = prefix_files.get(key)
        if source is None and key in mingw_files:
            source = mingw_files[key]
            from_toolchain.append(source.name)
        if source is None:
            raise RuntimeError(f"Unstaged dependency {dep} required by {library.name}")
        target = destination / source.name
        shutil.copyfile(source, target)
        staged.add(key)
        queue.append(target)

if not any(name.startswith("avformat") for name in staged):
    raise RuntimeError("Missing FFmpeg closure; build native dependencies first")
staged_libraries = sorted(library for library in destination.iterdir() if library.name.lower().endswith(".dll"))
for library in staged_libraries:
    print(f"Staged native: {library.name}", file=sys.stderr)

# Windows has no rpath, so the engine loads this order explicitly. Writing it here keeps the manifest
# derived from the DLLs actually staged; the audit above already rejected anything unknown.
try:
    order = native_deps.write_manifest(destination)
except ValueError as error:
    raise RuntimeError(f"The staged Windows closure is incomplete: {error}") from error
print(f"Staged native load order: {', '.join(order)}", file=sys.stderr)

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

# MSYS2 installs each package's license under share/licenses/<package>; copy those of the toolchain DLLs that shipped.
TOOLCHAIN_LICENSES = {"libwinpthread": "winpthreads", "libgcc_s": "gcc-libs", "libstdc++": "gcc-libs",
                      "libiconv": "libiconv"}
for name in from_toolchain:
    print(f"Staged from the MinGW toolchain: {name}", file=sys.stderr)
    package = next((p for prefix_name, p in TOOLCHAIN_LICENSES.items() if name.lower().startswith(prefix_name)), None)
    licenses = mingw_bin.parent / "share/licenses" / package if package else None
    if licenses and licenses.is_dir():
        shutil.copytree(licenses, notices / f"mingw-{package}", dirs_exist_ok=True)
