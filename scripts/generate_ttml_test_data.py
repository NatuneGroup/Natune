#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Embed the same fixtures/goldens for Kotlin/Native tests without platform file I/O."""
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent.parent
inputs = json.loads((ROOT / "native/core/fixtures/ttml-inputs.json").read_text())
goldens = json.loads((ROOT / "native/core/fixtures/ttml-goldens.json").read_text())
destination = Path(sys.argv[1]) / "TtmlFixtureData.kt"
destination.parent.mkdir(parents=True, exist_ok=True)


def literal(value):
    return json.dumps(value, ensure_ascii=True).replace("$", "\\$")


lines = ["package moe.rukamori.archivetune.shared.core", "",
         "// Generated from the shared GPL Natune inputs and retained-Kotlin goldens. Do not edit.",
         "internal val ttmlFixtureCases: List<Triple<String, String, String>> = listOf("]
for case in inputs:
    expected = json.dumps(goldens[case["id"]], ensure_ascii=True, separators=(",", ":"))
    lines.append("    Triple(" + ", ".join(literal(s) for s in (case["id"], case["raw"], expected)) + "),")
lines.append(")")
destination.write_text("\n".join(lines) + "\n")
