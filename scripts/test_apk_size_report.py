#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (c) 2026 vossgraves
"""Focused regression coverage for the reproducible APK storage report."""

import io
import json
import os
import tempfile
import unittest
import warnings
import zipfile
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import patch

from scripts.apk_size_report import (
    CATEGORIES,
    ReportError,
    classify_entry,
    compare_reports,
    inspect_apk,
    main,
    render_table,
    report_document,
    terminal_safe,
)


class ApkSizeReportTest(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.root = Path(self.temporary_directory.name)

    def apk_path(self, name="app.apk"):
        return self.root / name

    def write_apk(self, name, entries):
        path = self.apk_path(name)
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")
            with zipfile.ZipFile(path, "w") as archive:
                for entry_name, payload, compression in entries:
                    archive.writestr(entry_name, payload, compress_type=compression)
        return path

    def fixture_entries(self):
        return [
            ("classes.dex", b"d" * 100, zipfile.ZIP_STORED),
            ("classes2.dex", b"e" * 50, zipfile.ZIP_DEFLATED),
            ("lib/x86_64/libfixture.so", b"m" * 30, zipfile.ZIP_DEFLATED),
            ("lib/arm64-v8a/zlibfixture.so", b"n" * 20, zipfile.ZIP_STORED),
            ("lib/arm64-v8a/alibfixture.so", b"o" * 10, zipfile.ZIP_STORED),
            ("assets/data.bin", b"a" * 10, zipfile.ZIP_STORED),
            ("res/values/strings.xml", b"r" * 20, zipfile.ZIP_DEFLATED),
            ("resources.arsc", b"x" * 15, zipfile.ZIP_STORED),
            ("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\n", zipfile.ZIP_DEFLATED),
            ("kernel.bin", b"k" * 5, zipfile.ZIP_STORED),
        ]

    def test_valid_apk_category_abi_and_compression_counters(self):
        path = self.write_apk("valid.apk", self.fixture_entries())
        report = inspect_apk(path)
        document = report_document(report)

        with zipfile.ZipFile(path) as archive:
            infos = [info for info in archive.infolist() if not info.is_dir()]
            expected_compressed = sum(info.compress_size for info in infos)
            expected_uncompressed = sum(info.file_size for info in infos)
            expected_dex_compressed = sum(
                info.compress_size for info in infos if info.filename in ("classes.dex", "classes2.dex")
            )
            expected_stored = sum(1 for info in infos if info.compress_type == zipfile.ZIP_STORED)
            expected_deflated = sum(1 for info in infos if info.compress_type == zipfile.ZIP_DEFLATED)

        self.assertEqual(document["artifact"]["bytes"], path.stat().st_size)
        self.assertEqual(document["totals"]["apkBytes"], path.stat().st_size)
        self.assertEqual(document["totals"]["fileEntries"], 10)
        self.assertEqual(document["totals"]["directoryEntries"], 0)
        self.assertEqual(document["totals"]["uncompressedBytes"], expected_uncompressed)
        self.assertEqual(document["totals"]["compressedBytes"], expected_compressed)
        self.assertEqual(
            document["totals"]["archiveOverheadBytes"], path.stat().st_size - expected_compressed
        )
        self.assertEqual([category["id"] for category in document["categories"]], list(CATEGORIES))

        by_id = {category["id"]: category for category in document["categories"]}
        self.assertEqual(by_id["dex"]["entries"], 2)
        self.assertEqual(by_id["dex"]["uncompressedBytes"], 150)
        self.assertEqual(by_id["dex"]["compressedBytes"], expected_dex_compressed)
        self.assertEqual(by_id["resourcesArsc"]["uncompressedBytes"], 15)
        self.assertEqual(by_id["metaInf"]["entries"], 1)
        self.assertEqual(by_id["other"]["uncompressedBytes"], 5)
        self.assertEqual(by_id["assets"]["entries"], 1)

        self.assertEqual(
            [entry["type"] for entry in document["compressionTypes"]], [zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED]
        )
        self.assertEqual(
            [entry["entries"] for entry in document["compressionTypes"]], [expected_stored, expected_deflated]
        )
        self.assertEqual(report.duplicate_names, [])

    def test_native_library_files_are_grouped_by_sorted_abi_and_path(self):
        path = self.write_apk("native.apk", self.fixture_entries())
        document = report_document(inspect_apk(path))
        libraries = document["nativeLibraries"]

        self.assertEqual([abi["abi"] for abi in libraries["abis"]], ["arm64-v8a", "x86_64"])
        self.assertEqual(
            [native_file["path"] for native_file in libraries["abis"][0]["files"]],
            ["lib/arm64-v8a/alibfixture.so", "lib/arm64-v8a/zlibfixture.so"],
        )
        self.assertEqual(libraries["abis"][0]["entries"], 2)
        self.assertEqual(libraries["abis"][0]["uncompressedBytes"], 30)
        self.assertEqual(libraries["unclassifiedPaths"], [])

    def test_report_is_deterministic_and_keeps_json_separate_from_table(self):
        path = self.write_apk("deterministic.apk", self.fixture_entries())
        first = report_document(inspect_apk(path))
        second = report_document(inspect_apk(path))
        self.assertEqual(
            json.dumps(first, indent=2, ensure_ascii=True),
            json.dumps(second, indent=2, ensure_ascii=True),
        )
        self.assertEqual(render_table(inspect_apk(path)), render_table(inspect_apk(path)))
        self.assertNotIn("generatedAtUtc", first)

        output = io.StringIO()
        errors = io.StringIO()
        with redirect_stdout(output), redirect_stderr(errors):
            exit_code = main([str(path)])
        self.assertEqual(exit_code, 0)
        self.assertEqual(json.loads(output.getvalue())["artifact"]["path"], str(path))
        self.assertIn("APK storage report", errors.getvalue())
        self.assertNotIn("APK storage report", output.getvalue())

    def test_duplicate_entries_are_counted_individually_without_failure(self):
        entries = [
            ("classes.dex", b"a" * 100, zipfile.ZIP_STORED),
            ("classes.dex", b"b" * 50, zipfile.ZIP_STORED),
            ("assets/duplicate.bin", b"c" * 10, zipfile.ZIP_STORED),
        ]
        path = self.write_apk("duplicate.apk", entries)
        report = inspect_apk(path)
        document = report_document(report)

        self.assertEqual(document["totals"]["fileEntries"], 3)
        self.assertEqual(document["totals"]["uncompressedBytes"], 160)
        self.assertEqual(document["duplicates"]["extraEntryCount"], 1)
        self.assertEqual(document["duplicates"]["names"], ["classes.dex"])
        self.assertEqual(document["categories"][0]["entries"], 2)
        self.assertEqual(document["categories"][0]["uncompressedBytes"], 150)
        self.assertTrue(any("duplicate" in warning for warning in document["warnings"]))
        self.assertIn("duplicate entry names", render_table(report))

    def test_measurement_reads_only_metadata_not_entry_payloads(self):
        path = self.apk_path("compressed.apk")
        payload = b"\0" * (8 * 1024 * 1024)
        with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("assets/zeros.bin", payload)

        with patch.object(zipfile.ZipFile, "read", side_effect=AssertionError("entry payload read")), patch.object(
            zipfile.ZipFile, "open", side_effect=AssertionError("entry stream opened")
        ):
            document = report_document(inspect_apk(path))

        asset_category = next(category for category in document["categories"] if category["id"] == "assets")
        self.assertEqual(asset_category["uncompressedBytes"], len(payload))
        self.assertLess(document["totals"]["compressedBytes"], len(payload) // 10)

    def test_hostile_member_names_are_escaped_in_the_readable_table(self):
        unsafe_name = "lib/arm64-v8a/\x1b]0;owned\x07\n\u202e.so"
        self.assertTrue(terminal_safe(unsafe_name).isascii())
        self.assertNotIn("\x1b", terminal_safe(unsafe_name))
        self.assertNotIn("\n", terminal_safe(unsafe_name))
        self.assertIn("\\x1b", terminal_safe(unsafe_name))
        self.assertIn("\\u202e", terminal_safe(unsafe_name))

        path = self.write_apk(
            "unsafe.apk",
            [
                ("lib/arm64-v8a/libfixture.so", b"n" * 20, zipfile.ZIP_STORED),
                (unsafe_name, b"u" * 10, zipfile.ZIP_STORED),
            ],
        )
        table = render_table(inspect_apk(path))
        self.assertNotIn("\x1b", table)
        self.assertNotIn("\x07", table)
        self.assertNotIn("\u202e", table)
        self.assertIn("\\x1b]0;owned", table)

    def test_malformed_zip_reports_an_input_error_without_a_traceback(self):
        path = self.apk_path("broken.apk")
        path.write_bytes(b"PK\x03\x04truncated central directory")
        with self.assertRaises(ReportError):
            inspect_apk(path)

        output = self.apk_path("broken-report.json")
        errors = io.StringIO()
        with redirect_stderr(errors):
            exit_code = main([str(path), "--output", str(output)])
        self.assertEqual(exit_code, 2)
        self.assertFalse(output.exists())
        self.assertIn("not a readable ZIP/APK archive", errors.getvalue())
        self.assertNotIn("Traceback", errors.getvalue())

    def test_missing_and_directory_paths_are_rejected(self):
        with self.assertRaises(ReportError):
            inspect_apk(self.apk_path("missing.apk"))
        with self.assertRaises(ReportError):
            inspect_apk(self.root)

    def test_comparison_reports_growth_without_failing_by_default(self):
        baseline_path = self.write_apk(
            "baseline.apk", [("classes.dex", b"a" * 100, zipfile.ZIP_STORED)]
        )
        candidate_path = self.write_apk(
            "candidate.apk",
            [
                ("classes.dex", b"a" * 100, zipfile.ZIP_STORED),
                ("assets/extra.bin", b"b" * 1000, zipfile.ZIP_STORED),
            ],
        )
        baseline = report_document(inspect_apk(baseline_path))
        candidate = report_document(inspect_apk(candidate_path))
        comparison = compare_reports(baseline, candidate, None)

        self.assertEqual(comparison["status"], "measured")
        self.assertEqual(comparison["result"], "report-only")
        self.assertIsNone(comparison["thresholdExceeded"])
        self.assertIsNone(comparison["threshold"])
        self.assertGreater(comparison["apkBytes"]["deltaBytes"], 0)
        self.assertGreater(comparison["apkBytes"]["deltaPercent"], 0)
        self.assertEqual(comparison["categories"][0]["deltaUncompressedBytes"], 0)

        exceeded = compare_reports(baseline, candidate, 0.5)
        self.assertTrue(exceeded["thresholdExceeded"])
        self.assertEqual(exceeded["result"], "exceeds-threshold")
        self.assertEqual(exceeded["threshold"]["percent"], 0.5)

        within = compare_reports(baseline, candidate, 10000.0)
        self.assertFalse(within["thresholdExceeded"])
        self.assertEqual(within["result"], "within-threshold")

    def test_cli_threshold_gate_exits_one_only_when_requested(self):
        baseline_path = self.write_apk(
            "baseline.apk", [("classes.dex", b"a" * 100, zipfile.ZIP_STORED)]
        )
        candidate_path = self.write_apk(
            "candidate.apk",
            [
                ("classes.dex", b"a" * 100, zipfile.ZIP_STORED),
                ("assets/extra.bin", b"b" * 1000, zipfile.ZIP_STORED),
            ],
        )
        report_path = self.apk_path("comparison.json")
        errors = io.StringIO()
        with redirect_stderr(errors):
            report_only_exit = main(
                [str(candidate_path), "--baseline", str(baseline_path), "--output", str(report_path)]
            )
        self.assertEqual(report_only_exit, 0)
        report_only = json.loads(report_path.read_text(encoding="utf-8"))
        self.assertEqual(report_only["comparison"]["result"], "report-only")

        with redirect_stderr(errors):
            threshold_exit = main(
                [
                    str(candidate_path),
                    "--baseline",
                    str(baseline_path),
                    "--threshold-percent",
                    "0",
                    "--output",
                    str(report_path),
                ]
            )
        self.assertEqual(threshold_exit, 1)
        threshold_report = json.loads(report_path.read_text(encoding="utf-8"))
        self.assertTrue(threshold_report["comparison"]["thresholdExceeded"])
        self.assertIn("result: exceeds-threshold", errors.getvalue())

    def test_threshold_requires_a_baseline_and_finite_percent(self):
        path = self.write_apk("single.apk", self.fixture_entries())
        with redirect_stderr(io.StringIO()):
            missing_baseline = main([str(path), "--threshold-percent", "1"])
            non_finite = main([str(path), "--threshold-percent", "nan"])
        self.assertEqual(missing_baseline, 2)
        self.assertEqual(non_finite, 2)

    def test_output_cannot_overwrite_the_measured_apk(self):
        path = self.write_apk("protected.apk", self.fixture_entries())
        before = path.read_bytes()
        with redirect_stderr(io.StringIO()):
            exit_code = main([str(path), "--output", str(path)])
        self.assertEqual(exit_code, 2)
        self.assertEqual(path.read_bytes(), before)

    def test_output_rejects_hardlinks_to_candidate_and_baseline(self):
        candidate = self.write_apk("candidate.apk", self.fixture_entries())
        baseline = self.write_apk("baseline.apk", self.fixture_entries())
        candidate_before = candidate.read_bytes()
        baseline_before = baseline.read_bytes()

        for role, target in (("candidate", candidate), ("baseline", baseline)):
            with self.subTest(role=role):
                output = self.apk_path(f"{role}-hardlink.json")
                os.link(target, output)
                arguments = [str(candidate), "--output", str(output)]
                if role == "baseline":
                    arguments[1:1] = ["--baseline", str(baseline)]
                errors = io.StringIO()
                with redirect_stderr(errors):
                    exit_code = main(arguments)
                self.assertEqual(exit_code, 2)
                self.assertIn("must not overwrite", errors.getvalue())
                self.assertEqual(candidate.read_bytes(), candidate_before)
                self.assertEqual(baseline.read_bytes(), baseline_before)
                self.assertEqual(output.read_bytes(), candidate_before if role == "candidate" else baseline_before)

        fresh_output = self.apk_path("fresh-report.json")
        with redirect_stderr(io.StringIO()):
            fresh_exit = main([str(candidate), "--output", str(fresh_output)])
        self.assertEqual(fresh_exit, 0)
        fresh_report = json.loads(fresh_output.read_text(encoding="utf-8"))
        self.assertEqual(fresh_report["artifact"]["bytes"], candidate.stat().st_size)
        self.assertEqual(candidate.read_bytes(), candidate_before)
        self.assertEqual(baseline.read_bytes(), baseline_before)

    def test_classifier_keeps_dex_and_assets_distinct(self):
        self.assertEqual(classify_entry("classes.dex"), "dex")
        self.assertEqual(classify_entry("classes12.dex"), "dex")
        self.assertEqual(classify_entry("classes/dex.dex"), "other")
        self.assertEqual(classify_entry("assets/helper.dex"), "assets")
        self.assertEqual(classify_entry("resources.arsc"), "resourcesArsc")
        self.assertEqual(classify_entry("META-INF/MANIFEST.MF"), "metaInf")
        self.assertEqual(classify_entry("res/layout/main.xml"), "res")
        self.assertEqual(classify_entry("lib/arm64-v8a/libfixture.so"), "lib")


if __name__ == "__main__":
    unittest.main()
