#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (c) 2026 vossgraves
"""Release artifact byte breakdowns and Android runtime frame and memory samples (VAL-REL-001).

Every number comes from an input the operator names: an artifact path, or an adb device
serial passed with --adb-serial. The tool never builds, signs, installs, launches or drives
the app; during a runtime window the operator exercises the app by hand. Anything that cannot
be collected is reported as unrun (input or tool absent) or error (the attempt failed), and
behaviour outside this collector is listed as unsupported. Nothing is reported as passed.

Battery and energy are never measured or derived here. Those need a separately approved
physical-device method, so the report states that no battery claim is made.

--verify-self-test checks this tool's parsers, classifier and arithmetic on small fixtures
held in memory. It is a self-consistency check of the tool, not product evidence, and it reads
no artifact and contacts no device.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import time
import zipfile
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Sequence

PACKAGE_NAME = "moe.rukamori.archivetune"
MAIN_ACTIVITY = ".MainActivity"
CATEGORIES = ("dex", "resources", "assets", "native", "jvm_classes", "manifest_and_signing", "other")
NATIVE_SUFFIXES = (".so", ".dll", ".dylib", ".jnilib")
ARCHIVE_SUFFIXES = (".apk", ".zip", ".jar", ".aab")
ROLE_PATTERN = re.compile(r"^[a-z][a-z0-9_-]{0,31}$")
ADB_TIMEOUT_SECONDS = 60
WINDOW_MIN_SECONDS = 5
WINDOW_MAX_SECONDS = 3600
EXIT_MEASURED = 0
EXIT_ERROR = 1
EXIT_USAGE = 2
EXIT_INCOMPLETE = 3


def classify_entry(name: str) -> str:
    """Maps an archive member or relative file path to one breakdown category."""
    normalized = name.replace("\\", "/").lstrip("/")
    lower = normalized.lower()
    base = lower.rsplit("/", 1)[-1]
    if lower.startswith("meta-inf/") or lower == "androidmanifest.xml":
        return "manifest_and_signing"
    if "/" not in lower and re.fullmatch(r"classes\d*\.dex", base):
        return "dex"
    if lower.endswith(NATIVE_SUFFIXES):
        return "native"
    if lower.startswith("assets/"):
        return "assets"
    if lower.startswith(("res/", "resources/", "composeresources/")) or lower == "resources.arsc":
        return "resources"
    if lower.endswith(".class"):
        return "jvm_classes"
    return "other"


@dataclass
class Bucket:
    files: int = 0
    entry_bytes: int = 0
    compressed_bytes: int = 0

    def add(self, entry_bytes: int, compressed_bytes: int) -> None:
        self.files += 1
        self.entry_bytes += entry_bytes
        self.compressed_bytes += compressed_bytes

    def as_json(self) -> dict[str, int]:
        return {"files": self.files, "entryBytes": self.entry_bytes, "compressedBytes": self.compressed_bytes}


Breakdown = dict[str, Bucket]


def add_zip_entries(archive: zipfile.ZipFile, breakdown: Breakdown) -> None:
    for info in archive.infolist():
        if info.is_dir():
            continue
        breakdown.setdefault(classify_entry(info.filename), Bucket()).add(info.file_size, info.compress_size)


def add_directory_entries(root: Path, breakdown: Breakdown, notes: list[str]) -> None:
    for current, dirnames, filenames in os.walk(root, followlinks=False):
        dirnames.sort()
        for name in sorted(filenames):
            path = Path(current, name)
            relative = path.relative_to(root).as_posix()
            if path.is_symlink():
                notes.append(f"symlink not followed: {relative}")
                continue
            size = path.stat().st_size
            if path.suffix.lower() in ARCHIVE_SUFFIXES and zipfile.is_zipfile(path):
                with zipfile.ZipFile(path) as nested:
                    add_zip_entries(nested, breakdown)
                notes.append(f"nested archive expanded: {relative}")
                continue
            breakdown.setdefault(classify_entry(relative), Bucket()).add(size, size)


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


@dataclass
class ArtifactReport:
    role: str
    path: Path
    kind: str
    status: str = "measured"
    reason: str = ""
    on_disk_bytes: int = 0
    sha256: str | None = None
    breakdown: Breakdown = field(default_factory=dict)
    notes: list[str] = field(default_factory=list)


def inspect_artifact(role: str, path: Path) -> ArtifactReport:
    if not path.exists():
        return ArtifactReport(role, path, "missing", status="unrun", reason="artifact path does not exist")
    notes: list[str] = []
    if path.is_dir():
        breakdown: Breakdown = {}
        add_directory_entries(path, breakdown, notes)
        on_disk = sum(p.stat().st_size for p in path.rglob("*") if p.is_file() and not p.is_symlink())
        return ArtifactReport(role, path, "directory", on_disk_bytes=on_disk, breakdown=breakdown, notes=notes)
    size = path.stat().st_size
    digest = sha256_of(path)
    suffix = path.suffix.lower()
    if suffix in ARCHIVE_SUFFIXES:
        breakdown = {}
        try:
            with zipfile.ZipFile(path) as archive:
                add_zip_entries(archive, breakdown)
        except zipfile.BadZipFile:
            return ArtifactReport(
                role, path, "invalid-archive", status="error", reason="not a readable zip archive",
                on_disk_bytes=size, sha256=digest,
            )
        kind = "apk" if suffix == ".apk" else "archive"
        return ArtifactReport(role, path, kind, on_disk_bytes=size, sha256=digest, breakdown=breakdown)
    if suffix in NATIVE_SUFFIXES:
        return ArtifactReport(
            role, path, "native-library", on_disk_bytes=size, sha256=digest,
            breakdown={classify_entry(path.name): Bucket(1, size, size)},
        )
    notes.append("opaque package: contents are not expanded, so no internal breakdown is claimed")
    return ArtifactReport(
        role, path, "package", on_disk_bytes=size, sha256=digest,
        breakdown={"other": Bucket(1, size, size)}, notes=notes,
    )


def percent(delta: int, base: int) -> float | None:
    if base == 0:
        return None
    return round(100.0 * delta / base, 2)


def compare_artifacts(baseline: ArtifactReport, candidate: ArtifactReport) -> dict[str, object]:
    if baseline.status != "measured" or candidate.status != "measured":
        return {"status": "unrun", "reason": "both artifacts must be measured before a comparison"}
    if baseline.kind != candidate.kind:
        return {
            "status": "unrun",
            "reason": f"artifact kinds differ ({baseline.kind} vs {candidate.kind}); no matched comparison",
        }
    rows = []
    for category in CATEGORIES:
        base_bytes = baseline.breakdown.get(category, Bucket()).entry_bytes
        cand_bytes = candidate.breakdown.get(category, Bucket()).entry_bytes
        rows.append({
            "category": category,
            "baselineBytes": base_bytes,
            "candidateBytes": cand_bytes,
            "deltaBytes": cand_bytes - base_bytes,
            "deltaPercent": percent(cand_bytes - base_bytes, base_bytes),
        })
    return {
        "status": "measured",
        "note": "matched variants are the operator's responsibility; compare the same flavor and ABI pair",
        "onDiskDeltaBytes": candidate.on_disk_bytes - baseline.on_disk_bytes,
        "categories": rows,
    }


GFX_PERCENTILES = (("p50Ms", "50th"), ("p90Ms", "90th"), ("p95Ms", "95th"), ("p99Ms", "99th"))
GFX_COUNTERS = (
    ("missedVsync", "Number Missed Vsync"),
    ("slowUiThread", "Number Slow UI thread"),
    ("frameDeadlineMissed", "Number Frame deadline missed"),
)


def parse_gfxinfo(text: str) -> dict[str, float | int] | None:
    """Reads the summary lines of `dumpsys gfxinfo <package>`; None when no frame count is present."""
    total = re.search(r"Total frames rendered:\s*(\d+)", text)
    if total is None:
        return None
    result: dict[str, float | int] = {"totalFrames": int(total.group(1))}
    janky = re.search(r"Janky frames:\s*(\d+)(?:\s*\(([\d.]+)%\))?", text)
    if janky is not None:
        result["jankyFrames"] = int(janky.group(1))
        if janky.group(2) is not None:
            result["jankyPercent"] = float(janky.group(2))
    for key, label in GFX_PERCENTILES:
        match = re.search(rf"{label} percentile:\s*(\d+)ms", text)
        if match is not None:
            result[key] = int(match.group(1))
    for key, label in GFX_COUNTERS:
        match = re.search(rf"{re.escape(label)}:\s*(\d+)", text)
        if match is not None:
            result[key] = int(match.group(1))
    return result


def parse_meminfo_total_kb(text: str) -> int | None:
    """Reads the TOTAL PSS value (kilobytes) from `dumpsys meminfo <package>`."""
    match = re.search(r"^\s*TOTAL(?: PSS)?:\s*(\d+)", text, re.MULTILINE)
    return int(match.group(1)) if match else None


def parse_adb_devices(text: str) -> dict[str, str]:
    states: dict[str, str] = {}
    for line in text.splitlines():
        if "\t" not in line:
            continue
        serial, state = line.split("\t", 1)
        states[serial.strip()] = state.strip()
    return states


def run_command(argv: Sequence[str], timeout: float = ADB_TIMEOUT_SECONDS) -> tuple[int, str]:
    try:
        completed = subprocess.run(list(argv), capture_output=True, text=True, timeout=timeout, check=False)
    except subprocess.TimeoutExpired:
        return 124, f"timed out after {timeout:.0f}s"
    except OSError as error:
        return 127, str(error)
    return completed.returncode, f"{completed.stdout}{completed.stderr}"


def device_uptime(adb: str, serial: str) -> float | None:
    code, output = run_command([adb, "-s", serial, "shell", "cat", "/proc/uptime"])
    if code != 0:
        return None
    fields = output.split()
    try:
        return float(fields[0]) if fields else None
    except ValueError:
        return None


def collect_runtime(adb_name: str, serial: str, package: str, window_seconds: int) -> dict[str, object]:
    base: dict[str, object] = {"serial": serial, "package": package, "windowSeconds": window_seconds}
    adb = shutil.which(adb_name)
    if adb is None:
        return {**base, "status": "unrun", "reason": f"adb executable not found: {adb_name}"}
    code, listing = run_command([adb, "devices"])
    states = parse_adb_devices(listing) if code == 0 else {}
    if states.get(serial) != "device":
        seen = ", ".join(f"{s}={v}" for s, v in states.items()) or "none"
        return {**base, "status": "unrun", "reason": f"device {serial} is not attached in state 'device' (seen: {seen})"}
    shell = [adb, "-s", serial, "shell"]
    code, output = run_command([*shell, "pm", "path", package])
    if code != 0 or "package:" not in output:
        return {**base, "status": "unrun", "reason": f"{package} is not installed on {serial}"}
    code, output = run_command([*shell, "dumpsys", "gfxinfo", package, "reset"])
    if code != 0:
        return {**base, "status": "error", "reason": f"gfxinfo reset failed: {output.strip()[:300]}"}
    uptime_start = device_uptime(adb, serial)
    host_start = time.monotonic()
    # The operator uses the app during this window; the collector drives no UI.
    time.sleep(window_seconds)
    host_elapsed = time.monotonic() - host_start
    uptime_end = device_uptime(adb, serial)
    code, gfx_output = run_command([*shell, "dumpsys", "gfxinfo", package])
    frames = parse_gfxinfo(gfx_output) if code == 0 else None
    if frames is None:
        return {**base, "status": "error", "reason": "gfxinfo output had no 'Total frames rendered' line"}
    frames_out: dict[str, object] = dict(frames)
    frames_out["framesPerHostSecond"] = round(int(frames["totalFrames"]) / host_elapsed, 2) if host_elapsed > 0 else None
    frames_out["note"] = "the window spans gfxinfo reset to dump and includes adb command latency"
    code, mem_output = run_command([*shell, "dumpsys", "meminfo", package])
    total_kb = parse_meminfo_total_kb(mem_output) if code == 0 else None
    clock: dict[str, object] = {"hostElapsedSeconds": round(host_elapsed, 3)}
    if uptime_start is not None and uptime_end is not None:
        device_elapsed = uptime_end - uptime_start
        clock["deviceElapsedSeconds"] = round(device_elapsed, 3)
        clock["hostMinusDeviceMs"] = round((host_elapsed - device_elapsed) * 1000, 1)
    else:
        clock["deviceElapsedSeconds"] = None
        clock["note"] = "device uptime unavailable; the device clock was not compared"
    memory: dict[str, object] = {"totalPssKb": total_kb, "allocationCounts": "unsupported"}
    if total_kb is None:
        memory["note"] = "meminfo output had no TOTAL PSS line"
    memory["allocationNote"] = "per-allocation counts need a heap profile, which this collector does not take"
    return {**base, "status": "measured", "reason": "", "frames": frames_out, "memory": memory, "clock": clock}


UNSUPPORTED_METRICS = (
    {"metric": "battery and energy", "reason": "not measured; needs an approved physical-device method, so no claim is made"},
    {"metric": "desktop frame, clock and allocation", "reason": "no desktop benchmark harness in this collector"},
    {"metric": "visibility-gated collectors and single-flight deduplication",
     "reason": "behavioural properties belong to the UI and integration suites, not this byte and frame collector"},
)


def artifact_json(report: ArtifactReport) -> dict[str, object]:
    return {
        "role": report.role,
        "path": str(report.path),
        "kind": report.kind,
        "status": report.status,
        "reason": report.reason,
        "onDiskBytes": report.on_disk_bytes,
        "sha256": report.sha256,
        "categories": {name: report.breakdown.get(name, Bucket()).as_json() for name in CATEGORIES},
        "notes": report.notes,
    }


def parse_artifact_argument(value: str) -> tuple[str, Path]:
    if "=" not in value:
        raise argparse.ArgumentTypeError("expected ROLE=PATH, for example baseline=/path/to/app.apk")
    role, raw_path = value.split("=", 1)
    if not ROLE_PATTERN.fullmatch(role):
        raise argparse.ArgumentTypeError(f"invalid role {role!r}; use lowercase letters, digits, '-' or '_'")
    if not raw_path:
        raise argparse.ArgumentTypeError("artifact path is empty")
    return role, Path(raw_path).expanduser().resolve()


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Measure release artifact byte breakdowns and optional Android runtime samples.",
    )
    parser.add_argument("--verify-self-test", action="store_true",
                        help="check this tool's parsers on in-memory fixtures; reads no artifact or device")
    parser.add_argument("--artifact", action="append", default=[], type=parse_artifact_argument, metavar="ROLE=PATH",
                        help="artifact to break down; use roles baseline and candidate to get a comparison")
    parser.add_argument("--adb-serial", help="authorized Android device serial for runtime samples")
    parser.add_argument("--adb", default="adb", help="adb executable name or path (default: adb on PATH)")
    parser.add_argument("--package", default=PACKAGE_NAME, help=f"application id (default: {PACKAGE_NAME})")
    parser.add_argument("--window-seconds", type=int, default=30,
                        help=f"runtime sampling window, {WINDOW_MIN_SECONDS}..{WINDOW_MAX_SECONDS} seconds")
    parser.add_argument("--output", type=Path, help="write the JSON report to this file instead of stdout")
    return parser


def build_report(args: argparse.Namespace) -> tuple[dict[str, object], list[str]]:
    seen_roles: set[str] = set()
    reports: list[ArtifactReport] = []
    for role, path in args.artifact:
        if role in seen_roles:
            raise ValueError(f"duplicate artifact role: {role}")
        seen_roles.add(role)
        reports.append(inspect_artifact(role, path))
    by_role = {report.role: report for report in reports}
    comparison: dict[str, object] | None = None
    if "baseline" in by_role and "candidate" in by_role:
        comparison = compare_artifacts(by_role["baseline"], by_role["candidate"])
    runtime: dict[str, object] | None = None
    if args.adb_serial:
        runtime = collect_runtime(args.adb, args.adb_serial, args.package, args.window_seconds)
    statuses = [report.status for report in reports]
    if comparison is not None:
        statuses.append(str(comparison["status"]))
    if runtime is not None:
        statuses.append(str(runtime["status"]))
    if "error" in statuses:
        overall = "error"
    elif "unrun" in statuses:
        overall = "incomplete"
    else:
        overall = "measured"
    report: dict[str, object] = {
        "tool": "scripts/measure_release_and_perf.py",
        "schemaVersion": 1,
        "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
        "host": {"platform": platform.platform(), "python": platform.python_version()},
        "artifacts": [artifact_json(report) for report in reports],
        "comparison": comparison if comparison is not None else {"status": "not-requested"},
        "runtime": runtime if runtime is not None else {"status": "not-requested"},
        "notClaimed": list(UNSUPPORTED_METRICS),
        "overall": overall,
    }
    summary = [f"{report.role}: {report.kind} {report.status} {report.reason}".rstrip() for report in reports]
    return report, summary


def run_self_test() -> int:
    results: list[tuple[str, bool, str]] = []

    def expect(name: str, actual: object, expected: object) -> None:
        results.append((name, actual == expected, f"expected {expected!r}, got {actual!r}"))

    classifier_cases = {
        "classes.dex": "dex",
        "classes2.dex": "dex",
        "classes/evil.dex": "other",
        "lib/arm64-v8a/libfixture.so": "native",
        "bin/archivetune.dll": "native",
        "lib/libfixture.dylib": "native",
        "res/layout/fixture.xml": "resources",
        "resources.arsc": "resources",
        "assets/fixture.bin": "assets",
        "META-INF/CERT.RSA": "manifest_and_signing",
        "AndroidManifest.xml": "manifest_and_signing",
        "moe/fixture/Example.class": "jvm_classes",
        "README.txt": "other",
    }
    for name, category in classifier_cases.items():
        expect(f"classify {name}", classify_entry(name), category)

    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("classes.dex", b"d" * 1000)
        archive.writestr("classes2.dex", b"e" * 250)
        archive.writestr("lib/arm64-v8a/libfixture.so", b"n" * 500)
        archive.writestr("assets/fixture.bin", b"a" * 10)
        archive.writestr("res/values/strings.xml", b"r" * 20)
    buffer.seek(0)
    breakdown: Breakdown = {}
    with zipfile.ZipFile(buffer) as archive:
        add_zip_entries(archive, breakdown)
    expect("dex entry bytes across classes*.dex", breakdown["dex"].entry_bytes, 1250)
    expect("dex file count", breakdown["dex"].files, 2)
    expect("native entry bytes", breakdown["native"].entry_bytes, 500)
    expect("assets entry bytes", breakdown["assets"].entry_bytes, 10)
    expect("resources entry bytes", breakdown["resources"].entry_bytes, 20)
    expect("deflated compressed bytes do not exceed entry bytes",
           breakdown["dex"].compressed_bytes <= breakdown["dex"].entry_bytes, True)

    gfx_fixture = (
        "Total frames rendered: 1234\n"
        "Janky frames: 56 (4.54%)\n"
        "50th percentile: 6ms\n90th percentile: 9ms\n95th percentile: 11ms\n99th percentile: 16ms\n"
        "Number Missed Vsync: 3\nNumber Slow UI thread: 7\nNumber Frame deadline missed: 2\n"
    )
    gfx = parse_gfxinfo(gfx_fixture)
    expect("gfxinfo total frames", gfx.get("totalFrames") if gfx else None, 1234)
    expect("gfxinfo janky percent", gfx.get("jankyPercent") if gfx else None, 4.54)
    expect("gfxinfo p99", gfx.get("p99Ms") if gfx else None, 16)
    expect("gfxinfo deadline counter", gfx.get("frameDeadlineMissed") if gfx else None, 2)
    expect("gfxinfo without a frame count is rejected", parse_gfxinfo("no summary here"), None)
    expect("meminfo TOTAL PSS", parse_meminfo_total_kb(" TOTAL PSS:   123456   TOTAL RSS: 234567"), 123456)
    expect("meminfo bare TOTAL", parse_meminfo_total_kb("  TOTAL:  99999"), 99999)
    expect("meminfo without TOTAL is rejected", parse_meminfo_total_kb("Native Heap: 12"), None)
    expect("adb devices parse",
           parse_adb_devices("List of devices attached\nemulator-5554\tdevice\nR58X\tunauthorized\n"),
           {"emulator-5554": "device", "R58X": "unauthorized"})
    expect("percent of zero base is undefined", percent(10, 0), None)
    expect("percent rounds to two places", percent(50, 200), 25.0)

    baseline = ArtifactReport("baseline", Path("fixture-baseline.apk"), "apk",
                              on_disk_bytes=2000, breakdown={"dex": Bucket(1, 1000, 900)})
    candidate = ArtifactReport("candidate", Path("fixture-candidate.apk"), "apk",
                               on_disk_bytes=2100, breakdown={"dex": Bucket(1, 1100, 950)})
    comparison = compare_artifacts(baseline, candidate)
    dex_row = next(row for row in comparison["categories"] if row["category"] == "dex")  # type: ignore[index, union-attr]
    expect("comparison dex delta bytes", dex_row["deltaBytes"], 100)
    expect("comparison dex delta percent", dex_row["deltaPercent"], 10.0)
    expect("comparison on-disk delta", comparison["onDiskDeltaBytes"], 100)
    mismatched = compare_artifacts(baseline, ArtifactReport("candidate", Path("x"), "directory"))
    expect("comparison refuses mismatched kinds", mismatched["status"], "unrun")

    passed = 0
    for name, ok, detail in results:
        print(f"{'PASS' if ok else 'FAIL'} {name}" + ("" if ok else f" ({detail})"))
        passed += int(ok)
    total = len(results)
    print(f"tool self-test: {passed}/{total} checks passed. Tool logic only; not product evidence; "
          "no artifact was read and no device was contacted.")
    return EXIT_MEASURED if passed == total else EXIT_ERROR


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.verify_self_test:
        return run_self_test()
    if not args.artifact and not args.adb_serial:
        print("nothing requested: pass --artifact ROLE=PATH and/or --adb-serial SERIAL", file=sys.stderr)
        return EXIT_USAGE
    if not WINDOW_MIN_SECONDS <= args.window_seconds <= WINDOW_MAX_SECONDS:
        print(f"--window-seconds must be between {WINDOW_MIN_SECONDS} and {WINDOW_MAX_SECONDS}", file=sys.stderr)
        return EXIT_USAGE
    try:
        report, summary = build_report(args)
    except ValueError as error:
        print(str(error), file=sys.stderr)
        return EXIT_USAGE
    text = json.dumps(report, indent=2, sort_keys=False)
    if args.output is not None:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text + "\n", encoding="utf-8")
    else:
        print(text)
    for line in summary:
        print(line, file=sys.stderr)
    overall = str(report["overall"])
    if overall == "error":
        return EXIT_ERROR
    if overall == "incomplete":
        return EXIT_INCOMPLETE
    return EXIT_MEASURED


if __name__ == "__main__":
    raise SystemExit(main())
