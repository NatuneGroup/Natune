#!/usr/bin/env python3
"""Exact, validated caches for the pinned native dependency producers.

The desktop dependency and iOS download recipes fetch pinned third-party sources. This helper makes
their cache inputs and expected outputs explicit, derives one exact actions/cache key, and refuses a
restored tree unless its producer inputs, complete inventory, SHA-256 content and target binary
architecture still match. A rejected tree is rebuilt from the recipe instead of trusting stamps in a
relocated or partial prefix. It is standard-library only and runs no project build.
"""

from __future__ import annotations

import argparse
import fnmatch
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys

CACHE_SCHEMA = 1
DESKTOP = "desktop"
IOS_DOWNLOADS = "ios-downloads"
KINDS = (DESKTOP, IOS_DOWNLOADS)
TOOL_TIMEOUT_SECONDS = 60
OUTPUT_DELIMITER = "NATIVE_CACHE_PATHS"

PRODUCERS = {
    DESKTOP: "desktopApp/native/scripts/build-native-deps.sh",
    IOS_DOWNLOADS: "scripts/build_ios_native_core.sh",
}

ENVIRONMENT_INPUTS = (
    "ImageOS",
    "ImageVersion",
    "RUNNER_OS",
    "RUNNER_ARCH",
    "ARCHIVETUNE_NATIVE_JOBS",
    "ARCHIVETUNE_NATIVE_BUILD",
    "CC",
    "CXX",
    "AR",
    "RANLIB",
    "NM",
    "CFLAGS",
    "CXXFLAGS",
    "CPPFLAGS",
    "LDFLAGS",
    "CPATH",
    "LIBRARY_PATH",
    "PKG_CONFIG_PATH",
    "PKG_CONFIG_LIBDIR",
    "MAKEFLAGS",
    "MACOSX_DEPLOYMENT_TARGET",
    "SDKROOT",
    "DEVELOPER_DIR",
)


class CacheError(RuntimeError):
    """A cache input or restored tree cannot be trusted."""


def _native_path(value: str) -> str:
    """Accept MSYS2's /d/... form when the helper itself runs under Windows Python."""
    if os.name == "nt":
        match = re.fullmatch(r"/([A-Za-z])/(.*)", value)
        if match:
            return f"{match.group(1).upper()}:/{match.group(2)}"
    return value


def normalized(value: object) -> str:
    return os.path.normpath(_native_path(str(value))).replace("\\", "/")


def workspace_root(value: str | None) -> Path:
    return Path(os.path.abspath(_native_path(value or os.getcwd())))


def relative_to(workspace: Path, path: Path) -> str:
    # Lexical names are the manifest identity; symlink targets are validated separately.
    try:
        return normalized(Path(os.path.abspath(path)).relative_to(Path(os.path.abspath(workspace))))
    except ValueError as error:
        raise CacheError(f"{path} is outside the checkout {workspace}") from error


def _within(path: Path, root: Path) -> bool:
    try:
        Path(os.path.abspath(path)).relative_to(Path(os.path.abspath(root)))
        return True
    except ValueError:
        return False


def _safe_generated_path(workspace: Path, path: Path) -> Path:
    """Reject a cache path whose root or any ancestor resolves outside the checkout."""
    workspace_lexical = Path(os.path.abspath(workspace))
    workspace_real = workspace_lexical.resolve()
    lexical = Path(os.path.abspath(path))
    if not _within(lexical, workspace_lexical):
        raise CacheError(f"cache path is outside the checkout: {path}")
    current = workspace_lexical
    for part in lexical.relative_to(workspace_lexical).parts:
        current = current / part
        if not _within(current.resolve(), workspace_real):
            raise CacheError(f"cache path escapes the checkout through a symlink: {path}")
    return lexical


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sha256_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def canonical_json(value: object) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True)


def run_text(command: list[str]) -> str:
    try:
        result = subprocess.run(
            command,
            check=False,
            text=True,
            capture_output=True,
            timeout=TOOL_TIMEOUT_SECONDS,
        )
    except (OSError, subprocess.SubprocessError) as error:
        raise CacheError(f"cannot run tool identity command {command!r}: {error}") from error
    output = (result.stdout + result.stderr).strip()
    if result.returncode != 0:
        raise CacheError(
            f"tool identity command {command!r} failed with exit {result.returncode}: {output}"
        )
    if not output:
        raise CacheError(f"tool identity command {command!r} produced no output")
    return output


def constant(text: str, name: str) -> str:
    pattern = re.compile(
        rf"(?m)^{re.escape(name)}=(\"([^\"]*)\"|'([^']*)'|(\S+))\s*$"
    )
    match = pattern.search(text)
    if match is None:
        raise CacheError(
            f"{name} is missing from the producer; update scripts/native_dependency_cache.py"
        )
    return next(group for group in match.groups()[1:] if group is not None)


def producer(workspace: Path, kind: str) -> tuple[str, str, dict[str, str]]:
    relative = PRODUCERS[kind]
    path = workspace / relative
    if not path.is_file():
        raise CacheError(f"producer is missing: {relative}")
    text = path.read_text(encoding="utf-8")
    pins = {
        "zlib_version": constant(text, "ZLIB_VERSION"),
        "zlib_sha256": constant(text, "ZLIB_SHA256"),
        "openssl_version": constant(text, "OPENSSL_VERSION"),
        "openssl_sha256": constant(text, "OPENSSL_SHA256"),
        "libxml2_version": constant(text, "LIBXML2_VERSION"),
        "libxml2_sha256": constant(text, "LIBXML2_SHA256"),
        "ffmpeg_version": constant(text, "FFMPEG_VERSION"),
        "ffmpeg_sha256": constant(text, "FFMPEG_SHA256"),
        "ffmpeg_asc_sha256": constant(text, "FFMPEG_ASC_SHA256"),
        "ffmpeg_devel_asc_sha256": constant(text, "FFMPEG_DEVEL_ASC_SHA256"),
        "ffmpeg_signing_fpr": constant(text, "FFMPEG_SIGNING_FPR"),
        "miniaudio_version": constant(text, "MINIAUDIO_VERSION"),
        "miniaudio_sha256": constant(text, "MINIAUDIO_SHA256"),
        "miniaudio_license_sha256": constant(text, "MINIAUDIO_LICENSE_SHA256"),
    }
    return relative, sha256_text(text), pins


ARCH_ALIASES = {
    "x64": "x64",
    "x86_64": "x64",
    "x86-64": "x64",
    "amd64": "x64",
    "arm64": "arm64",
    "aarch64": "arm64",
}


def canonical_arch(value: str) -> str:
    key = value.strip().lower()
    try:
        return ARCH_ALIASES[key]
    except KeyError as error:
        raise CacheError(f"unknown binary architecture {value!r}") from error


def expected_arch(platform: str, arch: str) -> str:
    return canonical_arch(arch)


def _elf_architectures(path: Path) -> set[str]:
    output = run_text(["readelf", "-h", str(path)])
    machines = re.findall(r"^\s*Machine:\s*([^\n]+)$", output, re.MULTILINE)
    if not machines:
        raise CacheError(f"cannot read ELF machines from {path}: {output}")
    architectures = set()
    for machine in machines:
        match = re.fullmatch(
            r"\s*(?:Advanced Micro Devices X86-64|AArch64|ARM AArch64)\s*", machine, re.IGNORECASE
        )
        if match is None:
            raise CacheError(f"unsupported ELF machine in {path}: {machine.strip()}")
        value = match.group(0).lower()
        architectures.add("x64" if "x86-64" in value else "arm64")
    return architectures


def _pe_architectures(path: Path) -> set[str]:
    output = run_text(["objdump", "-f", str(path)])
    formats = re.findall(r"file format\s+(\S+)", output, re.IGNORECASE)
    blocks = re.findall(
        r"file format\s+(\S+)\s*\narchitecture:\s*([^,\n]+)", output, re.IGNORECASE
    )
    if not formats or len(blocks) != len(formats):
        raise CacheError(f"cannot read every PE member from {path}: {output}")
    architectures = set()
    for file_format, machine in blocks:
        values = {file_format.strip().lower(), machine.strip().lower()}
        recognized = set()
        for value in values:
            if value in {"pe-x86-64", "pei-x86-64", "i386:x86-64", "x86-64", "x86_64"}:
                recognized.add("x64")
            elif value in {"pe-aarch64", "pei-aarch64", "aarch64", "arm64"}:
                recognized.add("arm64")
            else:
                raise CacheError(f"unsupported PE architecture in {path}: {value}")
        if len(recognized) != 1:
            raise CacheError(f"mixed PE architecture in {path}: {sorted(recognized)}")
        architectures |= recognized
    return architectures


def probe_architectures(path: Path, platform: str) -> set[str]:
    if platform == "linux":
        return _elf_architectures(path)
    if platform == "windows":
        return _pe_architectures(path)
    raise CacheError(f"no architecture probe for {platform}")


def probe_all_architectures(path: Path, platform: str) -> set[str]:
    if not path.name.endswith(".a"):
        return probe_architectures(path, platform)
    # Bulk archive readers report every member; don't fork once per object.
    if platform == "linux":
        return _elf_architectures(path)
    if platform == "windows":
        return _pe_architectures(path)
    raise CacheError(f"no archive architecture probe for {platform}")


def _binary_files(workspace: Path, root: Path) -> list[Path]:
    binaries: list[Path] = []
    for path in sorted(root.rglob("*")):
        if not path.is_file() or path.is_symlink():
            continue
        name = path.name
        if (
            name.endswith((".dylib", ".so", ".dll", ".a"))
            or ".so." in name
        ):
            binaries.append(path)
    return binaries


def validate_architecture(workspace: Path, kind: str, platform: str, arch: str) -> None:
    roots: list[Path] = []
    if kind == DESKTOP:
        roots = [workspace / "desktopApp/build/native/prefix", workspace / "desktopApp/build/native/openssl-static"]
    else:
        return
    expected = expected_arch(platform, arch)
    for root in roots:
        if not root.is_dir():
            raise CacheError(f"binary root is missing: {root}")
        for binary in _binary_files(workspace, root):
            if platform == "windows" and root.name == "prefix" and binary.name.endswith(".a"):
                # MinGW import libraries (.dll.a) are linker metadata, not loadable images; the DLLs
                # beside them and the static OpenSSL archives are checked.
                continue
            architectures = probe_all_architectures(binary, platform)
            if architectures != {expected}:
                raise CacheError(
                    f"wrong binary architecture in {binary}: {sorted(architectures)}, expected {expected}"
                )


def _matches(directory: Path, pattern: str) -> list[Path]:
    if not directory.is_dir():
        return []
    return [
        path
        for path in directory.iterdir()
        if fnmatch.fnmatch(path.name, pattern) and (path.is_file() or path.is_symlink())
    ]


def _require_files(directory: Path, patterns: tuple[str, ...], label: str) -> None:
    for pattern in patterns:
        if not _matches(directory, pattern):
            raise CacheError(f"missing {label} {directory / pattern}")


def _require_file(path: Path, label: str) -> None:
    if not (path.is_file() or path.is_symlink()):
        raise CacheError(f"missing {label}: {path}")


def _require_support_file(path: Path, expected_sha: str, label: str) -> None:
    _require_file(path, label)
    actual = sha256_file(path)
    if actual != expected_sha:
        raise CacheError(f"{label} checksum mismatch: {actual} != {expected_sha}")


def download_inventory(pins: dict[str, str]) -> dict[str, str]:
    return {
        "ffmpeg-devel.asc": pins["ffmpeg_devel_asc_sha256"],
        f"ffmpeg-{pins['ffmpeg_version']}.tar.xz.asc": pins["ffmpeg_asc_sha256"],
        f"ffmpeg-{pins['ffmpeg_version']}.tar.xz": pins["ffmpeg_sha256"],
        f"zlib-{pins['zlib_version']}.tar.gz": pins["zlib_sha256"],
        f"openssl-{pins['openssl_version']}.tar.gz": pins["openssl_sha256"],
        f"libxml2-{pins['libxml2_version']}.tar.xz": pins["libxml2_sha256"],
        "miniaudio.h": pins["miniaudio_sha256"],
        "miniaudio.LICENSE": pins["miniaudio_license_sha256"],
    }


def cache_relative_paths(kind: str, platform: str, workspace: Path) -> list[str]:
    _, _, pins = producer(workspace, kind)
    if kind == DESKTOP:
        base = "desktopApp/build/native"
        return [
            f"{base}/prefix",
            f"{base}/openssl-static",
            f"{base}/src/miniaudio-{pins['miniaudio_version']}",
            f"{base}/src/ffmpeg-{pins['ffmpeg_version']}/COPYING.LGPLv3",
            f"{base}/src/openssl-{pins['openssl_version']}/LICENSE.txt",
            f"{base}/src/libxml2-{pins['libxml2_version']}/Copyright",
            f"{base}/.native-deps-cache.json",
        ]
    if kind == IOS_DOWNLOADS:
        return ["shared-data/build/ios-native-work/downloads"]
    raise CacheError(f"unknown cache kind: {kind}")


def manifest_relative(kind: str, platform: str) -> str:
    if kind == DESKTOP:
        return "desktopApp/build/native/.native-deps-cache.json"
    if kind == IOS_DOWNLOADS:
        return "shared-data/build/ios-native-work/downloads/.cache-manifest.json"
    raise CacheError(f"unknown cache kind: {kind}")


def _symlink_entry(path: Path, allowed_roots: list[Path]) -> dict[str, object]:
    raw_target = os.readlink(path)
    target = Path(raw_target)
    resolved = (path.parent / target if not target.is_absolute() else target).resolve()
    if not resolved.exists():
        raise CacheError(f"broken symlink in the cache: {path} -> {raw_target}")
    if not any(_within(resolved, root) for root in allowed_roots):
        raise CacheError(f"symlink leaves the cached tree: {path} -> {raw_target}")
    return {"type": "symlink", "target": raw_target}


def validate_cache_topology(workspace: Path, kind: str, platform: str) -> None:
    base = {
        DESKTOP: "desktopApp/build/native",
        IOS_DOWNLOADS: "shared-data/build/ios-native-work",
    }[kind]
    _safe_generated_path(workspace, workspace / base)
    for relative in [*cache_relative_paths(kind, platform, workspace), manifest_relative(kind, platform)]:
        _safe_generated_path(workspace, workspace / relative)


def _add_entry(
    entries: dict[str, dict[str, object]],
    workspace: Path,
    path: Path,
    allowed_roots: list[Path],
) -> None:
    rel = relative_to(workspace, path)
    if rel in entries:
        return
    if path.is_symlink():
        entry = _symlink_entry(path, allowed_roots)
        entries[rel] = {"path": rel, **entry}
    elif path.is_file():
        entries[rel] = {
            "path": rel,
            "type": "file",
            "size": path.stat().st_size,
            "sha256": sha256_file(path),
        }


def collect_entries(
    workspace: Path, kind: str, platform: str, relative_paths: list[str] | None = None
) -> list[dict[str, object]]:
    paths = relative_paths or cache_relative_paths(kind, platform, workspace)
    manifest = manifest_relative(kind, platform)
    for relative in [*paths, manifest]:
        _safe_generated_path(workspace, workspace / relative)
    allowed_roots = []
    for relative in paths:
        root = workspace / relative
        if root.is_dir() and not root.is_symlink():
            allowed_roots.append(_safe_generated_path(workspace, root).resolve())
    entries: dict[str, dict[str, object]] = {}
    for relative in paths:
        root = workspace / relative
        if relative == manifest:
            # The manifest is written after its own inventory is hashed.
            continue
        if not root.exists() and not root.is_symlink():
            raise CacheError(f"cache path is missing: {relative}; the producer did not finish")
        if root.is_symlink() or root.is_file():
            if relative_to(workspace, root) != manifest:
                _add_entry(entries, workspace, root, allowed_roots)
            continue
        for directory, dirnames, filenames in os.walk(root, followlinks=False):
            directory_path = Path(directory)
            for name in list(dirnames):
                child = directory_path / name
                if child.is_symlink():
                    _add_entry(entries, workspace, child, allowed_roots)
                    dirnames.remove(name)
            for name in filenames:
                child = directory_path / name
                if relative_to(workspace, child) == manifest:
                    continue
                _add_entry(entries, workspace, child, allowed_roots)
    return [entries[key] for key in sorted(entries)]


def validate_expected_inventory(
    workspace: Path, kind: str, platform: str, arch: str, pins: dict[str, str] | None = None
) -> None:
    validate_cache_topology(workspace, kind, platform)
    _, _, parsed_pins = producer(workspace, kind)
    pins = pins or parsed_pins
    if kind == DESKTOP:
        prefix = workspace / "desktopApp/build/native/prefix"
        static = workspace / "desktopApp/build/native/openssl-static"
        if platform == "windows":
            _require_files(prefix / "bin", (
                "avcodec*.dll", "avformat*.dll", "avfilter*.dll", "avutil*.dll",
                "swresample*.dll", "swscale*.dll", "libssl*.dll", "libcrypto*.dll",
                "libxml2*.dll", "zlib*.dll",
            ), "desktop DLL")
            _require_file(prefix / "lib/libz.a", "MinGW zlib static archive")
        else:
            _require_files(prefix / "lib", (
                "libavcodec.so*", "libavformat.so*", "libavfilter.so*", "libavutil.so*",
                "libswresample.so*", "libswscale.so*", "libssl.so*", "libcrypto.so*",
                "libxml2.so*", "libz.so*",
            ), "Linux shared library")
        headers = (
            "libavformat/avformat.h", "libavcodec/avcodec.h", "libavfilter/avfilter.h",
            "libavutil/avutil.h", "libswresample/swresample.h", "libswscale/swscale.h",
            "openssl/ssl.h", "zlib.h",
        )
        for header in headers:
            _require_file(prefix / "include" / header, "dependency header")
        if not (prefix / "include/libxml2/libxml/parser.h").is_file() and not (
            prefix / "include/libxml/parser.h"
        ).is_file():
            raise CacheError("missing libxml2 parser header in the native prefix")
        for stamp in ("zlib", "openssl", "openssl-static", "libxml2", "ffmpeg"):
            _require_file(prefix / f".stamp-{stamp}", "dependency stamp")
        _require_file(static / "lib/libcrypto.a", "static OpenSSL archive")
        _require_file(static / "lib/libssl.a", "static OpenSSL archive")
        miniaudio = workspace / "desktopApp/build/native/src" / f"miniaudio-{pins['miniaudio_version']}"
        _require_file(miniaudio / "miniaudio.h", "miniaudio header")
        _require_file(miniaudio / "LICENSE", "miniaudio license")
        base = workspace / "desktopApp/build/native/src"
        _require_file(base / f"ffmpeg-{pins['ffmpeg_version']}/COPYING.LGPLv3", "FFmpeg license")
        _require_file(base / f"openssl-{pins['openssl_version']}/LICENSE.txt", "OpenSSL license")
        _require_file(base / f"libxml2-{pins['libxml2_version']}/Copyright", "libxml2 license")
        return
    if kind == IOS_DOWNLOADS:
        downloads = workspace / "shared-data/build/ios-native-work/downloads"
        expected = download_inventory(pins)
        for name, digest in expected.items():
            _require_support_file(downloads / name, digest, "pinned download")
        allowed = {relative_to(workspace, downloads / name) for name in expected}
        allowed.add(manifest_relative(kind, platform))
        actual = {relative_to(workspace, path) for path in downloads.iterdir() if path.is_file() or path.is_symlink()}
        extras = actual - allowed
        if extras:
            raise CacheError(f"unexpected files in the iOS download cache: {sorted(extras)}")
        return
    raise CacheError(f"unknown cache kind: {kind}")


def _environment(kind: str) -> dict[str, str | None]:
    if kind == IOS_DOWNLOADS:
        # The archives are inputs, not build products; their script SHA and checksums are the key.
        return {}
    values: dict[str, str | None] = {}
    for name in ENVIRONMENT_INPUTS:
        # Compiler flags are opaque bytes, not paths: normpath would collapse -O0/-O3 and
        # relative include spellings into the same key.
        value = os.environ.get(name)
        values[name] = value if value not in (None, "") else None
    return values


def _resolve_tool(env_name: str, default: str) -> tuple[Path, list[str]]:
    value = os.environ.get(env_name)
    parts = shlex.split(value) if value else [default]
    executable = parts[0]
    resolved = executable if os.path.isabs(executable) else shutil.which(executable)
    if not resolved or not Path(resolved).is_file():
        raise CacheError(f"cannot identify {env_name or default}: {executable!r} is not an executable file")
    return Path(resolved), parts[1:]


def _tool_record(
    path: Path,
    args: tuple[str, ...] = (),
    *,
    wrapper: tuple[str, ...] = (),
) -> str:
    digest = sha256_file(path)
    wrapper_text = f" wrapper={' '.join(wrapper)}" if wrapper else ""
    if not args:
        return f"{path} sha256={digest} no-version{wrapper_text}"
    # A wrapper/flag override is part of the command after the executable, never before it.
    command = [str(path), *wrapper, *args]
    try:
        result = subprocess.run(
            command,
            check=False,
            text=True,
            capture_output=True,
            timeout=TOOL_TIMEOUT_SECONDS,
        )
    except (OSError, subprocess.SubprocessError) as error:
        raise CacheError(f"cannot run tool identity {command!r}: {error}") from error
    output = (result.stdout + result.stderr).strip()
    if result.returncode != 0:
        raise CacheError(f"tool identity {command!r} failed with exit {result.returncode}: {output}")
    version = output or f"exit={result.returncode} no-output"
    return f"{path} sha256={digest}{wrapper_text} {version}"


def _record_named(name: str, env_name: str, default: str, args: tuple[str, ...] = ("--version",)) -> str:
    path, wrapper = _resolve_tool(env_name, default)
    return _tool_record(path, args, wrapper=tuple(wrapper))

def tool_identity(kind: str, platform: str, arch: str) -> dict[str, str]:
    if kind == IOS_DOWNLOADS:
        return {}
    tools: dict[str, str] = {}
    uname_path, _ = _resolve_tool("", "uname")
    tools["uname"] = _tool_record(uname_path, ("-srm",))
    # The producer calls these literal PATH names (Windows make is /usr/bin/make); env overrides for
    # them do not change the recipe and must not produce a false cache identity.
    tools["make"] = _record_named("make", "", "/usr/bin/make" if platform == "windows" else "make")
    tools["pkg_config"] = _record_named("pkg_config", "", "pkg-config")
    tools["perl"] = _record_named("perl", "", "perl", ("-v",))
    tools["tar"] = _record_named("tar", "", "tar")
    tools["curl"] = _record_named("curl", "", "curl")
    tools["cc"] = _record_named("cc", "CC", "gcc" if platform == "windows" else "cc")
    tools["cxx"] = _record_named("cxx", "CXX", "g++" if platform == "windows" else "c++")
    tools["ld"] = _record_named("ld", "", "ld")
    tools["ar"] = _record_named("ar", "AR", "ar", ())
    tools["ranlib"] = _record_named("ranlib", "RANLIB", "ranlib", ())
    tools["nm"] = _record_named("nm", "NM", "nm", ())
    if platform == "windows":
        tools["objdump"] = _record_named("objdump", "", "objdump")
        tools["mingw32_make"] = _record_named("mingw32_make", "", "mingw32-make")
    if platform == "linux":
        tools["ldd"] = _record_named("ldd", "", "ldd")
        tools["readelf"] = _record_named("readelf", "", "readelf")
    return tools


def _check_tool_architecture(platform: str, arch: str, tools: dict[str, str]) -> None:
    uname = tools.get("uname")
    if not uname:
        # Test doubles and the download-only cache do not need a shell identity.
        return
    lower = uname.lower()
    expected = ("x86_64", "amd64", "x64") if arch == "x64" else ("arm64", "aarch64")
    if not any(token in lower for token in expected):
        raise CacheError(
            f"the cache helper is running as {uname!r}, not the requested {platform}/{arch} target; "
            "run it in the same Rosetta/MSYS2 shell as the producer"
        )



def collect_inputs(
    kind: str,
    platform: str,
    arch: str,
    workspace: Path,
    tools: dict[str, str] | None = None,
    environment: dict[str, str | None] | None = None,
) -> dict[str, object]:
    validate_cache_topology(workspace, kind, platform)
    relative, producer_sha, _ = producer(workspace, kind)
    tools = tools if tools is not None else tool_identity(kind, platform, arch)
    _check_tool_architecture(platform, arch, tools)
    inputs: dict[str, object] = {
        "cache_schema": CACHE_SCHEMA,
        "helper_sha256": sha256_file(Path(__file__).resolve()),
        "helper_runtime": {
            "python": sys.version.split()[0],
            "executable": normalized(sys.executable),
            "os_name": os.name,
        },
        "kind": kind,
        "platform": platform,
        "target_arch": arch,
        "workspace": normalized(workspace),
        "producer": {"path": relative, "sha256": producer_sha},
        "tools": tools,
        "environment": environment if environment is not None else _environment(kind),
    }
    return inputs


def compute_key(inputs: dict[str, object]) -> str:
    prefixes = {
        DESKTOP: "native-deps",
        IOS_DOWNLOADS: "ios-native-downloads",
    }
    digest = sha256_text(canonical_json(inputs))[:40]
    return f"{prefixes[str(inputs['kind'])]}-v{CACHE_SCHEMA}-{inputs['platform']}-{inputs['target_arch']}-{digest}"


def write_manifest(workspace: Path, kind: str, platform: str, arch: str, inputs: dict[str, object]) -> Path:
    validate_expected_inventory(workspace, kind, platform, arch)
    validate_architecture(workspace, kind, platform, arch)
    entries = collect_entries(workspace, kind, platform)
    manifest_path = workspace / manifest_relative(kind, platform)
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest = {
        "cache_schema": CACHE_SCHEMA,
        "kind": kind,
        "platform": platform,
        "target_arch": arch,
        "workspace": normalized(workspace),
        "key": compute_key(inputs),
        "inputs": inputs,
        "files": entries,
    }
    temporary = manifest_path.with_suffix(manifest_path.suffix + ".tmp")
    try:
        temporary.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        temporary.replace(manifest_path)
    except OSError:
        temporary.unlink(missing_ok=True)
        raise
    return manifest_path


def verify_manifest(workspace: Path, kind: str, platform: str, arch: str) -> None:
    inputs = collect_inputs(kind, platform, arch, workspace)
    manifest_path = workspace / manifest_relative(kind, platform)
    if not manifest_path.is_file():
        raise CacheError(f"cache manifest is missing: {manifest_path}")
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise CacheError(f"cache manifest is unreadable: {error}") from error
    if not isinstance(manifest, dict):
        raise CacheError("cache manifest is not a JSON object")
    if manifest.get("cache_schema") != CACHE_SCHEMA:
        raise CacheError("cache manifest schema does not match this helper")
    if manifest.get("kind") != kind or manifest.get("platform") != platform:
        raise CacheError("cache manifest belongs to another cache kind or platform")
    expected_key = compute_key(inputs)
    if manifest.get("key") != expected_key:
        raise CacheError("cache key does not match the current input identity")
    if canonical_json(manifest.get("inputs")) != canonical_json(inputs):
        raise CacheError("cache input identity changed; refusing to trust the restored tree")
    if normalized(manifest.get("workspace")) != normalized(workspace):
        raise CacheError("cache was produced in a different checkout path")
    validate_expected_inventory(workspace, kind, platform, arch)
    validate_architecture(workspace, kind, platform, arch)
    actual = collect_entries(workspace, kind, platform)
    declared = manifest.get("files")
    if not isinstance(declared, list):
        raise CacheError("cache manifest has no file inventory")
    actual_by_path = {str(entry["path"]): entry for entry in actual}
    declared_by_path = {str(entry.get("path")): entry for entry in declared}
    if set(actual_by_path) != set(declared_by_path):
        missing = sorted(set(declared_by_path) - set(actual_by_path))
        extra = sorted(set(actual_by_path) - set(declared_by_path))
        raise CacheError(f"cache inventory differs; missing={missing[:8]} extra={extra[:8]}")
    for path, entry in actual_by_path.items():
        if canonical_json(entry) != canonical_json(declared_by_path[path]):
            raise CacheError(f"cache content differs from its manifest: {path}")


def _scoped(workspace: Path, path: Path, roots: list[Path]) -> Path:
    workspace_lexical = Path(os.path.abspath(workspace))
    workspace_real = workspace_lexical.resolve()
    safe_roots = []
    for root in roots:
        _safe_generated_path(workspace, root)
        lexical_root = Path(os.path.abspath(root))
        safe_roots.append((lexical_root, lexical_root.resolve()))
    lexical = Path(os.path.abspath(path))
    if not _within(lexical, workspace_lexical):
        raise CacheError(f"refusing to remove a path outside the checkout: {path}")
    if not any(_within(lexical, root_lexical) for root_lexical, _ in safe_roots):
        raise CacheError(f"refusing to remove an unlisted path: {path}")
    # Every parent must stay inside the checkout; below the approved root it must stay inside that
    # root too. The final component may be a symlink and is unlinked lexically, which never follows it.
    parent = lexical.parent
    current = workspace_lexical
    for part in parent.relative_to(workspace_lexical).parts:
        current = current / part
        resolved = current.resolve()
        if not _within(resolved, workspace_real):
            raise CacheError(f"refusing to follow a symlink outside the checkout: {path}")
        for root_lexical, root_real in safe_roots:
            if _within(current, root_lexical) and not _within(resolved, root_real):
                raise CacheError(f"refusing to follow a symlink outside the generated root: {path}")
    if lexical.is_symlink():
        return lexical
    if not any(_within(lexical.resolve(), root_real) for _, root_real in safe_roots):
        raise CacheError(f"refusing to follow a symlink outside the generated root: {path}")
    return lexical


def _remove_safe_manifest(workspace: Path, kind: str, platform: str) -> None:
    manifest = workspace / manifest_relative(kind, platform)
    try:
        _safe_generated_path(workspace, manifest)
    except CacheError:
        return
    for path in (manifest, manifest.with_suffix(manifest.suffix + ".tmp")):
        if path.is_symlink() or path.is_file():
            path.unlink(missing_ok=True)


def _remove(path: Path) -> None:
    if path.is_symlink() or path.is_file():
        path.unlink()
    elif path.is_dir():
        shutil.rmtree(path)


def invalidate(workspace: Path, kind: str, platform: str) -> list[str]:
    if kind == DESKTOP:
        roots = [workspace / "desktopApp/build/native"]
        targets = [
            roots[0] / "prefix",
            roots[0] / "openssl-static",
            roots[0] / "src",
            roots[0] / ".native-deps-cache.json",
        ]
    elif kind == IOS_DOWNLOADS:
        roots = [workspace / "shared-data/build/ios-native-work"]
        targets = [roots[0] / "downloads"]
    else:
        raise CacheError(f"unknown cache kind: {kind}")
    removed: list[str] = []
    for target in targets:
        scoped = _scoped(workspace, target, roots)
        if scoped.exists() or scoped.is_symlink():
            _remove(scoped)
            removed.append(relative_to(workspace, scoped))
    return removed


def parse_arguments(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("key", "manifest", "verify", "invalidate"))
    parser.add_argument("--kind", required=True, choices=KINDS)
    parser.add_argument("--platform", required=True)
    parser.add_argument("--target-arch", required=True)
    parser.add_argument("--workspace", default=os.getcwd())
    parser.add_argument(
        "--optional",
        action="store_true",
        help="Disable caching instead of failing the job when the identity or probe is unsupported.",
    )
    arguments = parser.parse_args(argv)
    valid_platforms = {
        DESKTOP: {"linux", "windows"},
        IOS_DOWNLOADS: {"ios"},
    }
    if arguments.platform not in valid_platforms[arguments.kind]:
        parser.error(f"platform {arguments.platform!r} is not valid for {arguments.kind}")
    valid_arches = {
        DESKTOP: {"x64", "arm64"},
        IOS_DOWNLOADS: {"universal"},
    }
    if arguments.target_arch not in valid_arches[arguments.kind]:
        parser.error(f"target arch {arguments.target_arch!r} is not valid for {arguments.kind}")
    if arguments.platform == "windows" and arguments.target_arch != "x64" and arguments.kind != IOS_DOWNLOADS:
        parser.error("the Windows recipes cover x64 only")
    return arguments


def main(argv: list[str] | None = None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        # The Windows runner may use native Python; GitHub's output parser expects LF, not CRLF.
        sys.stdout.reconfigure(newline="\n")
    arguments = parse_arguments(argv)
    workspace = workspace_root(arguments.workspace)
    try:
        if arguments.command == "key":
            inputs = collect_inputs(arguments.kind, arguments.platform, arguments.target_arch, workspace)
            key = compute_key(inputs)
            paths = cache_relative_paths(arguments.kind, arguments.platform, workspace)
            print(f"key={key}")
            print(f"paths<<{OUTPUT_DELIMITER}")
            for relative in paths:
                print(relative)
            print(OUTPUT_DELIMITER)
        elif arguments.command == "manifest":
            inputs = collect_inputs(arguments.kind, arguments.platform, arguments.target_arch, workspace)
            manifest = write_manifest(
                workspace, arguments.kind, arguments.platform, arguments.target_arch, inputs
            )
            print(f"manifest={relative_to(workspace, manifest)}")
        elif arguments.command == "verify":
            verify_manifest(workspace, arguments.kind, arguments.platform, arguments.target_arch)
            print(f"verified {arguments.kind} cache for {workspace}")
        else:
            removed = invalidate(workspace, arguments.kind, arguments.platform)
            print("removed " + (", ".join(removed) if removed else "nothing"))
    except (CacheError, OSError, ValueError) as error:
        if arguments.optional and arguments.command in ("key", "manifest"):
            print(f"warning: native cache disabled: {error}", file=sys.stderr)
            if arguments.command == "key":
                print("key=")
                print(f"paths<<{OUTPUT_DELIMITER}")
                print(OUTPUT_DELIMITER)
            else:
                _remove_safe_manifest(workspace, arguments.kind, arguments.platform)
            return 0
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
