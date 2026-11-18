import json
import tempfile
import unittest
from pathlib import Path

import sync_shared_resources as sync


def write(root: Path, path: str, text: str) -> None:
    (root / path).parent.mkdir(parents=True, exist_ok=True)
    (root / path).write_text(text, encoding="utf-8")


class SyncSharedResourcesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        write(self.root, "app/src/main/res/values/strings.xml", """<resources>
    <string name="play">Play</string>
    <string name="progress">%1$d%% done</string>
    <string name="literal">100%% sure</string>
    <string name="title">App title</string>
    <plurals name="songs"><item quantity="one">%d song</item><item quantity="other">%d songs</item></plurals>
</resources>""")
        write(self.root, "app/src/main/res/values-ja/strings.xml", '<resources><string name="play">再生</string></resources>')
        write(self.root, "app/src/main/res/values-night/colors.xml", '<resources><color name="x">#000</color></resources>')
        write(self.root, "app/src/main/res/drawable/ic_play.xml", "<vector/>")
        write(self.root, "shared-ui/src/commonMain/composeResources/values/strings.xml",
              '<resources><string name="title">Shared title</string></resources>')

    def tearDown(self):
        self.tmp.cleanup()

    def use(self, code: str) -> None:
        write(self.root, "shared-ui/src/commonMain/kotlin/Moved.kt", code)

    def generated(self, locale: str = "values") -> str:
        return (self.root / f"shared-ui/src/commonMain/composeResources/{locale}/app_strings.xml").read_text()

    def test_copies_referenced_strings_with_translations_and_drawables(self):
        self.use("Res.string.play; Res.plurals.songs; Res.drawable.ic_play")
        self.assertEqual([], sync.write(self.root))
        self.assertIn('<string name="play">Play</string>', self.generated())
        self.assertIn('<plurals name="songs">', self.generated())
        self.assertIn('<string name="play">再生</string>', self.generated("values-ja"))
        self.assertTrue((self.root / "shared-ui/src/commonMain/composeResources/drawable/ic_play.xml").is_file())
        self.assertEqual(["shared-ui/src/commonMain/composeResources/drawable/ic_play.xml"],
                         json.loads((self.root / "shared-ui/app-resources.json").read_text()))
        self.assertEqual([], sync.problems(self.root))

    def test_percent_escape_unfolds_only_for_formatted_strings(self):
        self.use("Res.string.progress; Res.string.literal")
        sync.write(self.root)
        self.assertIn("%1$d% done", self.generated())
        self.assertIn("100%% sure", self.generated())

    def test_colliding_name_is_generated_under_the_app_prefix(self):
        self.use("Res.string.title; Res.string.app_title")
        sync.write(self.root)
        self.assertIn('<string name="app_title">App title</string>', self.generated())
        self.assertNotIn('name="title"', self.generated())

    def test_check_reports_stale_missing_and_orphaned_output(self):
        self.use("Res.string.play; Res.string.nowhere")
        self.assertIn("Res.string.nowhere is defined neither in :shared-ui nor in app/src/main/res", sync.write(self.root))
        self.use("Res.string.play")
        self.assertEqual([], sync.problems(self.root))
        write(self.root, "app/src/main/res/values/strings.xml", '<resources><string name="play">Start</string></resources>')
        self.assertTrue(any("stale" in p for p in sync.problems(self.root)))
        self.use("")
        sync.write(self.root)
        self.assertFalse((self.root / "shared-ui/src/commonMain/composeResources/values/app_strings.xml").exists())
        self.assertEqual([], sync.problems(self.root))


if __name__ == "__main__":
    unittest.main()
