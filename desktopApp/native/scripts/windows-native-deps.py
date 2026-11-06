#!/usr/bin/env python3
"""Verify the staged Windows native closure and write the load order the engine loader needs.

The playback engine is a DLL whose FFmpeg, OpenSSL, libxml2 and zlib dependencies sit beside it in
`resources/native`. Windows never searches a loaded library's own directory: HotSpot loads a JNI
library with plain `LoadLibrary`, whose search path is the executable's directory, System32, the
Windows directory, the current directory and PATH. ELF has `$ORIGIN` and Mach-O has
`@loader_path`; the PE format has no equivalent, so the loader preloads the closure itself and needs
the dependency order. This script derives that order from the real PE import tables and fails when a
staged DLL imports something that is neither staged nor an operating-system library.

Usage:
    python3 windows-native-deps.py <native-directory> [--root archivetune_engine.dll] [--check]

Without `--check` it writes `native-dependencies.txt` next to the DLLs. With `--check` it verifies
the directory and the manifest that is already there, and never writes.

The module is imported by stage-windows-package.py so the allowlist and the import walk exist once.
"""
import argparse
import pathlib
import re
import struct
import sys

MANIFEST_NAME = "native-dependencies.txt"
ROOT_LIBRARY = "archivetune_engine.dll"
# PE32+ machine for x64. A 32-bit or ARM DLL in the package is a packaging defect, not a variant.
MACHINE_AMD64 = 0x8664
# Imports resolved by the Windows loader from the system, never from the package.
SYSTEM_DLLS = {
    "advapi32.dll", "avrt.dll", "bcrypt.dll", "cabinet.dll", "cfgmgr32.dll", "comctl32.dll",
    "comdlg32.dll", "credui.dll", "crypt32.dll", "cryptbase.dll", "d3d11.dll", "d3d12.dll",
    "d3d9.dll", "d3dcompiler_47.dll", "dbghelp.dll", "dcomp.dll", "dnsapi.dll", "dsound.dll",
    "dwmapi.dll", "dwrite.dll", "dxgi.dll", "gdi32.dll", "gdiplus.dll", "imm32.dll",
    "iphlpapi.dll", "kernel32.dll", "kernel.appcore.dll", "mf.dll", "mfplat.dll", "mfuuid.dll",
    "mpr.dll", "msimg32.dll", "mswsock.dll", "msvcrt.dll", "ncrypt.dll", "netapi32.dll",
    "normaliz.dll", "ntdll.dll", "ole32.dll", "oleacc.dll", "oleaut32.dll", "opengl32.dll",
    "powrprof.dll", "propsys.dll", "psapi.dll", "rpcrt4.dll", "sechost.dll", "secur32.dll",
    "setupapi.dll", "shell32.dll", "shlwapi.dll", "sspicli.dll", "uiautomationcore.dll",
    "user32.dll", "userenv.dll", "uxtheme.dll", "version.dll", "webio.dll", "winhttp.dll",
    "wininet.dll", "winmm.dll", "wintrust.dll", "wldap32.dll", "ws2_32.dll", "wsock32.dll",
}
# API sets and CRT redistributables resolve from the Windows directory, never from the package.
SYSTEM_RE = re.compile(r"^(api-ms-win-|api-ms-onecore-|ext-ms-|ucrtbase|vcruntime|msvcp)", re.IGNORECASE)


def is_system(name):
    """True when Windows supplies this import itself."""
    key = name.lower()
    return key in SYSTEM_DLLS or SYSTEM_RE.match(key) is not None


def _rva_to_offset(sections, rva):
    for _name, virtual_address, virtual_size, raw_address, raw_size in sections:
        if virtual_address <= rva < virtual_address + max(virtual_size, raw_size):
            return rva - virtual_address + raw_address
    return None


def _cstring(data, offset, limit=512):
    end = data.find(b"\x00", offset, offset + limit)
    return data[offset:end if end >= 0 else offset + limit].decode("latin-1", "replace")


def read_pe(path):
    """Parses just enough of a PE file to read its imports and delay imports."""
    data = pathlib.Path(path).read_bytes()
    if data[:2] != b"MZ":
        raise ValueError(f"not a PE file: {path}")
    pe_offset = struct.unpack_from("<I", data, 0x3C)[0]
    if data[pe_offset:pe_offset + 4] != b"PE\x00\x00":
        raise ValueError(f"missing PE signature: {path}")
    coff = pe_offset + 4
    machine, section_count, _timestamp, _symbols, _symbol_count, optional_size, _flags = \
        struct.unpack_from("<HHIIIHH", data, coff)
    optional = coff + 20
    magic = struct.unpack_from("<H", data, optional)[0]
    if magic not in (0x10B, 0x20B):
        raise ValueError(f"unknown optional header magic in {path}")
    thunk_size = 8 if magic == 0x20B else 4
    directory_offset = optional + (112 if magic == 0x20B else 96)
    directory_count = struct.unpack_from("<I", data, directory_offset - 4)[0]
    directories = [
        struct.unpack_from("<II", data, directory_offset + index * 8)
        for index in range(min(directory_count, 16))
    ]
    sections = []
    for index in range(section_count):
        base = optional + optional_size + index * 40
        virtual_size, virtual_address, raw_size, raw_address = struct.unpack_from("<IIII", data, base + 8)
        sections.append((data[base:base + 8].rstrip(b"\x00").decode("latin-1"),
                         virtual_address, virtual_size, raw_address, raw_size))
    return {"data": data, "machine": machine, "pe64": magic == 0x20B,
            "directories": directories, "sections": sections}


def imports(path):
    """Returns (machine, normal imports, delay imports); each maps lower-case DLL name to symbol names."""
    pe = read_pe(path)
    data = pe["data"]
    found = ({}, {})
    for index, target in ((1, found[0]), (13, found[1])):
        if index >= len(pe["directories"]):
            continue
        table_rva, table_size = pe["directories"][index]
        if not table_rva:
            continue
        table = _rva_to_offset(pe["sections"], table_rva)
        if table is None:
            continue
        descriptor = 0
        while descriptor < 4096:
            base = table + descriptor * 20
            if len(data) < base + 20:
                break
            thunk_rva, _timestamp, _forwarder, name_rva, first_thunk_rva = struct.unpack_from("<IIIII", data, base)
            descriptor += 1
            if name_rva == 0:
                break
            name_offset = _rva_to_offset(pe["sections"], name_rva)
            if name_offset is None:
                continue
            library = _cstring(data, name_offset).lower()
            symbols = target.setdefault(library, [])
            symbols.extend(_thunk_symbols(pe, thunk_rva or first_thunk_rva))
    return pe["machine"], found[0], found[1]


def _thunk_symbols(pe, thunk_rva):
    data = pe["data"]
    if not thunk_rva:
        return []
    offset = _rva_to_offset(pe["sections"], thunk_rva)
    if offset is None:
        return []
    size = 8 if pe["pe64"] else 4
    ordinal_bit = 1 << (63 if pe["pe64"] else 31)
    mask = 0x7FFFFFFF
    symbols = []
    for index in range(20000):
        position = offset + index * size
        if len(data) < position + size:
            break
        value = struct.unpack_from("<Q" if size == 8 else "<I", data, position)[0]
        if value == 0:
            break
        if value & ordinal_bit:
            symbols.append(f"ordinal#{value & 0xFFFF}")
            continue
        name_offset = _rva_to_offset(pe["sections"], value & mask)
        if name_offset is None:
            break
        symbols.append(_cstring(data, name_offset + 2))
    return symbols


def staged_libraries(directory):
    """Maps lower-case name to path for every DLL directly inside the directory."""
    directory = pathlib.Path(directory)
    return {path.name.lower(): path for path in directory.iterdir()
            if path.is_file() and path.name.lower().endswith(".dll")}


def audit(directory):
    """Returns (problems, dependency graph, machine problems) for the DLLs in the directory."""
    directory = pathlib.Path(directory)
    if not directory.is_dir():
        return [f"{directory} is not a directory"], {}, []
    libraries = staged_libraries(directory)
    problems = []
    graph = {}
    machine_problems = []
    for name, path in sorted(libraries.items()):
        try:
            machine, direct, delayed = imports(path)
        except (ValueError, struct.error) as error:
            problems.append(f"{name}: unreadable PE import table ({error})")
            continue
        if machine != MACHINE_AMD64:
            machine_problems.append(f"{name}: machine 0x{machine:04x} is not x86-64")
        required = list(direct) + [name for name in delayed if name not in direct]
        graph[name] = required
        for dependency in required:
            if dependency in libraries or is_system(dependency):
                continue
            problems.append(f"{name} imports {dependency}, which is neither staged nor a system library")
    return problems, graph, machine_problems


def staged_graph(graph):
    """The dependency graph restricted to staged libraries; system imports need no load order."""
    return {name: [dependency for dependency in dependencies if dependency in graph]
            for name, dependencies in graph.items()}


def load_order(graph, root):
    """Dependency-first order of everything the root transitively imports, excluding the root itself."""
    root = root.lower()
    graph = staged_graph(graph)
    ordered = []
    placed = set()
    visiting = set()

    def visit(name):
        if name in placed or name in visiting:
            return
        visiting.add(name)
        for dependency in graph.get(name, ()):
            if dependency in graph:
                visit(dependency)
        visiting.discard(name)
        placed.add(name)
        if name != root:
            ordered.append(name)

    visit(root)
    return ordered


def manifest_text(graph, root):
    """The manifest body: every closure member in load order, then the root, each with its staged imports."""
    root = root.lower()
    graph = staged_graph(graph)
    names = load_order(graph, root)
    if root not in graph:
        raise ValueError(f"{root} is missing from the staged closure")
    names.append(root)
    lines = [
        "# Natune desktop native dependency order, written by desktopApp/native/scripts/windows-native-deps.py.",
        "# Windows has no rpath, so the playback engine loader preloads each line before the next one.",
        "# One entry per line: <library>=<comma-separated imports>.",
    ]
    lines.extend(f"{name}={','.join(graph.get(name, ()))}" for name in names)
    return "\n".join(lines) + "\n"


def problems_with_manifest(directory, graph, root):
    """Checks that a written manifest still describes the staged closure."""
    directory = pathlib.Path(directory)
    manifest = directory / MANIFEST_NAME
    if not manifest.is_file():
        return [f"{manifest} is missing; the engine loader has no dependency order to follow"]
    parsed = {}
    for line in manifest.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            return [f"{manifest} has an unreadable line: {line}"]
        name, _, dependencies = line.partition("=")
        parsed[name.strip().lower()] = [item.strip().lower() for item in dependencies.split(",") if item.strip()]
    staged = staged_graph(graph)
    problems = []
    for name, declared in parsed.items():
        if name not in staged:
            problems.append(f"{MANIFEST_NAME} lists {name}, which is not staged")
        elif sorted(declared) != sorted(staged[name]):
            problems.append(f"{MANIFEST_NAME} lists different imports for {name} than the DLL has")
    expected = set(load_order(graph, root)) | {root.lower()}
    for name in sorted(expected - set(parsed)):
        problems.append(f"{MANIFEST_NAME} is missing {name}, which the engine imports")
    order = [name for name in parsed]
    position = {name: index for index, name in enumerate(order)}
    for name, dependencies in parsed.items():
        for dependency in dependencies:
            if dependency in position and position[dependency] > position.get(name, -1):
                problems.append(f"{MANIFEST_NAME} loads {name} before its dependency {dependency}")
    return problems


def closure_problems(directory, root=ROOT_LIBRARY):
    """Everything wrong with the staged closure: unreadable DLLs, wrong machine, unstaged imports."""
    problems, graph, machine_problems = audit(directory)
    problems.extend(machine_problems)
    root = root.lower()
    if root not in graph:
        problems.append(f"{root} is not in {directory}")
        return problems, graph
    # Every closure member must be a staged file: the loader can only preload what exists.
    for name in load_order(graph, root):
        if name not in graph:
            problems.append(f"{name} is needed by the closure but is not in {directory}")
    return problems, graph


def write_manifest(directory, root=ROOT_LIBRARY):
    """Audits the directory and writes the manifest. Raises ValueError naming every problem."""
    problems, graph = closure_problems(directory, root)
    if problems:
        raise ValueError("; ".join(problems))
    directory = pathlib.Path(directory)
    (directory / MANIFEST_NAME).write_text(manifest_text(graph, root), encoding="utf-8")
    return load_order(graph, root)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("directory", help="the staged native directory that holds archivetune_engine.dll")
    parser.add_argument("--root", default=ROOT_LIBRARY, help=f"library whose closure is written (default {ROOT_LIBRARY})")
    parser.add_argument("--check", action="store_true", help="verify the directory and its existing manifest; write nothing")
    arguments = parser.parse_args(argv)
    directory = pathlib.Path(arguments.directory)
    root = arguments.root.lower()

    problems, graph = closure_problems(directory, root)
    if arguments.check and not problems:
        problems = problems_with_manifest(directory, graph, root)
    if problems:
        for problem in problems:
            print(f"[native-deps] {problem}", file=sys.stderr)
        print(f"[native-deps] the staged Windows closure is incomplete ({len(problems)} problems)", file=sys.stderr)
        return 1
    if not arguments.check:
        order = write_manifest(directory, root)
        print(f"Wrote {directory / MANIFEST_NAME} ({len(order)} libraries in load order)", file=sys.stderr)
    for name in load_order(graph, root):
        print(f"[native-deps] staged and resolvable: {name}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
