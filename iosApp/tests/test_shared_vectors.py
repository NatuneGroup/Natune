"""Portable vector contracts. Pixel rendering remains a simulator/device check."""
from pathlib import Path
import math
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
DRAWABLES = ROOT / "shared-ui/src/commonMain/composeResources/drawable"
A = "{http://schemas.android.com/apk/res/android}"
TOKEN = re.compile(r"[MmZzLlHhVvCcSsQqTtAa]|[-+]?(?:[0-9]*\.[0-9]+|[0-9]+\.?[0-9]*)(?:[eE][-+]?[0-9]+)?")
ARITY = dict(m=2, z=0, l=2, h=1, v=1, c=6, s=4, q=4, t=2, a=7)


class SharedVectorTest(unittest.TestCase):
    def test_every_vector_has_portable_colors_dimensions_and_paths(self):
        for path in DRAWABLES.glob("*.xml"):
            with self.subTest(vector=path.name):
                vector = ET.parse(path).getroot()
                self.assertEqual("vector", vector.tag)
                for dimension in ("viewportWidth", "viewportHeight"):
                    self.assertGreater(float(vector.attrib[A + dimension]), 0)
                for node in vector.iter():
                    self.assertIn(node.tag, ("vector", "path", "group", "clip-path"))
                    self.assertFalse(any(v.startswith(("@", "?")) for v in node.attrib.values()))
                    if node.tag == "path":
                        self.assertRegex(node.attrib[A + "fillColor"], r"^#[0-9a-fA-F]{6,8}$")
                        self.check_path(node.attrib[A + "pathData"])

    def check_path(self, data):
        self.assertFalse(TOKEN.sub("", data).strip(" ,\n\t\r"))
        tokens = TOKEN.findall(data)
        self.assertIn(tokens[0], ("M", "m"))
        i = 0
        while i < len(tokens):
            command = tokens[i].lower()
            self.assertIn(command, ARITY)
            i += 1
            values = []
            while i < len(tokens) and tokens[i].lower() not in ARITY:
                values.append(float(tokens[i]))
                i += 1
            self.assertTrue(all(math.isfinite(x) for x in values))
            arity = ARITY[command]
            if arity == 0:
                self.assertEqual([], values)
            else:
                self.assertGreater(len(values), 0)
                self.assertEqual(0, len(values) % arity)
                if command == "a":
                    for j in range(0, len(values), 7):
                        self.assertGreaterEqual(values[j], 0)
                        self.assertGreaterEqual(values[j + 1], 0)
                        self.assertIn(values[j + 3], (0, 1))
                        self.assertIn(values[j + 4], (0, 1))

    def test_player_assets_are_android_paths_not_placeholder_substitutes(self):
        for name in ("solar_play_linear", "solar_pause_linear", "solar_skip_previous_linear",
                     "solar_skip_next_linear", "solar_heart_bold", "solar_heart_linear"):
            source = ET.parse(ROOT / "app/src/main/res/drawable" / (name + ".xml"))
            shared = ET.parse(DRAWABLES / (name + ".xml"))
            self.assertEqual([p.attrib[A + "pathData"] for p in source.iter("path")],
                             [p.attrib[A + "pathData"] for p in shared.iter("path")])
