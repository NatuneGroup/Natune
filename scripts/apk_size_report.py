#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (c) 2026 vossgraves
"""Reproducible APK storage report for CI and release audits.

The report reads only ZIP central-directory metadata. Entry payloads are never
decompressed, so a large or highly compressed member cannot turn into a memory
bomb while it is measured. Every byte counter comes from the named APK, not from
an estimate; the archive path, file size and SHA-256 identify the measured
artifact.

Usage:
    python3 scripts/apk_size_report.py app.apk
    python3 scripts/apk_size_report.py --baseline old.apk candidate.apk
    python3 scripts/apk_size_report.py --baseline old.apk --threshold-percent 2 candidate.apk

JSON is written to stdout (or --output); the readable table is written to stderr
so stdout stays machine-parseable. Without --threshold-percent a comparison is a
report only and exits 0. With it, APK file-byte growth above the given percentage
exits 1; category deltas never fail the gate on their own. Signing identity and
certificate material are deliberately not inspected.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import stat
import sys
import zipfile
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import Sequence

TOOL = "scripts/apk_size_report.py"
SCHEMA_VERSION = 1
CATEGORIES = ("dex", "lib", "assets", "res", "resourcesArsc", "metaInf", "other")
CATEGORY_LABELS = {
    "dex": "DEX",
    "lib": "lib",
    "assets": "assets",
    "res": "res",
    "resourcesArsc": "resources.arsc",
    "metaInf": "META-INF",
    "other": "other",
}
COMPRESSION_NAMES = {
    0: "stored",
    8: "deflated",
    12: "bzip2",
    14: "lzma",
    93: "zstd",
    99: "aes",
}
EXIT_OK = 0
EXIT_THRESHOLD = 1
EXIT_USAGE = 2


class ReportError(Exception):
    """The requested artifact cannot be measured as a readable ZIP/APK file."""


@dataclass
class CompressionStat:
    entries: int = 0
    uncompressed_bytes: int = 0
    compressed_bytes: int = 0

    def add(self, uncompressed: int, compressed: int) -> None:
        self.entries += 1
        self.uncompressed_bytes += uncompressed
        self.compressed_bytes += compressed


@dataclass
class Bucket:
    entries: int = 0
    uncompressed_bytes: int = 0
    compressed_bytes: int = 0
    compression_types: Counter[int] = field(default_factory=Counter)

    def add(self, uncompressed: int, compressed: int, compression_type: int) -> None:
        self.entries += 1
        self.uncompressed_bytes += uncompressed
        self.compressed_bytes += compressed
        self.compression_types[compression_type] += 1


@dataclass(frozen=True)
class NativeFile:
    path: str
    uncompressed_bytes: int
    compressed_bytes: int
    compression_type: int


@dataclass
class ApkReport:
    path: Path
    apk_bytes: int
    sha256: str
    file_entries: int = 0
    directory_entries: int = 0
    uncompressed_bytes: int = 0
    compressed_bytes: int = 0
    buckets: dict[str, Bucket] = field(default_factory=lambda: {name: Bucket() for name in CATEGORIES})
    compression_types: dict[int, CompressionStat] = field(default_factory=dict)
    abis: dict[str, list[NativeFile]] = field(default_factory=dict)
    unclassified_library_paths: list[str] = field(default_factory=list)
    duplicate_names: list[str] = field(default_factory=list)
    duplicate_extra_entries: int = 0
    warnings: list[str] = field(default_factory=list)


def terminal_safe(value: str) -> str:
    """Returns ASCII-only text so a hostile archive name cannot drive a terminal."""
    result: list[str] = []
    for character in value:
        code = ord(character)
        if 0x20 <= code <= 0x7E:
            result.append(character)
        elif code <= 0xFF:
            result.append(f"\\x{code:02x}")
        elif code <= 0xFFFF:
            result.append(f"\\u{code:04x}")
        else:
            result.append(f"\\U{code:08x}")
    return "".join(result)


def _normalized_name(name: str) -> str:
    return name.replace("\\", "/").lstrip("/")


def classify_entry(name: str) -> str:
    """Maps one ZIP member to the storage category reported for it."""
    normalized = _normalized_name(name).lower()
    if normalized == "resources.arsc":
        return "resourcesArsc"
    if normalized.startswith("meta-inf/"):
        return "metaInf"
    if normalized.startswith("lib/"):
        return "lib"
    if normalized.startswith("assets/"):
        return "assets"
    if normalized.startswith("res/"):
        return "res"
    base = normalized.rsplit("/", 1)[-1]
    if "/" not in normalized and base.startswith("classes") and base.endswith(".dex"):
        digits = base[len("classes"):-len(".dex")]
        if digits == "" or digits.isdigit():
            return "dex"
    return "other"


def _abi_and_file(name: str) -> tuple[str, str] | None:
    normalized = _normalized_name(name)
    parts = normalized.split("/")
    if len(parts) >= 3 and parts[0].lower() == "lib" and parts[1] and parts[-1]:
        return parts[1], normalized
    return None


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def inspect_apk(path: Path) -> ApkReport:
    """Measures one APK from ZIP metadata without reading entry payloads."""
    try:
        metadata = path.stat()
    except OSError as error:
        reason = error.strerror or "the path cannot be inspected"
        raise ReportError(f"cannot read APK {terminal_safe(str(path))}: {terminal_safe(reason)}") from None
    if not stat.S_ISREG(metadata.st_mode):
        raise ReportError(f"artifact is not a regular file: {terminal_safe(str(path))}")
    if metadata.st_size <= 0:
        raise ReportError(f"artifact is empty: {terminal_safe(str(path))}")
    try:
        digest = sha256_of(path)
        with zipfile.ZipFile(path) as archive:
            infos = archive.infolist()
    except (zipfile.BadZipFile, zipfile.LargeZipFile, NotImplementedError):
        raise ReportError(f"not a readable ZIP/APK archive: {terminal_safe(str(path))}") from None
    except OSError as error:
        reason = error.strerror or "the archive cannot be opened"
        raise ReportError(f"cannot read APK {terminal_safe(str(path))}: {terminal_safe(reason)}") from None

    report = ApkReport(path=path, apk_bytes=metadata.st_size, sha256=digest)
    name_counts: Counter[str] = Counter()
    for info in infos:
        if info.is_dir():
            report.directory_entries += 1
            if info.file_size:
                report.warnings.append("a directory entry declares a non-zero size and was not counted as a file")
            continue
        name = info.filename
        category = classify_entry(name)
        bucket = report.buckets[category]
        bucket.add(info.file_size, info.compress_size, info.compress_type)
        report.file_entries += 1
        report.uncompressed_bytes += info.file_size
        report.compressed_bytes += info.compress_size
        report.compression_types.setdefault(info.compress_type, CompressionStat()).add(
            info.file_size, info.compress_size
        )
        name_counts[name] += 1
        if category == "lib":
            location = _abi_and_file(name)
            if location is None:
                report.unclassified_library_paths.append(name)
            else:
                abi, normalized = location
                report.abis.setdefault(abi, []).append(
                    NativeFile(normalized, info.file_size, info.compress_size, info.compress_type)
                )

    report.duplicate_names = sorted(name for name, count in name_counts.items() if count > 1)
    report.duplicate_extra_entries = sum(name_counts[name] - 1 for name in report.duplicate_names)
    if report.duplicate_names:
        report.warnings.append(
            f"{report.duplicate_extra_entries} duplicate archive entries across "
            f"{len(report.duplicate_names)} names were counted individually"
        )
    if report.unclassified_library_paths:
        report.warnings.append(
            f"{len(report.unclassified_library_paths)} lib/ entries did not match lib/<abi>/<file>"
        )
    if report.file_entries == 0:
        report.warnings.append("archive contains no file entries")
    return report


def _round_percent(numerator: int, denominator: int) -> float | None:
    if denominator == 0:
        return None
    return round(100.0 * numerator / denominator, 2)


def _compression_name(compression_type: int) -> str:
    return COMPRESSION_NAMES.get(compression_type, f"method-{compression_type}")


def _category_json(report: ApkReport, category: str) -> dict[str, object]:
    bucket = report.buckets[category]
    return {
        "id": category,
        "label": CATEGORY_LABELS[category],
        "entries": bucket.entries,
        "uncompressedBytes": bucket.uncompressed_bytes,
        "compressedBytes": bucket.compressed_bytes,
        "uncompressedSharePercent": _round_percent(bucket.uncompressed_bytes, report.uncompressed_bytes),
        "compressedSharePercent": _round_percent(bucket.compressed_bytes, report.compressed_bytes),
        "compressionTypes": {
            str(compression_type): bucket.compression_types[compression_type]
            for compression_type in sorted(bucket.compression_types)
        },
    }


def report_document(report: ApkReport, comparison: dict[str, object] | None = None) -> dict[str, object]:
    """Builds the deterministic JSON document for one measured APK."""
    compression_types = [
        {
            "type": compression_type,
            "name": _compression_name(compression_type),
            "entries": stat_for_type.entries,
            "uncompressedBytes": stat_for_type.uncompressed_bytes,
            "compressedBytes": stat_for_type.compressed_bytes,
        }
        for compression_type, stat_for_type in sorted(report.compression_types.items())
    ]
    abis = [
        {
            "abi": abi,
            "entries": len(files),
            "uncompressedBytes": sum(file.uncompressed_bytes for file in files),
            "compressedBytes": sum(file.compressed_bytes for file in files),
            "files": [
                {
                    "path": file.path,
                    "uncompressedBytes": file.uncompressed_bytes,
                    "compressedBytes": file.compressed_bytes,
                    "compressionType": file.compression_type,
                }
                for file in sorted(files, key=lambda native_file: native_file.path)
            ],
        }
        for abi, files in sorted(report.abis.items())
    ]
    return {
        "tool": TOOL,
        "schemaVersion": SCHEMA_VERSION,
        "artifact": {
            "path": str(report.path),
            "bytes": report.apk_bytes,
            "sha256": report.sha256,
            "format": "zip-apk",
        },
        "totals": {
            "apkBytes": report.apk_bytes,
            "fileEntries": report.file_entries,
            "directoryEntries": report.directory_entries,
            "uncompressedBytes": report.uncompressed_bytes,
            "compressedBytes": report.compressed_bytes,
            "archiveOverheadBytes": report.apk_bytes - report.compressed_bytes,
            "compressedShareOfApkPercent": _round_percent(report.compressed_bytes, report.apk_bytes),
            "compressedToUncompressedPercent": _round_percent(
                report.compressed_bytes, report.uncompressed_bytes
            ),
        },
        "categories": [_category_json(report, category) for category in CATEGORIES],
        "compressionTypes": compression_types,
        "nativeLibraries": {
            "abis": abis,
            "unclassifiedPaths": sorted(report.unclassified_library_paths),
        },
        "duplicates": {
            "extraEntryCount": report.duplicate_extra_entries,
            "names": list(report.duplicate_names),
        },
        "warnings": list(report.warnings),
        "signing": {
            "identityValidated": False,
            "note": (
                "Signing identity, certificate and APK signature block are not inspected; "
                "no signature material or secrets are read by this report."
            ),
        },
        "comparison": comparison if comparison is not None else {"status": "not-requested"},
    }


def _delta_percent(candidate: int, baseline: int) -> float | None:
    if baseline == 0:
        return None
    return round(100.0 * (candidate - baseline) / baseline, 2)


def compare_reports(
    baseline: dict[str, object],
    candidate: dict[str, object],
    threshold_percent: float | None,
) -> dict[str, object]:
    """Compares two report documents by APK file bytes and category byte counters."""
    baseline_totals = baseline["totals"]
    candidate_totals = candidate["totals"]
    assert isinstance(baseline_totals, dict) and isinstance(candidate_totals, dict)
    baseline_bytes = int(baseline_totals["apkBytes"])
    candidate_bytes = int(candidate_totals["apkBytes"])
    delta_bytes = candidate_bytes - baseline_bytes
    delta_percent = _delta_percent(candidate_bytes, baseline_bytes)
    baseline_artifact = baseline["artifact"]
    candidate_artifact = candidate["artifact"]
    assert isinstance(baseline_artifact, dict) and isinstance(candidate_artifact, dict)

    baseline_categories = {
        str(category["id"]): category
        for category in baseline["categories"]  # type: ignore[union-attr]
    }
    candidate_categories = {
        str(category["id"]): category
        for category in candidate["categories"]  # type: ignore[union-attr]
    }
    category_rows: list[dict[str, object]] = []
    for category in CATEGORIES:
        base_row = baseline_categories[category]
        candidate_row = candidate_categories[category]
        base_uncompressed = int(base_row["uncompressedBytes"])
        candidate_uncompressed = int(candidate_row["uncompressedBytes"])
        base_compressed = int(base_row["compressedBytes"])
        candidate_compressed = int(candidate_row["compressedBytes"])
        category_rows.append(
            {
                "id": category,
                "label": CATEGORY_LABELS[category],
                "baselineUncompressedBytes": base_uncompressed,
                "candidateUncompressedBytes": candidate_uncompressed,
                "deltaUncompressedBytes": candidate_uncompressed - base_uncompressed,
                "deltaUncompressedPercent": _delta_percent(candidate_uncompressed, base_uncompressed),
                "baselineCompressedBytes": base_compressed,
                "candidateCompressedBytes": candidate_compressed,
                "deltaCompressedBytes": candidate_compressed - base_compressed,
                "deltaCompressedPercent": _delta_percent(candidate_compressed, base_compressed),
            }
        )

    if threshold_percent is None:
        threshold_exceeded: bool | None = None
        result = "report-only"
    else:
        if delta_percent is None:
            threshold_exceeded = candidate_bytes > baseline_bytes
        else:
            threshold_exceeded = 100.0 * delta_bytes / baseline_bytes > threshold_percent
        result = "exceeds-threshold" if threshold_exceeded else "within-threshold"

    return {
        "status": "measured",
        "baseline": {
            "path": baseline_artifact["path"],
            "apkBytes": baseline_bytes,
            "sha256": baseline_artifact["sha256"],
        },
        "candidate": {
            "path": candidate_artifact["path"],
            "apkBytes": candidate_bytes,
            "sha256": candidate_artifact["sha256"],
        },
        "apkBytes": {
            "baseline": baseline_bytes,
            "candidate": candidate_bytes,
            "deltaBytes": delta_bytes,
            "deltaPercent": delta_percent,
        },
        "categories": category_rows,
        "threshold": {
            "percent": threshold_percent,
            "appliesTo": "apkBytes",
        } if threshold_percent is not None else None,
        "thresholdExceeded": threshold_exceeded,
        "result": result,
        "note": "Only APK file-byte growth can fail the report; category deltas are informational.",
    }


def format_bytes(value: int) -> str:
    return f"{value:,}"


def render_table(report: ApkReport, comparison: dict[str, object] | None = None) -> str:
    """Renders the readable report; every archive-controlled name is terminal-safe."""
    lines = [
        "APK storage report",
        f"  artifact: {terminal_safe(str(report.path))}",
        f"  sha256:   {report.sha256}",
        f"  apk bytes: {format_bytes(report.apk_bytes)}",
        f"  entries:   {format_bytes(report.file_entries)} files"
        f" ({format_bytes(report.directory_entries)} directories)",
        f"  uncompressed: {format_bytes(report.uncompressed_bytes)} bytes",
        f"  compressed:   {format_bytes(report.compressed_bytes)} bytes",
        f"  archive overhead: {format_bytes(report.apk_bytes - report.compressed_bytes)} bytes",
        "",
        "  category       files      uncompressed        compressed  compression types",
    ]
    for category in CATEGORIES:
        bucket = report.buckets[category]
        methods = " ".join(
            f"{compression_type}:{bucket.compression_types[compression_type]}"
            for compression_type in sorted(bucket.compression_types)
        ) or "-"
        lines.append(
            f"  {CATEGORY_LABELS[category]:<15} {bucket.entries:>5} "
            f"{format_bytes(bucket.uncompressed_bytes):>16} "
            f"{format_bytes(bucket.compressed_bytes):>17}  {methods}"
        )
    lines.extend(["", "  compression types"])
    for compression_type, compression_stat in sorted(report.compression_types.items()):
        lines.append(
            f"    {compression_type} {_compression_name(compression_type):<10} "
            f"{compression_stat.entries:>5} files  "
            f"{format_bytes(compression_stat.uncompressed_bytes):>14} -> "
            f"{format_bytes(compression_stat.compressed_bytes):>14} bytes"
        )
    lines.extend(["", "  native libraries by ABI"])
    if not report.abis:
        lines.append("    none")
    for abi in sorted(report.abis):
        files = sorted(report.abis[abi], key=lambda native_file: native_file.path)
        uncompressed = sum(native_file.uncompressed_bytes for native_file in files)
        compressed = sum(native_file.compressed_bytes for native_file in files)
        lines.append(
            f"    {terminal_safe(abi)}: {len(files)} files, "
            f"{format_bytes(uncompressed)} -> {format_bytes(compressed)} bytes"
        )
        for native_file in files:
            lines.append(
                f"      {terminal_safe(native_file.path)} "
                f"({format_bytes(native_file.uncompressed_bytes)} -> "
                f"{format_bytes(native_file.compressed_bytes)}, "
                f"method {native_file.compression_type})"
            )
    if report.unclassified_library_paths:
        lines.append("  unclassified lib/ entries")
        for library_path in sorted(report.unclassified_library_paths)[:10]:
            lines.append(f"    {terminal_safe(library_path)}")
        remaining = len(report.unclassified_library_paths) - 10
        if remaining > 0:
            lines.append(f"    (+{remaining} more)")
    if report.duplicate_names:
        lines.append("  duplicate entry names")
        for duplicate_name in report.duplicate_names[:10]:
            lines.append(f"    {terminal_safe(duplicate_name)}")
        remaining = len(report.duplicate_names) - 10
        if remaining > 0:
            lines.append(f"    (+{remaining} more)")
    if report.warnings:
        lines.append("  warnings")
        lines.extend(f"    {terminal_safe(warning)}" for warning in report.warnings)
    lines.append("  signing: not validated (no certificate or signature-block material read)")

    if comparison is not None and comparison.get("status") == "measured":
        lines.extend(["", "  comparison"])
        apk_bytes = comparison["apkBytes"]
        assert isinstance(apk_bytes, dict)
        baseline_bytes = int(apk_bytes["baseline"])
        candidate_bytes = int(apk_bytes["candidate"])
        delta_bytes = int(apk_bytes["deltaBytes"])
        delta_percent = apk_bytes["deltaPercent"]
        percent_text = "n/a" if delta_percent is None else f"{float(delta_percent):+.2f}%"
        lines.append(
            f"    apk bytes: {format_bytes(baseline_bytes)} -> {format_bytes(candidate_bytes)} "
            f"({delta_bytes:+,} bytes, {percent_text})"
        )
        lines.append("    category        uncompressed delta       compressed delta")
        for category in comparison["categories"]:  # type: ignore[union-attr]
            assert isinstance(category, dict)
            lines.append(
                f"    {str(category['label']):<14} "
                f"{int(category['deltaUncompressedBytes']):+,} bytes "
                f"({_format_optional_percent(category['deltaUncompressedPercent'])})  "
                f"{int(category['deltaCompressedBytes']):+,} bytes "
                f"({_format_optional_percent(category['deltaCompressedPercent'])})"
            )
        threshold = comparison["threshold"]
        if threshold is None:
            lines.append("    threshold: not set (report only; no failure)")
        else:
            assert isinstance(threshold, dict)
            lines.append(
                f"    threshold: {float(threshold['percent']):+.2f}% APK growth maximum"
            )
        lines.append(f"    result: {comparison['result']}")
    return "\n".join(lines)


def _format_optional_percent(value: object) -> str:
    if value is None:
        return "n/a"
    return f"{float(value):+.2f}%"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Report APK storage by ZIP category, ABI and compression type, with an optional gate.",
    )
    parser.add_argument("apk", type=Path, help="candidate APK to measure")
    parser.add_argument("--baseline", type=Path, help="earlier APK to compare against the candidate")
    parser.add_argument(
        "--threshold-percent",
        type=float,
        metavar="PERCENT",
        help="fail when APK file-byte growth exceeds PERCENT; omit for a report-only comparison",
    )
    parser.add_argument(
        "--output",
        type=Path,
        help="write JSON to this path instead of stdout; the readable table still goes to stderr",
    )
    return parser


def _output_conflicts(output: Path, inputs: Sequence[Path]) -> bool:
    try:
        output_resolved = output.expanduser().resolve()
        return any(output_resolved == path.expanduser().resolve() for path in inputs)
    except OSError:
        return False


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.threshold_percent is not None:
        if not math.isfinite(args.threshold_percent):
            print("error: --threshold-percent must be a finite number", file=sys.stderr)
            return EXIT_USAGE
        if args.baseline is None:
            print("error: --threshold-percent requires --baseline", file=sys.stderr)
            return EXIT_USAGE
    if args.output is not None:
        inputs = [args.apk] + ([args.baseline] if args.baseline is not None else [])
        if _output_conflicts(args.output, inputs):
            print("error: --output must not overwrite an input APK", file=sys.stderr)
            return EXIT_USAGE

    try:
        candidate = inspect_apk(args.apk)
        comparison = None
        if args.baseline is not None:
            baseline = inspect_apk(args.baseline)
            comparison = compare_reports(
                report_document(baseline),
                report_document(candidate),
                args.threshold_percent,
            )
    except ReportError as error:
        print(f"error: {terminal_safe(str(error))}", file=sys.stderr)
        return EXIT_USAGE

    document = report_document(candidate, comparison)
    text = json.dumps(document, indent=2, ensure_ascii=True) + "\n"
    if args.output is not None:
        try:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(text, encoding="utf-8")
        except OSError as error:
            reason = error.strerror or "the report cannot be written"
            print(f"error: cannot write report: {terminal_safe(reason)}", file=sys.stderr)
            return EXIT_USAGE
    else:
        sys.stdout.write(text)
    sys.stderr.write(render_table(candidate, comparison) + "\n")

    if comparison is not None and comparison["thresholdExceeded"] is True:
        return EXIT_THRESHOLD
    return EXIT_OK


if __name__ == "__main__":
    raise SystemExit(main())
