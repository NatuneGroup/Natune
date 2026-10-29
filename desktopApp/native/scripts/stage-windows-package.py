#!/usr/bin/env python3
"""Stage this worktree's exact media closure for Windows x64.

Walks the PE import table of every staged DLL and copies the ones this build produced from the
dependency prefix. Windows system libraries are an allowlist that is never bundled; anything else
that is neither staged nor a known system DLL fails the stage instead of shipping.
"""
import os
import pathlib
import re
import shutil
import subprocess
import sys

root = pathlib.Path(__file__).resolve().parents[2]
build = root / "build"
prefix = build / "native/prefix"
engine = build / "native/engine-build"
destination = build / "distribution-resources/windows-x64/native"
destination.mkdir(parents=True, exist_ok=True)
subprocess.run(["cmake", "--install", str(engine), "--prefix", str(destination)], check=True)

# Windows never ships these: they are the OS. Anything else not found in the prefix fails the audit.
SYSTEM_DLLS = {
    "kernel32.dll", "user32.dll", "advapi32.dll", "msvcrt.dll", "ws2_32.dll", "wsock32.dll",
    "bcrypt.dll", "ole32.dll", "oleaut32.dll", "gdi32.dll", "shell32.dll", "crypt32.dll",
    "imm32.dll", "setupapi.dll", "winmm.dll", "version.dll", "dbghelp.dll", "comdlg32.dll",
    "shlwapi.dll", "rpcrt4.dll", "ntdll.dll", "sechost.dll", "iphlpapi.dll", "dwmapi.dll",
    "uxtheme.dll", "comctl32.dll", "netapi32.dll", "dnsapi.dll", "secur32.dll", "userenv.dll",
    "avrt.dll", "winhttp.dll", "wininet.dll", "normaliz.dll", "wldap32.dll", "mpr.dll",
    "kernel.appcore.dll", "mswsock.dll", "powrprof.dll", "cfgmgr32.dll", "propsys.dll",
}
# These resolve at run time from the Windows directory, never from the package.
SYSTEM_RE = re.compile(r"^(api-ms-win-|api-ms-onecore-|ext-ms-|ucrtbase|vcruntime|msvcp)", re.IGNORECASE)


def imports(library):
    output = subprocess.run(["objdump", "-p", str(library)], check=True, text=True,
                            capture_output=True).stdout
    return re.findall(r"DLL Name:\s*(\S+)", output, flags=re.IGNORECASE)


prefix_files = {p.name.lower(): p for p in prefix.glob("**/*.dll") if p.is_file()}
# The MinGW toolchain's own runtime is not in the prefix. Only these names are taken from its bin directory, so an
# unexpected toolchain DLL still fails the audit instead of shipping.
MINGW_RUNTIME = {"libwinpthread-1.dll", "libgcc_s_seh-1.dll", "libstdc++-6.dll"}
mingw_bin = os.environ.get("ARCHIVETUNE_MINGW_BIN")
if mingw_bin:
    for name in MINGW_RUNTIME:
        candidate = pathlib.Path(mingw_bin) / name
        if candidate.is_file():
            prefix_files.setdefault(name, candidate)
staged = set()
queue = [p for p in destination.iterdir() if p.name.lower().endswith(".dll")]
while queue:
    library = queue.pop()
    for dep in imports(library):
        key = dep.lower()
        if key in staged or key in SYSTEM_DLLS or SYSTEM_RE.match(key):
            continue
        source = prefix_files.get(key)
        if source is None:
            raise RuntimeError(f"Unstaged dependency {dep} required by {library.name}")
        target = destination / source.name
        shutil.copyfile(source, target)
        staged.add(key)
        queue.append(target)

if not any(name.startswith("avformat") for name in staged):
    raise RuntimeError("Missing FFmpeg closure; build native dependencies first")
for library in sorted(destination.iterdir()):
    if library.name.lower().endswith(".dll"):
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
