"""Shared transport vectors must retain the Android reference geometry."""

from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "app/src/main/res/drawable"
SHARED = ROOT / "shared-ui/src/commonMain/composeResources/drawable"


class SharedPlayerAssetsTest(unittest.TestCase):
    def test_reference_assets_keep_android_paths_groups_and_dimensions(self):
        assets = list(SHARED.glob("reference_*.xml"))
        self.assertGreaterEqual(len(assets), 25)
        for asset in assets:
            with self.subTest(asset=asset.name):
                source = (ANDROID / asset.name.removeprefix("reference_")).read_text()
                source = source.replace("@android:color/white", "#FFFFFFFF").replace('dip"', 'dp"')
                self.assertEqual(ET.tostring(ET.fromstring(source)), ET.tostring(ET.parse(asset).getroot()))

    def test_navigation_assets_keep_android_geometry(self):
        for name in ("home_filled", "home_outlined", "library_filled", "library_outlined", "search"):
            with self.subTest(asset=name):
                key = "{http://schemas.android.com/apk/res/android}pathData"
                original = ET.parse(ANDROID / f"{name}.xml")
                shared = ET.parse(SHARED / f"m4_icon_{name}.xml")
                self.assertEqual([p.get(key) for p in original.iter("path")], [p.get(key) for p in shared.iter("path")])


if __name__ == "__main__":
    unittest.main()
