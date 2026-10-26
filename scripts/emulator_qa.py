#!/usr/bin/env python3
"""Drive the QA emulator: launch the app, walk onboarding/home/settings,
select every player design, capture screenshots and logcat.

Every UI action is best-effort: misses are logged, never fatal, so the
job always reaches the artifact-upload phase.
"""

import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

SDK = os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"]
ADB = f"{SDK}/platform-tools/adb"
PKG = "moe.rukamori.archivetune.debug"
OUT = Path("qa-artifacts")
OUT.mkdir(exist_ok=True)

STYLES = [
    "V1", "V2", "V3", "V4", "V5", "V6", "V7", "V8", "V9", "V10",
    "Apple Music", "Bitchord", "TikTok", "SimpMusic", "SpatialFlow",
    "Looper", "Flamingo", "Metrolist KMP",
]


def adb(*args: str, check: bool = False) -> subprocess.CompletedProcess:
    return subprocess.run(
        [ADB, *args], capture_output=True, text=True, check=check
    )


def shot(name: str) -> None:
    data = subprocess.run(
        [ADB, "exec-out", "screencap", "-p"], capture_output=True
    ).stdout
    (OUT / f"{name}.png").write_bytes(data)
    print(f"shot {name} ({len(data)} bytes)", flush=True)


def dump_ui(tag: str) -> list:
    """Return list of (text, desc, resid, bounds) tuples for enabled nodes."""
    r = adb("shell", "uiautomator", "dump", "/sdcard/uiauto.xml")
    if r.returncode != 0:
        print(f"uiautomator dump failed: {r.stdout.strip()} {r.stderr.strip()}")
        return []
    r = adb("pull", "/sdcard/uiauto.xml", str(OUT / f"uiauto-{tag}.xml"))
    path = OUT / f"uiauto-{tag}.xml"
    if r.returncode != 0 or not path.exists():
        print(f"uiautomator pull failed: {r.stdout.strip()} {r.stderr.strip()}")
        return []
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as e:
        print(f"uiauto xml parse error: {e}")
        return []
    nodes = []
    for n in root.iter("node"):
        if n.get("enabled") == "false":
            continue
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds") or "")
        if not m:
            continue
        cx = (int(m[1]) + int(m[3])) // 2
        cy = (int(m[2]) + int(m[4])) // 2
        nodes.append((n.get("text") or "", n.get("content-desc") or "",
                      n.get("resource-id") or "", cx, cy))
    print(f"uiauto-{tag}: {len(nodes)} nodes")
    if not nodes:
        print(path.read_text()[:2000])
    return nodes


def tap_text(pattern: str, tag: str) -> bool:
    pat = re.compile(pattern, re.I)
    for text, desc, resid, cx, cy in dump_ui(tag):
        hay = " ".join(x for x in (text, desc, resid) if x)
        if pat.search(hay):
            print(f"tap '{pattern}' -> ({cx},{cy}) [{text or desc or resid}]")
            adb("shell", "input", "tap", str(cx), str(cy))
            time.sleep(3)
            return True
    print(f"no node matching '{pattern}'")
    return False


def install_and_launch() -> None:
    apks = sorted(Path("app/build/outputs/apk").rglob("*.apk"))
    print(f"apk: {apks[0]}")
    print(adb("install", "-r", str(apks[0])).stdout.strip())
    adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1")
    time.sleep(15)


def walk_onboarding() -> None:
    shot("01-onboarding")
    for i in range(1, 8):
        if tap_text("start listening|onboarding_finish|get started",
                    f"onb-{i}"):
            time.sleep(5)
            shot("02-onboarding-done")
            return
        tap_text("^next$|^continue$|^skip$|onboarding_next|forward|navigate next",
                 f"onb-{i}-next")
    shot("02-onboarding-end")


def navigate() -> None:
    time.sleep(5)
    shot("03-home")
    tap_text("^search$|nav.*search", "nav-search")
    time.sleep(3)
    shot("04-search")
    adb("shell", "input", "keyevent", "4")
    time.sleep(2)
    tap_text("^library$|nav.*library", "nav-library")
    time.sleep(3)
    shot("05-library")
    tap_text("^settings$|settings.*icon|more options|^account$", "nav-settings")
    time.sleep(3)
    shot("06-settings")
    tap_text("^appearance$|^interface$", "nav-appearance")
    time.sleep(3)
    shot("07-appearance")


def select_styles() -> None:
    results = []
    for i, style in enumerate(STYLES, 1):
        tap_text("^player design$|^player style$", f"style-open-{i}")
        time.sleep(1)
        ok = tap_text(f"^{re.escape(style)}$", f"style-{i}")
        shot(f"style-{i:02d}-{style.replace(' ', '_')}")
        results.append(f"{'selected' if ok else 'NOT SELECTABLE'}: {style}")
        time.sleep(1)
    (OUT / "styles.txt").write_text("\n".join(results) + "\n")
    print("\n".join(results))


def main() -> None:
    install_and_launch()
    try:
        walk_onboarding()
        navigate()
        select_styles()
    except Exception as e:  # noqa: BLE001 - QA must never block artifacts
        print(f"driver error: {e}")
    log = adb("logcat", "-d", "-v", "threadtime").stdout
    (OUT / "logcat.txt").write_text(log)
    fatal = subprocess.run(
        ["grep", "-nE", "FATAL EXCEPTION|AndroidRuntime", "qa-artifacts/logcat.txt"],
        capture_output=True, text=True).stdout
    print(fatal[:3000] or "no fatal exceptions")


if __name__ == "__main__":
    sys.exit(main() or 0)
