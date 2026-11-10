#!/usr/bin/env python3
"""Drive the QA emulator: launch the app, walk onboarding/home/settings,
select every player design, and capture a generated local WAV player and logcat.

Settings pages are reached with the app's own routes (the `navigate_to` intent
extra), not with measured row offsets, because the settings tree merges and
reorders sections. Every UI action is best-effort: misses are logged, never
fatal, so the job always reaches the artifact-upload phase -- but a miss on a
row the walk depends on is reported as `QA FAIL` and written to
qa-artifacts/qa-failures.txt instead of being tapped blind.
"""

import os
import re
import subprocess
import sys
import time
import wave
import xml.etree.ElementTree as ET
from pathlib import Path

SDK = os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"]
ADB = f"{SDK}/platform-tools/adb"
PKG = "moe.rukamori.archivetune.debug"
# The debug applicationId suffix is not part of the activity's class name.
MAIN_ACTIVITY = "moe.rukamori.archivetune.MainActivity"
OUT = Path("qa-artifacts")
OUT.mkdir(exist_ok=True)

# QA misses that are worth failing loudly over: printed as they happen and written
# next to the screenshots. Never fatal -- the job must still upload its artifacts.
FAILURES: list = []

# The Android Appearance "Player design style" dropdown shows display
# names, not enum ids. Android offers 17 entries; the 18th style
# (METROLIST_KMP) lives in shared-domain only and is not listed on Android.
STYLES = [
    "Classic", "Modern", "Minimal", "Cinematic", "Little", "Expressive",
    "Immersive", "Immersive Extended", "Material Extended", "Editorial",
    "Apple Music", "BitChord", "TikTok", "SimpMusic", "SpatialFlow",
    "Looper", "Flamingo",
]

# Appearance used to be three sub-pages (theme, player, interface) and is now one
# scrolling page (docs/engineering/settings-route-map.md), so no fixed offset from
# the page's top identifies a row any more -- and nothing here counts rows.
# MainActivity.handleIntent() hands a `navigate_to` intent extra straight to the nav
# controller, and the Appearance destination honours `?scrollTo=<key>` by scrolling
# that row to the top of the viewport (PreferencePositions.scrollToKey), so the route
# decides what sits there independently of the page's row order. `player_design_style`
# is the anchor AppearanceSettings tags the row with -- the same key settings search
# hands out as `settings/appearance?scrollTo=player_design_style`.
SETTINGS_ROUTE = "settings"
APPEARANCE_ROUTE = "settings/appearance"
STYLE_ROW_ROUTE = f"{APPEARANCE_ROUTE}?scrollTo=player_design_style"
# R.string.player_design_style. Matched loosely on purpose: on a merged accessibility
# node the row's title arrives together with its current value ("Player design style
# Classic"), and the phrase is unique to this one row.
STYLE_ROW_LABEL = "player design style"


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


def _match(pattern: str, nodes: list) -> tuple:
    """Split the nodes matching `pattern` into (clickable, plain) lists."""
    pat = re.compile(pattern, re.I)
    clickable, plain = [], []
    for text, desc, resid, cx, cy, click in nodes:
        hay = " ".join(x for x in (text, desc, resid) if x)
        if pat.search(hay):
            (clickable if click else plain).append((text, desc, resid, cx, cy))
    return clickable, plain


def fail(message: str) -> None:
    """Report a QA miss that must not pass silently, and keep it for the artifacts."""
    print(f"QA FAIL: {message}", flush=True)
    FAILURES.append(message)


def tap_text(pattern: str, tag: str, scrolls: int = 0) -> bool:
    """Tap the first node matching `pattern`; clickable nodes win over plain
    text. Tapping a text node inside a button still hits the button, and the
    anchored patterns cannot match subtitle paragraphs. With scrolls>0,
    swipe up and re-dump until found."""
    for attempt in range(scrolls + 1):
        nodes = dump_ui(f"{tag}-{attempt}" if attempt else tag)
        clickable, plain = _match(pattern, nodes)
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
    # Capture fake/local data only; keep the guest offline before the app can start.
    adb("shell", "cmd", "connectivity", "airplane-mode", "enable", check=True)
    adb("shell", "svc", "wifi", "disable", check=True)
    adb("shell", "svc", "data", "disable", check=True)
    print(adb("install", "-r", str(apks[0])).stdout.strip())
    (OUT / "device.txt").write_text("\n".join(
        adb("shell", *command).stdout.strip() for command in [
            ("wm", "size"), ("wm", "density"), ("getprop", "ro.build.version.sdk"),
        ]
    ) + "\n")
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


def tap_xy(x: int, y: int, tag: str) -> None:
    print(f"tap {tag} ({x},{y})")
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(3)


def _tap_node(node: tuple, tag: str) -> None:
    text, desc, resid, cx, cy = node
    print(f"tap {tag} -> ({cx},{cy}) [{text or desc or resid}]")
    adb("shell", "input", "tap", str(cx), str(cy))
    time.sleep(3)


def open_route(route: str, tag: str) -> None:
    """Open a settings route through the `navigate_to` extra MainActivity reads.

    Reaching a settings row by route keeps the walk independent of a page's row
    order. A route the graph rejects is not silent: `am` reports the launch and
    the app logs the navigation failure, and the row lookup that follows fails
    loudly instead of tapping an approximation.
    """
    print(f"route {tag}: {route}")
    r = adb("shell", "am", "start", "-n", f"{PKG}/{MAIN_ACTIVITY}",
            "--es", "navigate_to", route)
    out = (r.stdout or r.stderr).strip()
    if out:
        print(f"am start: {out.splitlines()[-1]}")
    time.sleep(4)


def open_style_row(tag: str) -> bool:
    """Open the 'Player design style' picker from the merged Appearance page.

    The style loop leaves the page on screen with that row already revealed, so
    the first lookup costs one dump. A miss goes back through the Settings root
    and then to STYLE_ROW_ROUTE, which scrolls the row to the top of the viewport;
    the Settings hop first because navigating straight to the Appearance route
    that is already on top is a single-top no-op and would not re-run the scroll.
    A second miss is reported: tapping the offset that used to hold the row is
    exactly what started hitting a Theme row once the three sub-pages merged.
    """
    if tap_text(STYLE_ROW_LABEL, tag, scrolls=1):
        return True
    open_route(SETTINGS_ROUTE, f"{tag}-via-settings")
    open_route(STYLE_ROW_ROUTE, tag)
    if tap_text(STYLE_ROW_LABEL, f"{tag}-route"):
        return True
    shot(f"{tag}-no-row")
    fail(f"{tag}: no '{STYLE_ROW_LABEL}' row on {STYLE_ROW_ROUTE}")
    return False


def dismiss_update() -> None:
    nodes = dump_ui("dismiss-update")
    if _match("new update available", nodes)[0] or _match("new update available", nodes)[1]:
        if not tap_text("never show again|remind me later", "dismiss-update-action"):
            adb("shell", "input", "keyevent", "KEYCODE_BACK")
            time.sleep(2)


def capture_local_player() -> None:
    """Exercise the real Android player with a generated silent WAV, never a provider URL."""
    fixture = OUT / "Parity fixture.wav"
    with wave.open(str(fixture), "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(8000)
        wav.writeframes(b"\0\0" * 8000 * 240)
    target = "/sdcard/Music/parity-fixture.wav"
    adb("shell", "mkdir", "-p", "/sdcard/Music")
    for name in ["parity-up-next-1.wav", "parity-up-next-2.wav", "parity-fixture.wav"]:
        path = f"/sdcard/Music/{name}"
        adb("push", str(fixture), path, check=True)
        adb("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", f"file://{path}")
    adb("shell", "pm", "grant", PKG, "android.permission.READ_MEDIA_AUDIO")
    # The app's local scan creates a real finite queue with upcoming entries. If the
    # scan UI cannot be reached, the single-file intent still supplies player evidence.
    open_route("local_songs", "parity-local-files")
    scanned = tap_text("^settings$", "parity-local-scan-options") and tap_text("^scan device$", "parity-local-scan-start", scrolls=4)
    if scanned:
        time.sleep(8)
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(2)
        scanned = tap_text("^parity-", "parity-play-local-queue")
    (OUT / "parity-fixture-state.txt").write_text(
        "local scan queue selected\n" if scanned else "single-file fallback; upcoming tracks not verified\n")
    open_route("home", "player-home")
    if not scanned:
        adb("shell", "am", "start", "-n", f"{PKG}/{MAIN_ACTIVITY}",
            "-a", "android.intent.action.VIEW", "-d", f"file://{target}", "-t", "audio/wav")
    time.sleep(8)
    adb("shell", "input", "keyevent", "KEYCODE_MEDIA_PAUSE")
    time.sleep(2)
    shot("parity-mini-player")
    if not tap_text("parity-(fixture|up-next)", "expand-local-player"):
        fail("local player: fixture title not found; expanded player not captured")
        return
    shot("parity-player")
    if tap_text("^queue$|^up next$", "local-queue"):
        shot("parity-queue")
        dump_ui("parity-queue")
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(2)
    else:
        fail("local player: queue control not found")
    if tap_text("^lyrics$", "local-lyrics"):
        shot("parity-lyrics")
        dump_ui("parity-lyrics")
    else:
        fail("local player: lyrics control not found")
    # No timed lyrics are embedded in this WAV; the empty lyrics state is evidence,
    # and is never described as verification of the loaded lyrics renderer.
    fixture.unlink()


def navigate() -> None:
    foreground()
    dismiss_update()
    open_route("home", "nav-home-capture")
    shot("03-home")
    open_route("search", "nav-search")
    time.sleep(3)
    shot("04-search")
    open_route("library", "nav-library")
    time.sleep(3)
    shot("05-library")
    # The settings entry only exists on the Home route, so go back there first.
    open_route("home", "nav-home")
    time.sleep(3)
    # Settings and Appearance are opened by route rather than by tapping the gear
    # and a fixed row offset: the Appearance page grows and reorders its sections,
    # and a stale offset silently lands on a different row.
    open_route(SETTINGS_ROUTE, "nav-settings")
    shot("06-settings")
    open_route("settings/lyrics", "nav-lyrics-settings")
    shot("06a-settings-lyrics")
    open_route(APPEARANCE_ROUTE, "nav-appearance")
    shot("06b-appearance-root")
    # The player section, at the style picker's own row.
    open_route(STYLE_ROW_ROUTE, "nav-appearance-player")
    shot("07-appearance")


def select_styles() -> None:
    results = []
    if not open_style_row("style-open-first"):
        results.append("aborted: player design style row not found")
        (OUT / "styles.txt").write_text("\n".join(results) + "\n")
        print("\n".join(results))
        return
    shot("08-style-sheet")
    for i, style in enumerate(STYLES, 1):
        # The picker closes on selection, so the next style opens it again; the
        # row is still on screen, which open_style_row checks before navigating.
        if i > 1 and not open_style_row(f"style-open-{i}"):
            results.append(f"NOT SELECTED (row not found): {style}")
            continue
        # Options move with density and sheet size. Only a matching accessible label is tapped.
        picked = "label" if tap_text(f"^{re.escape(style)}$", f"style-pick-{i}", scrolls=4) else ""
        if not picked:
            fail(f"style-pick-{i}: '{style}' option not found")
            adb("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(2)
        shot(f"style-{i:02d}-{style.replace(' ', '_')}")
        if picked == "label":
            results.append(f"selected: {style}")
        else:
            results.append(f"NOT SELECTED (picker not open): {style}")
    # Player captures use the shipped default instead of the last style from the enumeration.
    if not open_style_row("style-reset-default") or not tap_text("^Cinematic$", "style-reset-default-option", scrolls=4):
        fail("could not restore default Cinematic player style before captures")
    results.append("n/a on Android host (shared-domain only): METROLIST_KMP")
    (OUT / "styles.txt").write_text("\n".join(results) + "\n")
    print("\n".join(results))


def main() -> None:
    install_and_launch()
    try:
        walk_onboarding()
        navigate()
        select_styles()
        capture_local_player()
    except Exception as e:  # noqa: BLE001 - QA must never block artifacts
        print(f"driver error: {e}")
        fail(f"driver error: {e}")
    log = adb("logcat", "-d", "-v", "threadtime").stdout
    (OUT / "logcat.txt").write_text(log)
    if FAILURES:
        (OUT / "qa-failures.txt").write_text("\n".join(FAILURES) + "\n")
        print(f"{len(FAILURES)} QA FAILURES (see qa-failures.txt)")
    fatal = subprocess.run(
        ["grep", "-nE", "FATAL EXCEPTION|AndroidRuntime", "qa-artifacts/logcat.txt"],
        capture_output=True, text=True).stdout
    print(fatal[:3000] or "no fatal exceptions")


if __name__ == "__main__":
    sys.exit(main() or 0)
