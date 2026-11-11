#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Extract unchanged literal inputs from retained Kotlin tests; never invent expected results."""
import html
import json
from pathlib import Path
import re
import textwrap

ROOT = Path(__file__).resolve().parent.parent
DEST = ROOT / "native/core/fixtures/ttml-inputs.json"


def corpus():
    result = []
    tests = ROOT / "shared-domain/src/commonTest/kotlin/moe/rukamori/archivetune/shared/domain/lyrics"
    for path in sorted(tests.rglob("*.kt")):
        source = path.read_text()
        literals = re.finditer(r'"""([\s\S]*?)"""|"((?:\\.|[^"\\\n])*)"', source)
        for index, match in enumerate(literals):
            if match[1] is not None:
                raw = textwrap.dedent(match[1]).strip() if source[match.end():].startswith(".trimIndent()") else match[1]
            else:
                try:
                    raw = json.loads('"' + match[2] + '"')
                except ValueError:
                    continue
            if not any(token in raw for token in ("<tt", "<t:tt", "&lt;tt", "<!DOCTYPE")):
                continue
            if "$" in raw:
                continue  # Runtime/rebuild inputs are covered by the retained suites and generated bounds below.
            xml = raw
            if raw.startswith("{"):
                try:
                    xml = json.loads(raw).get("lyrics", raw)
                except ValueError:
                    pass
            if "&lt;tt" in xml:
                xml = html.unescape(xml)
            result.append({"id": f"{path.relative_to(tests)}:{index}", "raw": raw, "xml": xml})
    extras = {
        "minimal": '<tt><body><p begin="1s">Hello</p></body></tt>',
        "nested-duplicate": '<tt><div><p begin="2s">A</p><div><p begin="1s">B</p></div></div></tt>',
        "metadata-after-body": '<tt><p key="x" begin="1s">A</p><translation><text for="x">B</text></translation><songwriter name="Writer"/></tt>',
        "latin-priority": '<tt><transliteration lang="ja"><text for="a">かな</text></transliteration><transliteration lang="ja-Latn"><text for="a"><span>ka</span> <span>na</span></text></transliteration><p key="a" begin="1s">仮名</p></tt>',
        "duplicate-keys": '<tt><translation><text for="a">One</text><text for="a">Two</text></translation><p key="a" begin="1s">A</p></tt>',
        "sorted-attributes": '<tt z:frameRate="50" a:frameRate="25"><p z:agent="v2" a:agent="v1" begin="50f">A</p></tt>',
        "cdata-comments-pi": '<tt><p begin="1s"><span begin="1s" end="2s">A<!--c--><![CDATA[B]]><?pi data?>C</span><span begin="2s" end="3s"><b><![CDATA[D]]></b></span></p><p begin="4s"><![CDATA[ignored]]></p></tt>',
        "bom-crlf": '\ufeff<tt>\r\n<p begin="1s">A\rB</p></tt>',
        "odd-timing": '<tt frameRate="30" frameRateMultiplier="1000 1001" tickRate="1000"><p begin="00;00;01;15" dur="500ms"><span begin="0s" dur="1000t">A</span></p><p begin="1.234999">B</p><p begin="bad">C</p></tt>',
        "negative-interval": '<tt><p begin="-10" end="-9"><span begin="-10" end="-9">A</span></p><songwriter>Ada</songwriter></tt>',
        "xml-char-refs": '<tt><p begin="1s">&#127908;&#x20000;&#x10FFFF;&amp;&lt;</p></tt>',
        "unknown-entity": '<tt><p begin="0">&xxe;</p></tt>',
        "duplicate-attribute": '<tt><p begin="0" begin="1">A</p></tt>',
        "second-root": '<tt/><tt/>',
        "bad-comment": '<tt><!-- a--b --></tt>',
        "bad-text-close": '<tt><p begin="0">a]]>b</p></tt>',
        "invalid-scalar": '<tt><p begin="0">&#xD800;</p></tt>',
        "unicode": '<tt><p begin="1s"><span begin="1s" end="2s">می\u200cروم👩\u200d🎤\u200b𠀀e\u0301</span></p></tt>',
    }
    for name, xml in extras.items():
        result.append({"id": "added/" + name, "raw": xml, "xml": xml})
    return result


if __name__ == "__main__":
    import sys
    text = json.dumps(corpus(), ensure_ascii=False, indent=2) + "\n"
    if "--check" in sys.argv:
        if DEST.read_text() != text:
            sys.exit("TTML inputs drifted: run scripts/extract_ttml_fixtures.py")
    else:
        DEST.parent.mkdir(parents=True, exist_ok=True)
        DEST.write_text(text)
        print(f"Extracted {len(corpus())} inputs")
