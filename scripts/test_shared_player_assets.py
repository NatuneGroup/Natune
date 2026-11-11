"""Shared transport vectors must retain the Android reference geometry."""

from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "app/src/main/res/drawable"
SHARED = ROOT / "shared-ui/src/commonMain/composeResources/drawable"
ANDROID_SOURCE_NAMES = {
    "reference_cinematic_repeat.xml": "player_repeat.xml",
    "reference_cinematic_repeat_one.xml": "player_repeat_one.xml",
    "reference_cinematic_shuffle.xml": "player_shuffle.xml",
    "reference_lyrics_fast_forward.xml": "player_fast_forward.xml",
    "reference_queue_lock_open.xml": "lock_open.xml",
    "reference_queue_more_vert.xml": "more_vert.xml",
    "reference_queue_repeat_one.xml": "repeat_one_on.xml",
}


def vector_geometry(element):
    return element.tag, sorted(element.attrib.items()), tuple(vector_geometry(child) for child in element)


class SharedPlayerAssetsTest(unittest.TestCase):
    def test_reference_assets_keep_android_paths_groups_and_dimensions(self):
        assets = list(SHARED.glob("reference_*.xml"))
        self.assertGreaterEqual(len(assets), 25)
        for asset in assets:
            with self.subTest(asset=asset.name):
                source_name = ANDROID_SOURCE_NAMES.get(asset.name, asset.name.removeprefix("reference_"))
                source = (ANDROID / source_name).read_text()
                source = source.replace("@android:color/white", "#FFFFFFFF").replace('dip"', 'dp"')
                original = ET.fromstring(source)
                if asset.name == "reference_queue_lock_open.xml":
                    # Compose needs an explicit transparent fill for Android's stroke-only lock paths.
                    for path in original.iter("path"):
                        path.set("{http://schemas.android.com/apk/res/android}fillColor", "#00000000")
                self.assertEqual(vector_geometry(original), vector_geometry(ET.parse(asset).getroot()))

    def test_navigation_assets_keep_android_geometry(self):
        for name in ("home_filled", "home_outlined", "library_filled", "library_outlined", "search"):
            with self.subTest(asset=name):
                key = "{http://schemas.android.com/apk/res/android}pathData"
                original = ET.parse(ANDROID / f"{name}.xml")
                shared = ET.parse(SHARED / f"m4_icon_{name}.xml")
                self.assertEqual([p.get(key) for p in original.iter("path")], [p.get(key) for p in shared.iter("path")])


if __name__ == "__main__":
    unittest.main()
