#!/usr/bin/env python3
"""Bounded relocated bundle smoke. Run under run-heavy.sh and a private Xvfb display."""
import ctypes as c
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import time
import wave


def engine_smoke(library, fixture):
    class Config(c.Structure):
        _fields_ = [(name, c.c_uint32) for name in
                    ("abi", "output", "backend", "rate", "ring_ms", "prebuffer_ms")]

    engine = c.CDLL(str(library))
    engine.ae_engine_create.argtypes = [c.POINTER(Config), c.c_char_p, c.c_size_t]
    engine.ae_engine_create.restype = c.c_void_p
    engine.ae_engine_destroy.argtypes = [c.c_void_p]
    engine.ae_engine_load.argtypes = [c.c_void_p, c.c_char_p, c.c_uint64, c.c_int]
    engine.ae_engine_load.restype = c.c_uint64
    engine.ae_engine_render.argtypes = [c.c_void_p, c.POINTER(c.c_float), c.c_uint32]
    engine.ae_engine_render.restype = c.c_uint32
    error = c.create_string_buffer(1024)
    handle = engine.ae_engine_create(c.byref(Config(1, 0, 1, 48000, 2000, 100)), error, len(error))
    if not handle:
        raise RuntimeError(error.value.decode())
    try:
        assert engine.ae_engine_load(handle, os.fsencode(fixture), 0, 1) > 0
        output = (c.c_float * 9600)()
        assert engine.ae_engine_render(handle, output, 4800) == 4800
        assert any(abs(value) > 0.001 for value in output), "Real packaged decoder produced no fixture samples"
    finally:
        engine.ae_engine_destroy(handle)
    print("Relocated engine: real WAV decode/render, no LD_LIBRARY_PATH or build-prefix lookup")


def windows(display):
    x = c.CDLL("libX11.so.6")
    window = c.c_ulong
    pointer = c.c_void_p
    x.XDefaultRootWindow.argtypes = [pointer]
    x.XDefaultRootWindow.restype = window
    x.XQueryTree.argtypes = [pointer, window, c.POINTER(window), c.POINTER(window),
                            c.POINTER(c.POINTER(window)), c.POINTER(c.c_uint)]
    x.XFetchName.argtypes = [pointer, window, c.POINTER(pointer)]
    x.XFree.argtypes = [pointer]

    def visit(node, depth=0):
        if depth > 16:
            return []
        name = pointer()
        found = []
        if x.XFetchName(display, node, c.byref(name)) and name.value:
            try:
                if c.string_at(name.value) == b"ArchiveTune":
                    found.append(node)
            finally:
                x.XFree(name)
        root, parent, count = window(), window(), c.c_uint()
        children = c.POINTER(window)()
        if x.XQueryTree(display, node, c.byref(root), c.byref(parent), c.byref(children), c.byref(count)):
            try:
                for index in range(min(count.value, 4096)):
                    found.extend(visit(children[index], depth + 1))
            finally:
                if children:
                    x.XFree(children)
        return found
    return visit(x.XDefaultRootWindow(display))


def main():
    root = Path(__file__).resolve().parents[1]
    source = root / "build/compose/binaries/main/app/ArchiveTune"
    with tempfile.TemporaryDirectory(prefix="bundle-smoke-", dir=root / "build") as temporary:
        temporary = Path(temporary)
        bundle = temporary / "relocated/ArchiveTune"
        shutil.copytree(source, bundle)
        resources = bundle / "lib/app/resources"
        assert (resources / "notices/FFmpeg-LGPL-3.0.txt").is_file()
        fixture = temporary / "fixture.wav"
        with wave.open(str(fixture), "wb") as output:
            output.setparams((1, 2, 48000, 0, "NONE", "not compressed"))
            output.writeframes(b"".join(struct.pack("<h", (i % 400) * 40 - 8000) for i in range(9600)))
        clean = dict(os.environ)
        clean.pop("LD_LIBRARY_PATH", None)
        subprocess.run([sys.executable, str(Path(__file__).resolve()), "--engine",
                        str(resources / "native/libarchivetune_engine.so"), str(fixture)],
                       env=clean, check=True, timeout=20)
        x = c.CDLL("libX11.so.6")
        x.XOpenDisplay.argtypes = [c.c_char_p]
        x.XOpenDisplay.restype = c.c_void_p
        x.XCloseDisplay.argtypes = [c.c_void_p]
        display = x.XOpenDisplay(None)
        assert display, "Run on a private Xvfb display"
        environment = {**os.environ, "JAVA_TOOL_OPTIONS": os.environ.get("JAVA_TOOL_OPTIONS", "") +
                       f" -Darchivetune.instance.directory={temporary / 'instance'}"}
        executable = str(bundle / "bin/ArchiveTune")
        # Exercise the real per-user installer without touching the test user's actual associations.
        registration = {**environment, "HOME": str(temporary / "home"),
                        "XDG_DATA_HOME": str(temporary / "data"), "XDG_CONFIG_HOME": str(temporary / "config")}
        (temporary / "home").mkdir()
        (temporary / "config").mkdir()
        subprocess.run(["bash", str(resources / "integration/install-uri-handler.sh"), executable],
                       env=registration, check=True, timeout=10)
        assert (temporary / "data/applications/archivetune.desktop").is_file()
        assert (temporary / "data/icons/hicolor/256x256/apps/archivetune.png").is_file()
        associated = subprocess.run(["xdg-mime", "query", "default", "x-scheme-handler/archivetune"],
                                    env=registration, check=True, capture_output=True, text=True, timeout=10)
        assert associated.stdout.strip() == "archivetune.desktop"
        print("Portable URI installer: branded per-user desktop entry and xdg-mime association in isolated XDG directories")
        try:
            with (temporary / "primary.log").open("w+") as log:
                primary = subprocess.Popen([executable], cwd=temporary, env=environment, stdout=log, stderr=log)
                try:
                    deadline = time.monotonic() + 25
                    while not windows(display) and primary.poll() is None and time.monotonic() < deadline:
                        time.sleep(0.1)
                    assert primary.poll() is None, "Packaged primary exited"
                    assert len(windows(display)) == 1, "Expected one real packaged AWT/Compose window"
                    subprocess.run([executable, "archivetune://open/library"], cwd=temporary,
                                   env=environment, check=True, timeout=10)
                    assert primary.poll() is None
                    assert len(windows(display)) == 1, "Secondary launch created another window"
                    print("Relocated launcher: real Compose window; authenticated secondary URI delivery exits without another window")
                except BaseException:
                    log.flush()
                    log.seek(0)
                    print(log.read(8000), file=sys.stderr)
                    raise
                finally:
                    primary.terminate()
                    try:
                        primary.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        primary.kill()
                        primary.wait(timeout=5)
        finally:
            x.XCloseDisplay(display)


if __name__ == "__main__":
    if len(sys.argv) == 4 and sys.argv[1] == "--engine":
        engine_smoke(Path(sys.argv[2]), Path(sys.argv[3]))
    else:
        main()
