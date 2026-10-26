#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (c) 2026 vossgraves
"""In-place upgrade verifier for UPGRADE-001 through UPGRADE-005 (VAL-REL-002).

An update over an installed build is proven only from authorized inputs: the trusted original
release APK, the candidate APK, the public signer digest of the original release, an adb device
when device steps are requested, and database copies that an operator pulled from an authorized
QA target. This tool never builds, signs, uninstalls, clears app data, downgrades, reads signing
keys or passwords, or exports app-private data. Launching the app is not data parity; UPGRADE-005
is the only evidence for retained data.

Outcomes per check: passed (checked and satisfied), failed (checked and violated), unrun (a
required input or tool is absent), reviewed (a different candidate signer accepted only through an
explicit review reference, recorded in the report), and unsupported (outside UPGRADE-001..005).
The verdict is passed only when every UPGRADE-001..005 check passed or was reviewed; otherwise it
is failed or incomplete. Nothing is marked passed by default.

--self-test checks this tool's parsers, version gate and database comparison on fixtures held in
memory and in a temporary directory. It is a self-consistency check of the tool, not product
evidence, and it reads no APK and contacts no device.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import shutil
import sqlite3
import subprocess
import tempfile
import zipfile
from collections import Counter
from contextlib import closing
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Sequence

PACKAGE_NAME = "moe.rukamori.archivetune"
LAUNCH_ACTIVITY = ".MainActivity"
TRUSTED_VERSION_CODE_FLOOR = 6833  # app/build.gradle.kts trustedUpgradeVersionCodeFloor
VERSION_CODE_CEILING = 2100000000
ROOM_VERSION = 36
ROOM_IDENTITY_ROW_ID = 42
ADB_TIMEOUT_SECONDS = 60
INSTALL_TIMEOUT_SECONDS = 900
IDENTIFIER = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
DIGEST_LINE = re.compile(r"certificate SHA-256 digest:\s*([0-9a-fA-F]{64})")
HEX_DIGEST = re.compile(r"^[0-9a-fA-F]{64}$")
SDK_TOOL_GLOBS = {
    "apksigner": ("build-tools/*/apksigner",),
    "apkanalyzer": ("cmdline-tools/latest/bin/apkanalyzer", "cmdline-tools/*/bin/apkanalyzer", "tools/bin/apkanalyzer"),
    "adb": ("platform-tools/adb",),
}
DEFAULT_SDK_ROOTS = ("~/Android/Sdk", "~/Library/Android/sdk")
OUT_OF_SCOPE = (
    ("UPGRADE-006", "persistent queue, player state and automix readers are outside this verifier"),
    ("UPGRADE-007", "DataStore settings and account slots are outside this verifier"),
    ("UPGRADE-008", "files, downloads, SAF grants and fonts are outside this verifier"),
    ("UPGRADE-009", "private feed and update-offer policy are outside this verifier"),
    ("UPGRADE-010", "exact-head BUILD and TESTS evidence is outside this verifier"),
)
EXIT_PASSED = 0
EXIT_FAILED = 1
EXIT_USAGE = 2
EXIT_INCOMPLETE = 3


def run_command(argv: Sequence[str], timeout: float = ADB_TIMEOUT_SECONDS) -> tuple[int, str]:
    try:
        completed = subprocess.run(list(argv), capture_output=True, text=True, timeout=timeout, check=False)
    except subprocess.TimeoutExpired:
        return 124, f"timed out after {timeout:.0f}s"
    except OSError as error:
        return 127, str(error)
    return completed.returncode, f"{completed.stdout}{completed.stderr}"


def locate_tool(name: str, explicit: str | None) -> str | None:
    """Finds a tool by explicit path or name, then PATH, then the Android SDK layout."""
    if explicit:
        found = shutil.which(explicit)
        if found:
            return found
        candidate = Path(explicit).expanduser()
        return str(candidate) if candidate.is_file() else None
    found = shutil.which(name)
    if found:
        return found
    roots = [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"), *DEFAULT_SDK_ROOTS]
    for root in filter(None, roots):
        base = Path(root).expanduser()
        for pattern in SDK_TOOL_GLOBS.get(name, ()):
            matches = sorted(match for match in base.glob(pattern) if match.is_file())
            if matches:
                # Lexicographically last match; pass --apksigner or --apkanalyzer for an exact version.
                return str(matches[-1])
    return None


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def describe_file(path: Path | None) -> dict[str, object] | None:
    if path is None:
        return None
    if not path.is_file():
        return {"path": str(path), "present": False}
    return {"path": str(path), "present": True, "bytes": path.stat().st_size, "sha256": sha256_of(path)}


def parse_int(text: str) -> int | None:
    stripped = text.strip()
    return int(stripped) if re.fullmatch(r"\d+", stripped) else None


def coerce_code(value: object) -> int | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value
    if isinstance(value, str):
        return parse_int(value)
    return None


def signer_digests(text: str) -> list[str]:
    return sorted({match.group(1).lower() for match in DIGEST_LINE.finditer(text)})


def read_version_code(apkanalyzer: str, apk: Path) -> tuple[int | None, str]:
    code, output = run_command([apkanalyzer, "manifest", "version-code", str(apk)])
    if code != 0:
        return None, f"apkanalyzer failed: {output.strip()[:200]}"
    return parse_int(output), output.strip()


def evaluate_version_gate(
    starting: int | None,
    candidate: int | None,
    floor: int,
    previous: Sequence[int],
    ceiling: int = VERSION_CODE_CEILING,
) -> tuple[str, str]:
    if candidate is None:
        return "failed", "candidate versionCode is missing or not an integer; unknown codes are rejected"
    if not 1 <= candidate <= ceiling:
        return "failed", f"candidate versionCode {candidate} is outside 1..{ceiling}"
    if starting is None:
        return "unrun", "starting versionCode was not read"
    required_above = max([starting, floor, *previous])
    if candidate <= required_above:
        return "failed", f"candidate versionCode {candidate} is not strictly above {required_above}"
    return "passed", (
        f"candidate versionCode {candidate} is strictly above {required_above} "
        f"(starting {starting}, trusted floor {floor}, previously distributed {sorted(previous)})"
    )


def read_application_id(apkanalyzer: str, apk: Path) -> tuple[str | None, str]:
    code, output = run_command([apkanalyzer, "manifest", "application-id", str(apk)])
    if code != 0:
        return None, f"apkanalyzer failed: {output.strip()[:200]}"
    return output.strip(), ""


def apk_native_abis(apk: Path) -> set[str]:
    abis: set[str] = set()
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            parts = name.split("/")
            if len(parts) >= 3 and parts[0] == "lib" and parts[-1].endswith(".so"):
                abis.add(parts[1])
    return abis


def device_properties(adb: str, serial: str) -> dict[str, object] | None:
    sdk_code, sdk_text = run_command([adb, "-s", serial, "shell", "getprop", "ro.build.version.sdk"])
    abi_code, abi_text = run_command([adb, "-s", serial, "shell", "getprop", "ro.product.cpu.abilist"])
    if sdk_code != 0 or abi_code != 0:
        return None
    return {"sdk": parse_int(sdk_text), "abis": [item for item in abi_text.strip().split(",") if item]}


def read_installed_package(adb: str, serial: str, package: str) -> dict[str, object] | None:
    code, output = run_command([adb, "-s", serial, "shell", "dumpsys", "package", package])
    if code != 0 or f"Package [{package}]" not in output:
        return None
    version = re.search(r"versionCode=(\d+)", output)
    user = re.search(r"userId=(\d+)", output)
    first_install = re.search(r"firstInstallTime=([^\r\n]+)", output)
    if version is None or user is None or first_install is None:
        return None
    return {
        "versionCode": int(version.group(1)),
        "userId": user.group(1),
        "firstInstallTime": first_install.group(1).strip(),
    }


def check_application_identity(
    apkanalyzer: str | None, trusted: Path | None, candidate: Path | None, package: str
) -> dict[str, object]:
    base: dict[str, object] = {"id": "UPGRADE-001"}
    if apkanalyzer is None:
        return {**base, "status": "unrun", "reason": "apkanalyzer not found (--apkanalyzer, PATH or ANDROID_HOME)"}
    if trusted is None or candidate is None:
        return {**base, "status": "unrun", "reason": "--trusted-apk and --candidate-apk are required"}
    identities: dict[str, str | None] = {}
    for label, apk in (("trusted", trusted), ("candidate", candidate)):
        value, error = read_application_id(apkanalyzer, apk)
        if value is None:
            return {**base, "status": "failed", "reason": f"{label} application id could not be read: {error}"}
        identities[label] = value
    evidence: dict[str, object] = {"expectedPackage": package, "applicationIds": identities}
    if any(value != package for value in identities.values()):
        return {**base, "status": "failed", "reason": f"application id must be exactly {package}", "evidence": evidence}
    return {**base, "status": "passed", "reason": f"both application ids equal {package}", "evidence": evidence}


def check_device_compatibility(
    apkanalyzer: str | None, adb: str | None, serial: str | None, candidate: Path | None
) -> tuple[str, str, dict[str, object]]:
    if not serial:
        return "unrun", "no --device-serial supplied; device SDK and ABI compatibility not checked", {}
    if adb is None or apkanalyzer is None or candidate is None:
        return "unrun", "adb, apkanalyzer or the candidate APK is unavailable", {}
    props = device_properties(adb, serial)
    if props is None or props.get("sdk") is None:
        return "unrun", "device SDK level or ABI list could not be read", {}
    code, min_sdk_text = run_command([apkanalyzer, "manifest", "min-sdk-version", str(candidate)])
    min_sdk = parse_int(min_sdk_text) if code == 0 else None
    if min_sdk is None:
        return "unrun", "candidate minSdk could not be read", {}
    try:
        needed_abis = apk_native_abis(candidate)
    except (zipfile.BadZipFile, OSError) as error:
        return "unrun", f"candidate native ABIs could not be read ({type(error).__name__})", {}
    device_abis = list(props["abis"])  # type: ignore[arg-type]
    evidence: dict[str, object] = {
        "deviceSdk": props["sdk"],
        "candidateMinSdk": min_sdk,
        "deviceAbis": device_abis,
        "candidateNativeAbis": sorted(needed_abis),
    }
    if int(props["sdk"]) < min_sdk:  # type: ignore[arg-type]
        return "failed", f"device SDK {props['sdk']} is below candidate minSdk {min_sdk}", evidence
    if needed_abis and not (needed_abis & set(device_abis)):
        return "failed", "candidate native ABIs do not include any device ABI", evidence
    return "passed", f"device SDK {props['sdk']} meets minSdk {min_sdk}; ABI compatible", evidence


def check_signers(
    apksigner: str | None,
    trusted: Path | None,
    candidate: Path | None,
    trusted_expected: Sequence[str],
    reviewed: Sequence[str],
    review_reference: str | None,
) -> dict[str, object]:
    base: dict[str, object] = {"id": "UPGRADE-002"}
    if apksigner is None:
        return {**base, "status": "unrun", "reason": "apksigner not found (--apksigner, PATH or ANDROID_HOME)"}
    if trusted is None or candidate is None:
        return {**base, "status": "unrun", "reason": "--trusted-apk and --candidate-apk are required"}
    digests: dict[str, list[str]] = {}
    for label, apk in (("trusted", trusted), ("candidate", candidate)):
        code, output = run_command([apksigner, "verify", "--verbose", "--print-certs", str(apk)])
        if code != 0 or "Verifies" not in output:
            return {**base, "status": "failed", "reason": f"apksigner verify failed for the {label} APK"}
        digests[label] = signer_digests(output)
    evidence: dict[str, object] = {
        "trustedSignerSha256": digests["trusted"],
        "candidateSignerSha256": digests["candidate"],
    }
    if not digests["trusted"] or not digests["candidate"]:
        return {**base, "status": "failed", "reason": "no certificate SHA-256 digest was parsed", "evidence": evidence}
    if not trusted_expected:
        return {**base, "status": "unrun", "evidence": evidence,
                "reason": "no trusted original signer digest supplied (--trusted-signer-sha256); both APKs verify, "
                          "but the trusted APK's authorship is not established"}
    expected = sorted({value.lower() for value in trusted_expected})
    evidence["expectedSignerSha256"] = expected
    if digests["trusted"] != expected:
        return {**base, "status": "failed", "evidence": evidence,
                "reason": "the trusted APK is not signed by the supplied original release signer"}
    if digests["candidate"] == expected:
        return {**base, "status": "passed", "evidence": evidence,
                "reason": "candidate signer digest equals the trusted original release signer"}
    if reviewed and review_reference and digests["candidate"] == sorted({value.lower() for value in reviewed}):
        evidence["reviewReference"] = review_reference
        return {**base, "status": "reviewed", "evidence": evidence,
                "reason": "candidate signer differs and was accepted only by the explicit lineage review reference"}
    return {**base, "status": "failed", "evidence": evidence,
            "reason": "candidate signer digest differs from the trusted original release signer"}


def metadata_outcome(label: str, path: Path, candidate_code: int | None) -> tuple[str, str]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        return "unrun", f"{label}: metadata could not be read ({type(error).__name__})"
    code = coerce_code(data.get("versionCode")) if isinstance(data, dict) else None
    if code is None:
        return "failed", f"{label}: versionCode is missing or not an integer; unknown codes are rejected"
    if candidate_code is None:
        return "failed", f"{label}: candidate versionCode is unknown"
    if code != candidate_code:
        return "failed", f"{label}: versionCode {code} differs from the candidate {candidate_code}"
    return "passed", f"{label}: versionCode {code} matches the candidate"


def check_version_code(
    apkanalyzer: str | None,
    trusted: Path | None,
    candidate: Path | None,
    floor: int,
    previous: Sequence[int],
    metadata: Sequence[tuple[str, Path]],
    installed: dict[str, object] | None,
) -> dict[str, object]:
    base: dict[str, object] = {"id": "UPGRADE-003"}
    if apkanalyzer is None:
        return {**base, "status": "unrun", "reason": "apkanalyzer not found (--apkanalyzer, PATH or ANDROID_HOME)"}
    if trusted is None or candidate is None:
        return {**base, "status": "unrun", "reason": "--trusted-apk and --candidate-apk are required"}
    trusted_code, trusted_error = read_version_code(apkanalyzer, trusted)
    if trusted_code is None:
        return {**base, "status": "unrun", "reason": f"starting APK versionCode not read: {trusted_error}"}
    candidate_code, candidate_error = read_version_code(apkanalyzer, candidate)
    starting_values = [trusted_code]
    installed_code = installed.get("versionCode") if installed else None
    if isinstance(installed_code, int):
        starting_values.append(installed_code)
    starting = max(starting_values)
    gate_status, gate_reason = evaluate_version_gate(starting, candidate_code, floor, previous)
    evidence: dict[str, object] = {
        "trustedApkVersionCode": trusted_code,
        "installedVersionCode": installed_code,
        "candidateVersionCode": candidate_code,
        "candidateReadNote": candidate_error if candidate_code is None else "",
        "trustedFloor": floor,
        "previouslyDistributedCodes": sorted(previous),
    }
    metadata_results = [metadata_outcome(label, path, candidate_code) for label, path in metadata]
    evidence["metadata"] = [{"status": status, "reason": reason} for status, reason in metadata_results]
    if gate_status == "failed":
        return {**base, "status": "failed", "reason": gate_reason, "evidence": evidence}
    if gate_status == "unrun":
        return {**base, "status": "unrun", "reason": gate_reason, "evidence": evidence}
    if any(status == "failed" for status, _ in metadata_results):
        failing = [reason for status, reason in metadata_results if status == "failed"]
        return {**base, "status": "failed", "reason": "; ".join(failing), "evidence": evidence}
    if not any(status == "passed" for status, _ in metadata_results):
        return {**base, "status": "unrun", "evidence": evidence,
                "reason": f"{gate_reason}; feed, notes and summary versionCode metadata not supplied (--metadata)"}
    return {**base, "status": "passed", "reason": gate_reason, "evidence": evidence}


def check_device_install(
    adb: str | None,
    serial: str | None,
    run_steps: bool,
    package: str,
    candidate: Path | None,
    candidate_code: int | None,
    installed: dict[str, object] | None,
    launch_activity: str,
) -> dict[str, object]:
    base: dict[str, object] = {"id": "UPGRADE-004"}
    if not serial or not run_steps:
        return {**base, "status": "unrun",
                "reason": "device steps not requested; pass --device-serial and --run-device-steps on an authorized QA target"}
    if adb is None:
        return {**base, "status": "unrun", "reason": "adb not found (--adb, PATH or ANDROID_HOME)"}
    if candidate is None or candidate_code is None:
        return {**base, "status": "unrun", "reason": "candidate APK or versionCode is unavailable"}
    if installed is None:
        return {**base, "status": "unrun",
                "reason": f"{package} is not installed on {serial}; the authorized baseline must be installed and seeded first"}
    installed_code = int(installed["versionCode"])  # type: ignore[arg-type]
    if installed_code >= candidate_code:
        return {**base, "status": "failed", "evidence": {"installedVersionCode": installed_code},
                "reason": f"installed versionCode {installed_code} is not below candidate {candidate_code}; "
                          "install was not attempted because it would not be an upgrade"}
    code, output = run_command(
        [adb, "-s", serial, "install", "-r", str(candidate)], timeout=INSTALL_TIMEOUT_SECONDS
    )
    lines = [line.strip() for line in output.splitlines() if line.strip()]
    if code != 0 or "Success" not in lines:
        return {**base, "status": "failed",
                "evidence": {"installOutputTail": lines[-3:]},
                "reason": "adb install -r did not report Success"}
    after = read_installed_package(adb, serial, package)
    if after is None:
        return {**base, "status": "failed", "reason": "package metadata was not readable after the install"}
    identity_kept = after["userId"] == installed["userId"] and after["firstInstallTime"] == installed["firstInstallTime"]
    version_moved = after["versionCode"] == candidate_code
    code, launch_output = run_command(
        [adb, "-s", serial, "shell", "am", "start", "-W", "-n", f"{package}/{launch_activity}"]
    )
    launched = code == 0 and "Status: ok" in launch_output
    evidence: dict[str, object] = {
        "userIdBefore": installed["userId"],
        "userIdAfter": after["userId"],
        "firstInstallTimeBefore": installed["firstInstallTime"],
        "firstInstallTimeAfter": after["firstInstallTime"],
        "versionCodeBefore": installed_code,
        "versionCodeAfter": after["versionCode"],
        "launchOk": launched,
        "dataClearingOrUninstallPerformed": False,
    }
    if not identity_kept:
        return {**base, "status": "failed", "evidence": evidence,
                "reason": "package UID or firstInstallTime changed across the install"}
    if not version_moved:
        return {**base, "status": "failed", "evidence": evidence,
                "reason": "installed versionCode did not become the candidate code"}
    if not launched:
        return {**base, "status": "failed", "evidence": evidence, "reason": "normal launch did not report Status: ok"}
    return {**base, "status": "passed", "evidence": evidence,
            "reason": "install -r succeeded with UID and firstInstallTime unchanged, and the launch succeeded. "
                      "This is not data-parity evidence; UPGRADE-005 covers retained data."}


@dataclass(frozen=True)
class RoomSchema:
    version: int
    identity_hash: str
    tables: tuple[str, ...]


def load_room_schema(path: Path) -> RoomSchema:
    data = json.loads(path.read_text(encoding="utf-8"))
    database = data["database"]
    tables = tuple(str(entity["tableName"]) for entity in database["entities"])
    for table in tables:
        if not IDENTIFIER.fullmatch(table):
            raise ValueError(f"unsafe table name in schema: {table!r}")
    return RoomSchema(int(database["version"]), str(database["identityHash"]), tables)


def quote_identifier(name: str) -> str:
    return '"' + name.replace('"', '""') + '"'


def canonical_row(row: Sequence[object]) -> str:
    """Encodes a row with type tags so that 1, '1', 1.0 and NULL never collide."""
    encoded: list[list[str]] = []
    for value in row:
        if value is None:
            encoded.append(["null", ""])
        elif isinstance(value, bytes):
            encoded.append(["blob", value.hex()])
        elif isinstance(value, bool):
            encoded.append(["int", str(int(value))])
        elif isinstance(value, int):
            encoded.append(["int", str(value)])
        elif isinstance(value, float):
            encoded.append(["real", repr(value)])
        else:
            encoded.append(["text", str(value)])
    return json.dumps(encoded, separators=(",", ":"), ensure_ascii=False)


def copy_snapshot(source: Path, scratch: Path, name: str) -> Path:
    """Copies the database and its WAL so the original file is never opened or modified."""
    target = scratch / f"{name}.db"
    shutil.copy2(source, target)
    wal = source.with_name(source.name + "-wal")
    if wal.is_file():
        shutil.copy2(wal, scratch / f"{name}.db-wal")
    return target


def open_read_only(path: Path) -> sqlite3.Connection:
    return sqlite3.connect(f"{path.resolve().as_uri()}?mode=ro", uri=True)


def table_names(connection: sqlite3.Connection) -> set[str]:
    return {row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type = 'table'")}


def column_names(connection: sqlite3.Connection, table: str) -> list[str]:
    return [row[1] for row in connection.execute(f"PRAGMA table_info({quote_identifier(table)})")]


def row_multiset(connection: sqlite3.Connection, table: str, columns: Sequence[str]) -> Counter[str]:
    selected = ", ".join(quote_identifier(column) for column in columns)
    counter: Counter[str] = Counter()
    for row in connection.execute(f"SELECT {selected} FROM {quote_identifier(table)}"):
        counter[canonical_row(row)] += 1
    return counter


def room_identity(connection: sqlite3.Connection) -> str | None:
    if "room_master_table" not in table_names(connection):
        return None
    row = connection.execute(
        "SELECT identity_hash FROM room_master_table WHERE id = ?", (ROOM_IDENTITY_ROW_ID,)
    ).fetchone()
    return str(row[0]) if row else None


def compare_databases(
    before: Path,
    after: Path,
    schema: RoomSchema,
    accepted_before: Sequence[int],
    tables: Sequence[str],
) -> dict[str, object]:
    """Compares seeded rows from before and after the update. Rows may be added but never lost or changed."""
    evidence: dict[str, object] = {
        "beforeSha256": sha256_of(before),
        "afterSha256": sha256_of(after),
        "schemaVersion": schema.version,
        "schemaIdentityHash": schema.identity_hash,
    }
    problems: list[str] = []
    unsupported: list[str] = []
    seeded_rows = 0
    table_reports: list[dict[str, object]] = []
    with tempfile.TemporaryDirectory(prefix="upgrade-db-") as scratch_name:
        scratch = Path(scratch_name)
        before_copy = copy_snapshot(before, scratch, "before")
        after_copy = copy_snapshot(after, scratch, "after")
        with closing(open_read_only(before_copy)) as old, closing(open_read_only(after_copy)) as new:
            old_integrity = old.execute("PRAGMA integrity_check").fetchone()[0]
            new_integrity = new.execute("PRAGMA integrity_check").fetchone()[0]
            old_version = int(old.execute("PRAGMA user_version").fetchone()[0])
            new_version = int(new.execute("PRAGMA user_version").fetchone()[0])
            new_identity = room_identity(new)
            evidence.update({
                "beforeUserVersion": old_version,
                "afterUserVersion": new_version,
                "beforeIntegrity": old_integrity,
                "afterIntegrity": new_integrity,
                "afterRoomIdentityHash": new_identity,
            })
            if old_integrity != "ok" or new_integrity != "ok":
                problems.append("integrity_check did not return ok")
            if old_version not in accepted_before:
                unsupported.append(
                    f"before database user_version {old_version} is outside the accepted set {sorted(accepted_before)}"
                )
            if new_version != schema.version:
                problems.append(f"after database user_version {new_version} differs from schema version {schema.version}")
            if new_identity != schema.identity_hash:
                problems.append("after database Room identity hash does not match the schema identityHash")
            old_tables = table_names(old)
            new_tables = table_names(new)
            for table in tables:
                report: dict[str, object] = {"table": table}
                if table not in new_tables:
                    problems.append(f"table {table} is missing after the update")
                    report["status"] = "failed"
                elif table not in old_tables:
                    report["status"] = "skipped-absent-before"
                else:
                    old_columns = column_names(old, table)
                    new_columns = column_names(new, table)
                    dropped = [column for column in old_columns if column not in new_columns]
                    common = [column for column in old_columns if column in new_columns]
                    if dropped:
                        problems.append(f"table {table} lost columns after the update: {dropped}")
                    if not common:
                        problems.append(f"table {table} has no columns in common")
                        report["status"] = "failed"
                    else:
                        old_rows = row_multiset(old, table, common)
                        new_rows = row_multiset(new, table, common)
                        missing = sum((old_rows - new_rows).values())
                        before_count = sum(old_rows.values())
                        seeded_rows += before_count
                        report.update({
                            "beforeRows": before_count,
                            "afterRows": sum(new_rows.values()),
                            "missingOrChangedRows": missing,
                            "status": "failed" if missing or dropped else "passed",
                        })
                        if missing:
                            problems.append(f"table {table}: {missing} seeded row(s) changed or disappeared")
                table_reports.append(report)
    evidence["tables"] = table_reports
    evidence["seededRowsChecked"] = seeded_rows
    evidence["note"] = ("row contents are compared by digest only; the report never includes row values, "
                        "and a reset would fail as missing seeded rows")
    if problems:
        return {"status": "failed", "reason": "; ".join(problems), "evidence": evidence}
    if unsupported:
        return {"status": "unsupported", "reason": "; ".join(unsupported), "evidence": evidence}
    if seeded_rows == 0:
        return {"status": "unrun", "reason": "the before database holds no rows in the checked tables; the seed did not happen",
                "evidence": evidence}
    return {"status": "passed", "reason": f"{seeded_rows} seeded row(s) survived the update with unchanged content",
            "evidence": evidence}


def check_database(
    db_before: Path | None,
    db_after: Path | None,
    schema_path: Path | None,
    accepted_before: Sequence[int],
    tables: Sequence[str],
    expected_version: int,
) -> dict[str, object]:
    base: dict[str, object] = {"id": "UPGRADE-005"}
    missing = [flag for flag, value in (("--db-before", db_before), ("--db-after", db_after),
                                         ("--room-schema-json", schema_path)) if value is None]
    if missing:
        return {**base, "status": "unrun",
                "reason": f"missing inputs: {', '.join(missing)}; copies must come from an authorized QA target "
                          "(this tool never reads app-private data itself)"}
    for label, path in (("--db-before", db_before), ("--db-after", db_after), ("--room-schema-json", schema_path)):
        if path is None or not path.is_file():
            return {**base, "status": "unrun", "reason": f"{label} does not name a readable file"}
    try:
        schema = load_room_schema(schema_path)  # type: ignore[arg-type]
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as error:
        return {**base, "status": "unrun", "reason": f"Room schema could not be read ({type(error).__name__})"}
    if schema.version != expected_version:
        return {**base, "status": "failed",
                "reason": f"Room schema version {schema.version} differs from the expected {expected_version}"}
    selected = tuple(tables) if tables else schema.tables
    unknown = [table for table in selected if table not in schema.tables]
    if unknown:
        return {**base, "status": "unrun", "reason": f"--table names are not in the schema: {unknown}"}
    outcome = compare_databases(db_before, db_after, schema, accepted_before, selected)  # type: ignore[arg-type]
    return {**base, **outcome}


def parse_labeled_path(value: str) -> tuple[str, Path]:
    if "=" not in value:
        raise argparse.ArgumentTypeError("expected LABEL=PATH")
    label, raw = value.split("=", 1)
    if not label or not raw:
        raise argparse.ArgumentTypeError("label and path must both be non-empty")
    return label, Path(raw).expanduser().resolve()


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Verify UPGRADE-001..005 for an in-place update of the Android app.")
    parser.add_argument("--self-test", action="store_true",
                        help="check this tool's parsers, gate and database comparison on fixtures; reads no APK or device")
    parser.add_argument("--trusted-apk", type=Path, help="authorized original release APK that the candidate updates")
    parser.add_argument("--candidate-apk", type=Path, help="candidate APK to install over the trusted build")
    parser.add_argument("--package", default=PACKAGE_NAME, help=f"expected application id (default: {PACKAGE_NAME})")
    parser.add_argument("--apksigner", help="apksigner path or name (default: PATH, then the Android SDK build-tools)")
    parser.add_argument("--apkanalyzer", help="apkanalyzer path or name (default: PATH, then the Android SDK)")
    parser.add_argument("--adb", help="adb path or name (default: PATH, then the Android SDK platform-tools)")
    parser.add_argument("--trusted-signer-sha256", action="append", default=[],
                        help="public certificate SHA-256 of the original release signer (repeatable)")
    parser.add_argument("--reviewed-signer-sha256", action="append", default=[],
                        help="a different candidate signer accepted only with --review-reference (repeatable)")
    parser.add_argument("--review-reference", help="reference to the explicit lineage review that accepts a different signer")
    parser.add_argument("--floor", type=int, default=TRUSTED_VERSION_CODE_FLOOR,
                        help=f"trusted upgrade floor (default: {TRUSTED_VERSION_CODE_FLOOR}, app/build.gradle.kts)")
    parser.add_argument("--previous-distributed-code", type=int, action="append", default=[],
                        help="versionCode of each previously distributed private build (repeatable)")
    parser.add_argument("--metadata", action="append", default=[], type=parse_labeled_path, metavar="LABEL=PATH",
                        help="feed, notes or summary JSON with a versionCode field (repeatable)")
    parser.add_argument("--device-serial", help="authorized QA device serial")
    parser.add_argument("--run-device-steps", action="store_true",
                        help="install the candidate over the installed baseline and launch it (UPGRADE-004); "
                             "never uninstalls, clears data or downgrades")
    parser.add_argument("--launch-activity", default=LAUNCH_ACTIVITY, help=f"launcher activity (default: {LAUNCH_ACTIVITY})")
    parser.add_argument("--db-before", type=Path, help="song.db copy taken before the update from an authorized QA target")
    parser.add_argument("--db-after", type=Path, help="song.db copy taken after the update and launch")
    parser.add_argument("--room-schema-json", type=Path, help="Room schema JSON for the expected version")
    parser.add_argument("--expected-room-version", type=int, default=ROOM_VERSION,
                        help=f"expected Room schema version (default: {ROOM_VERSION})")
    parser.add_argument("--accepted-before-version", type=int, action="append", default=[],
                        help="database versions accepted as the before state (default: the expected version)")
    parser.add_argument("--table", action="append", default=[],
                        help="restrict the row comparison to these tables (default: every schema entity)")
    parser.add_argument("--output", type=Path, help="write the JSON report to this file instead of stdout")
    return parser


def build_report(args: argparse.Namespace) -> dict[str, object]:
    apksigner = locate_tool("apksigner", args.apksigner)
    apkanalyzer = locate_tool("apkanalyzer", args.apkanalyzer)
    adb = locate_tool("adb", args.adb) if args.device_serial else None
    device_steps = bool(args.device_serial and args.run_device_steps)
    installed: dict[str, object] | None = None
    if device_steps and adb is not None:
        installed = read_installed_package(adb, args.device_serial, args.package)
    candidate_code = None
    if apkanalyzer is not None and args.candidate_apk is not None and args.candidate_apk.is_file():
        candidate_code, _ = read_version_code(apkanalyzer, args.candidate_apk)
    accepted = tuple(args.accepted_before_version) or (args.expected_room_version,)
    compat_status, compat_reason, compat_evidence = check_device_compatibility(
        apkanalyzer, adb, args.device_serial, args.candidate_apk
    )
    identity = check_application_identity(apkanalyzer, args.trusted_apk, args.candidate_apk, args.package)
    if identity["status"] == "passed":
        if compat_status == "passed":
            identity["reason"] = f"{identity['reason']}; {compat_reason}"
        else:
            identity = {**identity, "status": compat_status if compat_status != "passed" else "passed",
                        "reason": f"{identity['reason']}; device compatibility: {compat_reason}",
                        "evidence": {**dict(identity.get("evidence", {})), "device": compat_evidence}}
            if compat_status == "failed":
                identity["status"] = "failed"
    checks = [
        identity,
        check_signers(apksigner, args.trusted_apk, args.candidate_apk, args.trusted_signer_sha256,
                      args.reviewed_signer_sha256, args.review_reference),
        check_version_code(apkanalyzer, args.trusted_apk, args.candidate_apk, args.floor,
                           args.previous_distributed_code, args.metadata, installed),
        check_device_install(adb, args.device_serial, args.run_device_steps, args.package, args.candidate_apk,
                             candidate_code, installed, args.launch_activity),
        check_database(args.db_before, args.db_after, args.room_schema_json, accepted, args.table,
                       args.expected_room_version),
    ]
    return {
        "tool": "scripts/verify_inplace_upgrade.py",
        "schemaVersion": 1,
        "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
        "host": {"platform": platform.platform(), "python": platform.python_version()},
        "expectedPackage": args.package,
        "trustedFloor": args.floor,
        "inputs": {
            "trustedApk": describe_file(args.trusted_apk),
            "candidateApk": describe_file(args.candidate_apk),
            "deviceSerial": args.device_serial,
            "deviceStepsRequested": device_steps,
            "tools": {"apksigner": apksigner, "apkanalyzer": apkanalyzer, "adb": adb},
        },
        "checks": checks,
        "outOfScope": [{"id": check_id, "status": "unsupported", "reason": reason}
                       for check_id, reason in OUT_OF_SCOPE],
        "verdict": verdict_for(checks),
    }


def verdict_for(checks: Sequence[dict[str, object]]) -> str:
    statuses = [str(check["status"]) for check in checks]
    if any(status == "failed" for status in statuses):
        return "failed"
    if all(status in ("passed", "reviewed") for status in statuses):
        return "passed"
    return "incomplete"


def run_self_test() -> int:
    results: list[tuple[str, bool, str]] = []

    def expect(name: str, actual: object, expected: object) -> None:
        results.append((name, actual == expected, f"expected {expected!r}, got {actual!r}"))

    first, second = "ab" * 32, "CD" * 32
    sample = (f"Signer #1 certificate SHA-256 digest: {first}\n"
              f"Signer #2 certificate SHA-256 digest: {second}\n")
    expect("signer digests parsed, lowercased and sorted", signer_digests(sample), sorted([first, second.lower()]))
    expect("signer digests reject short hex", signer_digests("certificate SHA-256 digest: abcd"), [])
    expect("gate passes above floor and previous codes", evaluate_version_gate(6833, 6834, 6833, [6000])[0], "passed")
    expect("gate rejects an equal code", evaluate_version_gate(6833, 6833, 6833, [])[0], "failed")
    expect("gate rejects a lower code", evaluate_version_gate(6900, 6850, 6833, [])[0], "failed")
    expect("gate rejects a code equal to a previous build", evaluate_version_gate(6000, 6500, 6833, [6500])[0], "failed")
    expect("gate rejects an unknown candidate code", evaluate_version_gate(6833, None, 6833, [])[0], "failed")
    expect("gate is unrun without a starting code", evaluate_version_gate(None, 7000, 6833, [])[0], "unrun")
    expect("gate rejects a code above the ceiling", evaluate_version_gate(6833, 2100000001, 6833, [])[0], "failed")
    expect("gate rejects a zero code", evaluate_version_gate(6833, 0, 6833, [])[0], "failed")
    expect("parse_int accepts digits only", (parse_int("6834\n"), parse_int("6.8"), parse_int("")), (6834, None, None))
    expect("canonical row separates integer from text", canonical_row([1]) != canonical_row(["1"]), True)
    expect("canonical row separates NULL from empty text", canonical_row([None]) != canonical_row([""]), True)
    expect("canonical row is stable", canonical_row([1, "a", None]), canonical_row([1, "a", None]))
    expect("quote_identifier doubles embedded quotes", quote_identifier('a"b'), '"a""b"')
    expect("identifier pattern rejects statement text", IDENTIFIER.fullmatch("song; DROP TABLE song") is None, True)
    expect("digest pattern requires 64 hex characters", HEX_DIGEST.fullmatch("ab" * 32) is not None, True)

    with tempfile.TemporaryDirectory(prefix="upgrade-self-test-") as scratch_name:
        root = Path(scratch_name)
        schema = RoomSchema(36, "fixture-identity", ("song", "playlist"))
        tables = ["song", "playlist"]
        before = make_fixture_database(root / "before.db", 36, "fixture-identity",
                                       [(1, "a"), (2, "b")], [(1, "p")])
        grown = make_fixture_database(root / "grown.db", 36, "fixture-identity",
                                      [(1, "a"), (2, "b"), (3, "c")], [(1, "p")])
        lost = make_fixture_database(root / "lost.db", 36, "fixture-identity", [(1, "a")], [(1, "p")])
        reset = make_fixture_database(root / "reset.db", 0, None, [], [])
        renamed = make_fixture_database(root / "renamed.db", 36, "other-identity",
                                        [(1, "a"), (2, "b")], [(1, "p")])
        empty = make_fixture_database(root / "empty.db", 36, "fixture-identity", [], [])
        expect("database compare passes when seeded rows survive with additions",
               compare_databases(before, grown, schema, [36], tables)["status"], "passed")
        expect("database compare fails when a seeded row disappears",
               compare_databases(before, lost, schema, [36], tables)["status"], "failed")
        expect("database compare fails after a reset to version 0",
               compare_databases(before, reset, schema, [36], tables)["status"], "failed")
        expect("database compare fails on a Room identity mismatch",
               compare_databases(before, renamed, schema, [36], tables)["status"], "failed")
        expect("database compare is unsupported for an unaccepted before version",
               compare_databases(before, grown, schema, [35], tables)["status"], "unsupported")
        expect("database compare is unrun when the seed is empty",
               compare_databases(empty, grown, schema, [36], tables)["status"], "unrun")

    passed = 0
    for name, ok, detail in results:
        print(f"{'PASS' if ok else 'FAIL'} {name}" + ("" if ok else f" ({detail})"))
        passed += int(ok)
    total = len(results)
    print(f"verifier self-test: {passed}/{total} checks passed. Tool logic only; not product evidence; "
          "no APK was read and no device was contacted.")
    return EXIT_PASSED if passed == total else EXIT_FAILED


def make_fixture_database(
    path: Path,
    version: int,
    identity: str | None,
    song_rows: Sequence[tuple[int, str]],
    playlist_rows: Sequence[tuple[int, str]],
) -> Path:
    connection = sqlite3.connect(path)
    try:
        connection.execute("CREATE TABLE song (id INTEGER PRIMARY KEY, title TEXT)")
        connection.execute("CREATE TABLE playlist (id INTEGER PRIMARY KEY, name TEXT)")
        connection.executemany("INSERT INTO song VALUES (?, ?)", song_rows)
        connection.executemany("INSERT INTO playlist VALUES (?, ?)", playlist_rows)
        if identity is not None:
            connection.execute("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            connection.execute("INSERT INTO room_master_table VALUES (?, ?)", (ROOM_IDENTITY_ROW_ID, identity))
        connection.execute(f"PRAGMA user_version = {int(version)}")
        connection.commit()
    finally:
        connection.close()
    return path


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.self_test:
        return run_self_test()
    if args.reviewed_signer_sha256 and not args.review_reference:
        print("--reviewed-signer-sha256 requires --review-reference", flush=True)
        return EXIT_USAGE
    for value in [*args.trusted_signer_sha256, *args.reviewed_signer_sha256]:
        if not HEX_DIGEST.fullmatch(value):
            print(f"signer digest must be 64 hexadecimal characters: {value[:16]}...")
            return EXIT_USAGE
    try:
        report = build_report(args)
    except (OSError, ValueError) as error:
        print(f"verifier input error: {type(error).__name__}: {error}")
        return EXIT_USAGE
    text = json.dumps(report, indent=2)
    if args.output is not None:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text + "\n", encoding="utf-8")
    else:
        print(text)
    verdict = str(report["verdict"])
    if verdict == "passed":
        return EXIT_PASSED
    if verdict == "failed":
        return EXIT_FAILED
    return EXIT_INCOMPLETE


if __name__ == "__main__":
    raise SystemExit(main())
