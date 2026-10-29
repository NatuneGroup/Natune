#!/usr/bin/env python3
"""Stage this worktree's exact media closure for macOS.

Every staged dylib gets an @rpath install name and every non-system dependency is rewritten to
@rpath/<name>, so the package loads its own closure from the resource directory and never resolves
against the build prefix. Anything the audit cannot account for fails the stage instead of shipping.
"""
import argparse
import pathlib
import platform
import re
import shutil
import subprocess
import sys

SYSTEM_PREFIXES = ("/System/", "/usr/lib/system/", "/usr/lib/libSystem", "/usr/lib/libc++",
                   "/usr/lib/libobjc", "/usr/lib/libresolv")


def otool(arguments):
    return subprocess.run(["otool"] + arguments, check=True, text=True, capture_output=True).stdout


def install_name(library):
    lines = otool(["-D", str(library)]).splitlines()
    if len(lines) < 2 or not lines[1].strip():
        raise RuntimeError(f"Missing install name: {library.name}")
    return lines[1].strip()


def dependencies(library):
    lines = otool(["-L", str(library)]).splitlines()
    return [line.strip().split(" ", 1)[0] for line in lines[2:] if line.strip()]


def stage(arch, root=None):
    if arch not in ("arm64", "x64"):
        raise ValueError(f"Unsupported macOS package architecture: {arch}")
    lipo_arch = "x86_64" if arch == "x64" else "arm64"
    print(f"Staging the macOS {arch} closure as lipo architecture {lipo_arch}", file=sys.stderr)
    root = pathlib.Path(root) if root is not None else pathlib.Path(__file__).resolve().parents[2]
    build = root / "build"
    prefix = build / "native/prefix/lib"
    engine = build / "native/engine-build"
    destination = build / f"distribution-resources/macos-{arch}/native"
    destination.mkdir(parents=True, exist_ok=True)
    subprocess.run(["cmake", "--install", str(engine), "--prefix", str(destination)], check=True)
    if not (destination / "libarchivetune_engine.dylib").is_file():
        raise RuntimeError(f"CMake did not install the playback engine into {destination}")

    # The closure name is the install-name basename, so a dependent's recorded path resolves to it.
    allowed = ("libavcodec.", "libavformat.", "libavfilter.", "libavutil.", "libavdevice.",
               "libswresample.", "libswscale.", "libssl.", "libcrypto.", "libxml2.", "libz.")
    staged = set()
    for library in sorted(prefix.iterdir()):
        if not library.name.endswith(".dylib") or not library.name.startswith(allowed) or library.is_symlink():
            continue
        name = install_name(library).rsplit("/", 1)[-1]
        if name not in staged:
            shutil.copyfile(library, destination / name)
            staged.add(name)
    if not any(name.startswith("libavformat.") for name in staged):
        raise RuntimeError("Missing FFmpeg closure; build native dependencies first")

    for library in sorted(destination.iterdir()):
        if not library.name.endswith(".dylib") or library.is_symlink():
            continue
        # Audit the binary, not Python's architecture: Homebrew Python may remain arm64 under an x64 JDK.
        # lipo takes the input file before the command (`lipo <input_file> -verify_arch <arch>`); with the
        # operation first it reads the trailing path as another architecture flag and rejects every dylib.
        subprocess.run(["lipo", str(library), "-verify_arch", lipo_arch], check=True)
        rewrites = ["-id", f"@rpath/{library.name}"]
        for dep in dependencies(library):
            name = dep.rsplit("/", 1)[-1]
            if dep.startswith(("@rpath/", "@loader_path/", "@executable_path/")):
                if name != library.name:
                    rewrites += ["-change", dep, f"@rpath/{name}"]
            elif dep.startswith("/"):
                if dep.startswith(SYSTEM_PREFIXES) or dep.startswith("/usr/lib/"):
                    continue
                if name in staged or name == library.name:
                    rewrites += ["-change", dep, f"@rpath/{name}"]
                else:
                    raise RuntimeError(f"Unstaged dependency {dep} in {library.name}")
            else:
                raise RuntimeError(f"Unexpected dependency {dep} in {library.name}")
        subprocess.run(["install_name_tool"] + rewrites + [str(library)], check=True)
        rpaths = re.findall(r"LC_RPATH\s*\n\s*cmdsize \d+\s*\n\s*path (\S+)", otool(["-l", str(library)]))
        for rpath in rpaths:
            if rpath != "@loader_path":
                subprocess.run(["install_name_tool", "-delete_rpath", rpath, str(library)], check=True)
        if "@loader_path" not in rpaths:
            subprocess.run(["install_name_tool", "-add_rpath", "@loader_path", str(library)], check=True)

    # Audit: every dependency must be a system path or an @rpath name that the staged set provides.
    for library in sorted(destination.iterdir()):
        if not library.name.endswith(".dylib") or library.is_symlink():
            continue
        for dep in dependencies(library):
            name = dep.rsplit("/", 1)[-1]
            if dep.startswith("/"):
                if not (dep.startswith(SYSTEM_PREFIXES) or dep.startswith("/usr/lib/")):
                    raise RuntimeError(f"Unresolved dependency {dep} in {library.name}")
            elif dep.startswith(("@rpath/", "@loader_path/")):
                if name != library.name and name not in staged:
                    raise RuntimeError(f"Unresolved @rpath dependency {dep} in {library.name}")
            else:
                raise RuntimeError(f"Unresolved dependency {dep} in {library.name}")
        # otool's first output line is the inspected file's own path; load commands follow it.
        dynamic = otool(["-l", str(library)]).split("\n", 1)[-1]
        if str(root) in dynamic or str(build) in dynamic:
            raise RuntimeError(f"Non-relocatable path in {library.name}")
        print(f"Staged native: {library.name}", file=sys.stderr)

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


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--arch", choices=("arm64", "x64"),
                        default="arm64" if platform.machine() in ("aarch64", "arm64") else "x64",
                        help="packaging JVM architecture, independent of the Python interpreter")
    stage(parser.parse_args(argv).arch)


if __name__ == "__main__":
    main()
