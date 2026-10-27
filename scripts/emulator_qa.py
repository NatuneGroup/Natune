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

# The Android Appearance "Player design style" dropdown shows display
# names, not enum ids. Android offers 17 entries; the 18th style
# (METROLIST_KMP) lives in shared-domain only and is not listed on Android.
STYLES = [
    "Classic", "Modern", "Minimal", "Cinematic", "Little", "Expressive",
    "Immersive", "Immersive Extended", "Material Extended", "Editorial",
    "Apple Music", "BitChord", "TikTok", "SimpMusic", "SpatialFlow",
    "Looper", "Flamingo",
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
                      n.get("resource-id") or "", cx, cy,
                      n.get("clickable") == "true"))
    print(f"uiauto-{tag}: {len(nodes)} nodes")
    if not nodes:
        print(path.read_text()[:2000])
    return nodes


def swipe_up() -> None:
    adb("shell", "input", "swipe", "540", "1900", "540", "700", "400")
    time.sleep(2)


def tap_text(pattern: str, tag: str, scrolls: int = 0) -> bool:
    """Tap the first node matching `pattern`; clickable nodes win over plain
    text. Tapping a text node inside a button still hits the button, and the
    anchored patterns cannot match subtitle paragraphs. With scrolls>0,
    swipe up and re-dump until found."""
    pat = re.compile(pattern, re.I)
    for attempt in range(scrolls + 1):
        nodes = dump_ui(f"{tag}-{attempt}" if attempt else tag)
        clickable, plain = [], []
        for text, desc, resid, cx, cy, click in nodes:
            hay = " ".join(x for x in (text, desc, resid) if x)
            if pat.search(hay):
                (clickable if click else plain).append(
                    (text, desc, resid, cx, cy))
        hit = (clickable + plain or [None])[0]
        if hit:
            text, desc, resid, cx, cy = hit
            print(f"tap '{pattern}' -> ({cx},{cy}) [{text or desc or resid}]")
            adb("shell", "input", "tap", str(cx), str(cy))
            time.sleep(3)
            return True
        if attempt < scrolls:
            swipe_up()
    print(f"no node matching '{pattern}'")
    return False


def install_and_launch() -> None:
    apks = sorted(Path("app/build/outputs/apk").rglob("*.apk"))
    print(f"apk: {apks[0]}")
    print(adb("install", "-r", str(apks[0])).stdout.strip())
    adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1")
    time.sleep(15)


def foreground() -> None:
    """Bring the app back to the front without pressing Back (Back on the
    root activity just sends the app to the launcher)."""
    adb("shell", "monkey", "-p", PKG,
        "-c", "android.intent.category.LAUNCHER", "1")
    time.sleep(4)


def walk_onboarding() -> None:
    shot("01-onboarding")
    # Welcome -> Let's go; Permissions -> Continue; Login -> Skip for now;
    # Community -> Start listening. The pager is not user-scrollable.
    # Labels use typographic apostrophes (Let’s go) — use . for the quote.
    # Every page's action button must be a clickable node (the subtitles
    # contain the same words) and may sit below the fold: keep scrolling.
    # Each page shows exactly one of these buttons; a miss after scrolling
    # means onboarding is finished.
    for i in range(1, 16):
        if not tap_text("let.s go|^next$|^skip for now$|^start listening$",
                        f"onb-{i}", scrolls=3):
            break
    time.sleep(5)
    shot("02-onboarding-done")


def navigate() -> None:
    foreground()
    time.sleep(5)
    shot("03-home")
    tap_text("^search$|nav.*search", "nav-search")
    time.sleep(3)
    shot("04-search")
    tap_text("^library$|nav.*library", "nav-library")
    time.sleep(3)
    shot("05-library")
    # The settings gear only exists on the Home route; go back first.
    tap_text("^home$|nav.*home", "nav-home")
    time.sleep(3)
    # The gear has contentDescription "Settings" but the home feed's merged
    # semantics sometimes hide it from uiautomator — fall back to its fixed
    # top-right position on the 1080x2400 emulator display.
    if not tap_text("^settings$|settings.*icon|more options|^account$",
                    "nav-settings", scrolls=1):
        print("settings gear not in dump; tapping (985,140)")
        adb("shell", "input", "tap", "985", "140")
        time.sleep(3)
    time.sleep(3)
    shot("06-settings")
    tap_text("^appearance$|^interface$", "nav-appearance", scrolls=2)
    time.sleep(3)
    shot("07-appearance")


def select_styles() -> None:
    results = []
    for i, style in enumerate(STYLES, 1):
        tap_text("^player design style$|^player design$|^player style$",
                 f"style-open-{i}", scrolls=4)
        time.sleep(1)
        ok = tap_text(f"^{re.escape(style)}$", f"style-{i}", scrolls=4)
        shot(f"style-{i:02d}-{style.replace(' ', '_')}")
        results.append(f"{'selected' if ok else 'NOT SELECTABLE'}: {style}")
        time.sleep(1)
        # Close any selection sheet that stayed open, then re-foreground in
        # case Back sent the app to the launcher instead.
        adb("shell", "input", "keyevent", "4")
        foreground()
    results.append("n/a on Android host (shared-domain only): METROLIST_KMP")
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
