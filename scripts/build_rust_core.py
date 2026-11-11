#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Native build mechanics for shared-core; never installs Rust targets or an NDK."""
import argparse
import os
from pathlib import Path
import platform
import shutil
import subprocess

ROOT = Path(__file__).resolve().parent.parent
WORKSPACE = ROOT / "native/core"
BUILD = ROOT / "shared-core/build"
TARGET = BUILD / "cargo"
GOBLEY = "0.3.7"
ANDROID = {
    "arm64-v8a": ("aarch64-linux-android", "aarch64-linux-android"),
    "armeabi-v7a": ("armv7-linux-androideabi", "armv7a-linux-androideabi"),
    "x86_64": ("x86_64-linux-android", "x86_64-linux-android"),
    "x86": ("i686-linux-android", "i686-linux-android"),
}


def run(*args, env=None):
    subprocess.run(args, cwd=WORKSPACE, env=env, check=True)


def build(target=None, extra=None):
    env = dict(os.environ, CARGO_TARGET_DIR=str(TARGET), CARGO_BUILD_JOBS="2")
    env.update(extra or {})
    command = ["cargo", "build", "--locked", "--release", "-p", "natune-ffi"]
    if target:
        command += ["--target", target]
    run(*command, env=env)
    return TARGET / (target or "") / "release"


def host_library():
    directory = build()
    name = {"Linux": "libnatune_ffi.so", "Darwin": "libnatune_ffi.dylib", "Windows": "natune_ffi.dll"}[platform.system()]
    return directory / name


def bindings():
    library = host_library()
    executable = BUILD / "tools/bin" / ("gobley-uniffi-bindgen.exe" if os.name == "nt" else "gobley-uniffi-bindgen")
    if not executable.exists():
        env = dict(os.environ, CARGO_TARGET_DIR=str(BUILD / "bindgen-target"), CARGO_BUILD_JOBS="2")
        run("cargo", "install", "--locked", "--version", GOBLEY, "gobley-uniffi-bindgen", "--root", str(BUILD / "tools"), env=env)
        shutil.rmtree(BUILD / "bindgen-target")
    version = subprocess.check_output([str(executable), "--version"], text=True).strip()
    if version != f"gobley-uniffi-bindgen {GOBLEY}":
        raise SystemExit(f"Unexpected generator version: {version}")
    output = BUILD / "generated/bindings"
    if output.exists():
        shutil.rmtree(output)
    run(str(executable), "--library", str(library), "--crate", "natune_ffi", "--config", str(WORKSPACE / "crates/natune-ffi/uniffi.toml"), "--out-dir", str(output))
    for target in ("common", "jvm", "android", "native"):
        if not list((output / f"{target}Main/kotlin").rglob(f"natune_ffi.{target}.kt")):
            raise SystemExit(f"Gobley did not generate {target} bindings; check the flat CLI configuration")


def jvm(target=None):
    if target:
        directory = build(target)
        names = {
            "x86_64-unknown-linux-gnu": ("linux-x86-64", "libnatune_ffi.so"),
            "aarch64-unknown-linux-gnu": ("linux-aarch64", "libnatune_ffi.so"),
            "x86_64-pc-windows-msvc": ("win32-x86-64", "natune_ffi.dll"),
            "aarch64-apple-darwin": ("darwin-aarch64", "libnatune_ffi.dylib"),
        }
        prefix, name = names[target]
        library = directory / name
    else:
        library = host_library()
        arch = "aarch64" if platform.machine().lower() in ("aarch64", "arm64") else "x86-64"
        prefix = {"Linux": "linux", "Darwin": "darwin", "Windows": "win32"}[platform.system()] + "-" + arch
    resources = BUILD / "generated/rustResources"
    if resources.exists():
        shutil.rmtree(resources)
    destination = resources / prefix
    destination.mkdir(parents=True, exist_ok=True)
    shutil.copy2(library, destination / library.name)


def android(abis):
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    pinned = Path(sdk or "") / "ndk/30.0.16248370"
    ndk = pinned if pinned.is_dir() else Path(os.environ.get("ANDROID_NDK_HOME") or str(pinned))
    properties = ndk / "source.properties"
    if not properties.is_file() or not any(line.strip() == "Pkg.Revision = 30.0.16248370" for line in properties.read_text().splitlines()):
        raise SystemExit(f"Expected repository NDK 30.0.16248370: {ndk}")
    host = {"Linux": "linux-x86_64", "Darwin": "darwin-x86_64", "Windows": "windows-x86_64"}[platform.system()]
    toolchain = ndk / "toolchains/llvm/prebuilt" / host / "bin"
    for abi in abis.split(","):
        target, clang = ANDROID[abi]
        linker = toolchain / (clang + "24-clang" + (".cmd" if os.name == "nt" else ""))
        if not linker.exists():
            raise SystemExit(f"Install the repository's NDK 30.0.16248370 in CI first: {linker}")
        env = {
            "CARGO_TARGET_" + target.upper().replace("-", "_") + "_LINKER": str(linker),
            "CARGO_TARGET_" + target.upper().replace("-", "_") + "_RUSTFLAGS": "-C link-arg=-Wl,-z,max-page-size=16384",
        }
        directory = build(target, env)
        destination = BUILD / "generated/androidJniLibs" / abi
        destination.mkdir(parents=True, exist_ok=True)
        run(str(toolchain / "llvm-strip"), "--strip-unneeded", str(directory / "libnatune_ffi.so"))
        shutil.copy2(directory / "libnatune_ffi.so", destination / "libnatune_ffi.so")


def interop(target):
    directory = build(target)
    definition = BUILD / f"generated/interop/{target}.def"
    definition.parent.mkdir(parents=True, exist_ok=True)
    definition.write_text(f"package = natune_ffi.cinterop\nstaticLibraries = libnatune_ffi.a\nlibraryPaths = {directory.as_posix()}\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("operation", choices=["bindings", "jvm", "android", "interop"])
    parser.add_argument("--target")
    parser.add_argument("--abis", default=",".join(ANDROID))
    args = parser.parse_args()
    {"bindings": bindings, "jvm": lambda: jvm(args.target), "android": lambda: android(args.abis), "interop": lambda: interop(args.target)}[args.operation]()
