# SPDX-License-Identifier: GPL-3.0-only
# Copyright (c) 2026 vossgraves
from collections import Counter
from pathlib import Path
import re
import tempfile
import unittest
import subprocess
from unittest.mock import patch

from scripts.check_code_quality import (
    ROOT, FORMAT_ROOTS, LEGACY_SOURCE_ROOTS, PROFILE_LEGACY_ANDROID, PROFILE_STRUCTURAL,
    SOURCE_ROOTS, cpd_findings, detekt_findings, extract_sources, formatter_patch,
    formatting_regressions, parse_rename_status, prepare_base_formatter, prepare_formatter,
    profile_for_classpath, regressions, remap_duplicates, remap_findings, resolve_base,
    source_roots,
)


class CodeQualityTest(unittest.TestCase):
    def report(self, text):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name) / "report.xml"
        path.write_text(text)
        return path

    def test_line_movement_does_not_create_a_new_detekt_violation(self):
        report = '<checkstyle><file name="app/A.kt"><error line="10" source="detekt.LongMethod" message="Function resolve is too long (90). Allowed: 80."/></file></checkstyle>'
        before, weights = detekt_findings(self.report(report), Path("/repo"))
        after, new_weights = detekt_findings(self.report(report.replace('line="10"', 'line="30"')), Path("/repo"))
        self.assertEqual([], regressions(before, after, weights, new_weights))

    def test_existing_complexity_may_shrink_but_cannot_grow(self):
        key = ("app/A.kt", "CyclomaticComplexMethod", "resolve complexity (#)")
        findings = Counter({key: 1})
        self.assertEqual([], regressions(findings, findings, {key: 20}, {key: 19}))
        self.assertEqual([key], regressions(findings, findings, {key: 20}, {key: 21}))

    def test_multiple_identical_named_violations_are_not_collapsed(self):
        key = ("app/A.kt", "UnusedPrivateProperty", "unused cache")
        self.assertEqual([key], regressions(Counter({key: 1}), Counter({key: 2}), {}, {}))

    def test_duplicate_fingerprints_ignore_temporary_roots_and_line_offsets(self):
        body = '<pmd-cpd><duplication tokens="120"><file path="/base/app/A.kt" line="1"/><file path="/base/app/B.kt" line="20"/><codefragment>fun duplicate() { return 1 }</codefragment></duplication></pmd-cpd>'
        before = cpd_findings(self.report(body), Path("/base"))
        after = cpd_findings(self.report(body.replace("/base/", "/candidate/").replace('line="1"', 'line="7"')), Path("/candidate"))
        self.assertEqual(before, after)

    def test_incomplete_analyzer_reports_fail_closed(self):
        with self.assertRaises(ValueError):
            cpd_findings(self.report('<pmd-cpd><error filename="A.kt"/></pmd-cpd>'), Path("/repo"))
        with self.assertRaises(ValueError):
            detekt_findings(self.report("<html/>"), Path("/repo"))

    def test_formatter_copies_modified_and_untracked_sources_but_not_unchanged_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "repo"
            root.mkdir()
            def git(*args):
                return subprocess.check_output(["git", "-C", str(root), *args], stderr=subprocess.DEVNULL).decode().strip()
            git("init", "--quiet")
            sources = root / "app/src/main/kotlin"
            sources.mkdir(parents=True)
            for name in ("Changed.kt", "Same.kt", "Deleted.kt"):
                (sources / name).write_text(f"class {name[:-3]}\n")
            git("add", ".")
            revision = git("write-tree")
            (sources / "Changed.kt").write_text("class Changed(val value: Int)\n")
            (sources / "Deleted.kt").unlink()
            (sources / "New.kt").write_text("class New\n")
            destination = Path(temporary) / "formatter"
            self.assertEqual(2, prepare_formatter(root, revision, destination))
            self.assertEqual(
                ["Changed.kt", "New.kt"],
                sorted(path.name for path in destination.rglob("*.kt")),
            )
            self.assertEqual(
                (sources / "Changed.kt").read_bytes(),
                (destination / "app/src/main/kotlin/Changed.kt").read_bytes(),
            )

    def test_formatter_covers_the_selected_gms_flavor_source_root(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "repo"
            root.mkdir()
            def git(*args):
                return subprocess.check_output(["git", "-C", str(root), *args], stderr=subprocess.DEVNULL).decode().strip()
            git("init", "--quiet")
            flavor = root / "app/src/gms/kotlin"
            flavor.mkdir(parents=True)
            (flavor / "Gms.kt").write_text("class Gms\n")
            git("add", ".")
            revision = git("write-tree")
            (flavor / "Gms.kt").write_text("class Gms(val source: String)\n")
            destination = Path(temporary) / "formatter"
            self.assertEqual(1, prepare_formatter(root, revision, destination))
            self.assertEqual(
                (flavor / "Gms.kt").read_bytes(),
                (destination / "app/src/gms/kotlin/Gms.kt").read_bytes(),
            )

    def test_gradle_analysis_patterns_match_the_python_source_roots(self):
        text = (ROOT / "quality/build.gradle.kts").read_text(encoding="utf-8")
        legacy_block = text.split("val legacySourcePatterns = listOf(", 1)[1].split(")", 1)[0]
        kmp_block = text.split("val kmpSourcePatterns = listOf(", 1)[1].split(")", 1)[0]
        patterns = re.findall(r'"([^"]+)"', legacy_block + kmp_block)
        self.assertEqual([f"{root}/**/*.kt" for root in SOURCE_ROOTS], patterns)
        for root in ("shared-domain/src", "shared-data/src", "shared-ui/src", "desktopApp/src"):
            self.assertIn(root, SOURCE_ROOTS)
            self.assertIn(root, FORMAT_ROOTS)
        self.assertIn("app/src/gms/kotlin", FORMAT_ROOTS)
        self.assertTrue((ROOT / "app/src/gms/kotlin").is_dir())

    def test_formatter_refuses_symlinks_instead_of_copying_unrelated_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "repo"
            root.mkdir()
            subprocess.run(["git", "-C", str(root), "init", "--quiet"], check=True)
            revision = subprocess.check_output(
                ["git", "-C", str(root), "hash-object", "-t", "tree", "--stdin"], input=b"",
            ).decode().strip()
            sources = root / "app/src/main/kotlin"
            sources.mkdir(parents=True)
            outside = Path(temporary) / "outside.kt"
            outside.write_text("class Outside\n")
            (sources / "Alias.kt").symlink_to(outside)
            with self.assertRaisesRegex(ValueError, "regular file"):
                prepare_formatter(root, revision, Path(temporary) / "formatter")

    def test_formatter_patch_is_applicable_and_does_not_rewrite_the_checkout(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "repo"
            formatted = Path(temporary) / "formatter"
            relative = Path("app/src/main/kotlin/Example.kt")
            source = root / relative
            target = formatted / relative
            source.parent.mkdir(parents=True)
            target.parent.mkdir(parents=True)
            original = "/* Original attribution */\nfun example(){println(1)}\n"
            expected = "/* Original attribution */\nfun example() {\n    println(1)\n}\n"
            source.write_text(original)
            target.write_text(expected)
            subprocess.run(["git", "-C", str(root), "init", "--quiet"], check=True)
            patch = formatter_patch(root, formatted)
            self.assertEqual(original, source.read_text())
            subprocess.run(["git", "-C", str(root), "apply", "--check", "-"], input=patch.encode(), check=True)
            subprocess.run(["git", "-C", str(root), "apply", "-"], input=patch.encode(), check=True)
            self.assertEqual(expected, source.read_text())

    def formatting_case(self, base, base_formatted, candidate, candidate_formatted):
        """Run the ratchet with formatter output supplied for the base and candidate copies."""
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        work = Path(temporary.name)
        root = work / "repo"
        relative = Path("app/src/main/kotlin/Legacy.kt")
        source = root / relative
        source.parent.mkdir(parents=True)
        subprocess.run(["git", "-C", str(root), "init", "--quiet"], check=True)
        if base is not None:
            source.write_text(base)
            subprocess.run(["git", "-C", str(root), "add", "."], check=True)
        revision = subprocess.check_output(["git", "-C", str(root), "write-tree"]).decode().strip()
        source.write_text(candidate)
        candidates = work / "candidate"
        (candidates / relative).parent.mkdir(parents=True)
        (candidates / relative).write_text(candidate_formatted)
        copied = prepare_base_formatter(root, revision, candidates, work / "original", work / "formatted")
        self.assertEqual(0 if base is None else 1, copied)
        if base is not None:
            self.assertEqual(base, (work / "original" / relative).read_text())
            (work / "formatted" / relative).write_text(base_formatted)
        return formatting_regressions(root, candidates, work / "original", work / "formatted")

    LEGACY = "fun legacy(){println(1)}\n\nfun kept() = 1\n"
    LEGACY_FORMATTED = "fun legacy() {\n    println(1)\n}\n\nfun kept() = 1\n"

    def test_formatted_edit_in_a_legacy_file_inherits_its_debt(self):
        candidate = self.LEGACY.replace("= 1", "= 2")
        self.assertEqual([], self.formatting_case(
            self.LEGACY, self.LEGACY_FORMATTED, candidate, self.LEGACY_FORMATTED.replace("= 1", "= 2"),
        ))

    def test_removing_unformatted_code_from_a_legacy_file_passes(self):
        candidate = "fun kept() = 1\n"
        self.assertEqual([], self.formatting_case(self.LEGACY, self.LEGACY_FORMATTED, candidate, candidate))

    def test_unformatted_addition_to_a_legacy_file_fails(self):
        candidate = self.LEGACY + "fun added( ) = 3\n"
        self.assertEqual(
            ["app/src/main/kotlin/Legacy.kt: formatter rewrites 2 lines, base 1"],
            self.formatting_case(
                self.LEGACY, self.LEGACY_FORMATTED, candidate, self.LEGACY_FORMATTED + "fun added() = 3\n",
            ),
        )

    def test_new_file_must_be_fully_formatted(self):
        self.assertEqual(
            ["app/src/main/kotlin/Legacy.kt: formatter rewrites 1 lines, base 0"],
            self.formatting_case(None, None, "fun added(){}\n", "fun added() {}\n"),
        )


    def test_formatter_selects_changed_kmp_sources_but_not_unchanged_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "repo"
            root.mkdir()
            def git(*args):
                return subprocess.check_output(["git", "-C", str(root), *args], stderr=subprocess.DEVNULL).decode().strip()
            git("init", "--quiet")
            changed = root / "shared-ui/src/commonMain/kotlin/Changed.kt"
            unchanged = root / "shared-data/src/commonMain/kotlin/Unchanged.kt"
            changed.parent.mkdir(parents=True)
            unchanged.parent.mkdir(parents=True)
            changed.write_text("class Changed\n")
            unchanged.write_text("class Unchanged\n")
            git("add", ".")
            revision = git("write-tree")
            changed.write_text("class Changed(val value: Int)\n")
            destination = Path(temporary) / "formatter"
            self.assertEqual(1, prepare_formatter(root, revision, destination))
            self.assertTrue((destination / "shared-ui/src/commonMain/kotlin/Changed.kt").is_file())
            self.assertFalse((destination / "shared-data/src/commonMain/kotlin/Unchanged.kt").exists())

    def test_structural_profile_includes_kmp_and_legacy_classpath_profile_does_not(self):
        self.assertIn("shared-ui/src", source_roots(PROFILE_STRUCTURAL))
        self.assertNotIn("shared-ui/src", source_roots(PROFILE_LEGACY_ANDROID))
        self.assertEqual(LEGACY_SOURCE_ROOTS, source_roots(PROFILE_LEGACY_ANDROID))
        self.assertEqual(PROFILE_LEGACY_ANDROID, profile_for_classpath(Path("classpath.txt")))
        self.assertEqual(PROFILE_STRUCTURAL, profile_for_classpath(None))

    def test_base_must_be_an_explicit_non_head_full_commit(self):
        head = "b" * 40
        base = "a" * 40
        def fake(command, *args, **kwargs):
            if command[-1] == "HEAD":
                return (head + "\n").encode()
            if "merge-base" in command:
                return (base + "\n").encode()
            if command[-1].endswith("^{commit}"):
                return (base + "\n").encode()
            raise AssertionError(command)
        for value in ("HEAD", "0" * 40, head[:12], head):
            with self.assertRaises(ValueError):
                resolve_base(value)
        with patch("scripts.check_code_quality.subprocess.check_output", side_effect=fake):
            self.assertEqual(base, resolve_base(base))
        def fake_head(command, *args, **kwargs):
            if command[-1] == "HEAD":
                return (head + "\n").encode()
            if "merge-base" in command:
                return (head + "\n").encode()
            if command[-1].endswith("^{commit}"):
                return (head + "\n").encode()
            raise AssertionError(command)
        with patch("scripts.check_code_quality.subprocess.check_output", side_effect=fake_head):
            with self.assertRaisesRegex(ValueError, "candidate HEAD"):
                resolve_base(head)

    def test_git_confirmed_rename_maps_base_findings_and_formatter_copy(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "repo"
            root.mkdir()
            def git(*args, **kwargs):
                return subprocess.check_output(["git", "-C", str(root), *args], stderr=subprocess.DEVNULL, **kwargs).decode().strip()
            git("init", "--quiet")
            old = "shared-ui/src/commonMain/kotlin/Legacy.kt"
            new = "shared-ui/src/commonMain/kotlin/Renamed.kt"
            old_path = root / old
            old_path.parent.mkdir(parents=True)
            old_path.write_text("fun legacy(){}\n")
            git("add", ".")
            base_tree = git("write-tree")
            (root / new).parent.mkdir(parents=True, exist_ok=True)
            git("mv", old, new)
            git("add", "-A")
            current_tree = git("write-tree")
            status = subprocess.check_output(
                ["git", "-C", str(root), "diff-tree", "-r", "-M", "--name-status", "-z", base_tree, current_tree]
            )
            renames = parse_rename_status(status)
            self.assertEqual({old: new}, renames)
            findings, weights = remap_findings(
                Counter({(old, "LongMethod", "message"): 1}), {(old, "LongMethod", "message"): 81}, renames
            )
            self.assertIn((new, "LongMethod", "message"), findings)
            self.assertIn((new, "LongMethod", "message"), weights)
            self.assertEqual([], regressions(
                findings, Counter({(new, "LongMethod", "message"): 1}), weights,
                {(new, "LongMethod", "message"): 81},
            ))
            self.assertEqual({}, parse_rename_status(b"A\0new.kt\0C100\0old.kt\0copy.kt\0M\0same.kt\0"))
            duplicates = remap_duplicates(Counter({((old, "app/A.kt"), "fragment"): 1}), renames)
            self.assertIn((tuple(sorted((new, "app/A.kt"))), "fragment"), duplicates)
            candidates = Path(temporary) / "candidates"
            formatted = candidates / new
            formatted.parent.mkdir(parents=True)
            formatted.write_text("fun legacy() {}\n")
            original = Path(temporary) / "original"
            formatted_base = Path(temporary) / "formatted-base"
            self.assertEqual(1, prepare_base_formatter(
                root, base_tree, candidates, original, formatted_base, renames
            ))
            self.assertTrue((original / new).is_file())
            self.assertTrue((formatted_base / new).is_file())
            (formatted_base / new).write_text("fun legacy() {}\n")
            self.assertEqual([], formatting_regressions(root, candidates, original, formatted_base))

    def test_partial_rename_and_extraction_case_records_unproved_lineage_as_new(self):
        old = "app/src/main/kotlin/moe/rukamori/archivetune/ui/screens/RukamoriHomeScreen.kt"
        renamed = "app/src/main/kotlin/moe/rukamori/archivetune/ui/screens/NatuneClassicHomeScreen.kt"
        extracted = "app/src/main/kotlin/moe/rukamori/archivetune/ui/screens/NatuneClassicHomeComponents.kt"
        status = (
            f"R055\0{old}\0{renamed}\0A\0{extracted}\0"
        ).encode()
        renames = parse_rename_status(status)
        self.assertEqual({old: renamed}, renames)
        quick = "QuickPicksSection is too long (81). Allowed: 80."
        home_old = "RukamoriHomeScreen is too long (90). Allowed: 80."
        home_new = "NatuneClassicHomeScreen is too long (90). Allowed: 80."
        before, weights = remap_findings(
            Counter({(old, "LongMethod", quick): 1, (old, "LongMethod", home_old): 1}),
            {(old, "LongMethod", quick): 81, (old, "LongMethod", home_old): 90},
            renames,
        )
        after = Counter({
            (extracted, "LongMethod", quick): 1,
            (renamed, "LongMethod", home_new): 1,
        })
        self.assertEqual(
            {
                (extracted, "LongMethod", quick),
                (renamed, "LongMethod", home_new),
            },
            set(regressions(before, after, weights, {(extracted, "LongMethod", quick): 81,
                                                     (renamed, "LongMethod", home_new): 90})),
        )
        self.assertNotIn(extracted, renames)
        self.assertEqual({old: renamed}, renames)

    def test_extract_sources_collects_nonempty_kmp_roots(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "repo"
            root.mkdir()
            def git(*args):
                return subprocess.check_output(["git", "-C", str(root), *args], stderr=subprocess.DEVNULL).decode().strip()
            git("init", "--quiet")
            for relative in ("shared-ui/src/commonMain/kotlin/A.kt", "desktopApp/src/jvmMain/kotlin/B.kt"):
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(f"class {Path(relative).stem}\n")
            git("add", ".")
            tree = git("write-tree")
            destination = Path(temporary) / "extract"
            extract_sources(tree, destination, ("shared-ui/src", "desktopApp/src"), root=root)
            self.assertTrue((destination / "shared-ui/src/commonMain/kotlin/A.kt").is_file())
            self.assertTrue((destination / "desktopApp/src/jvmMain/kotlin/B.kt").is_file())

    def test_build_pr_quality_job_is_independent_and_uses_explicit_trusted_base(self):
        text = (ROOT / ".github/workflows/build_pull_request.yml").read_text()
        job = text.split("  kotlin-quality:\n", 1)[1].split("\n  # The desktop host", 1)[0]
        self.assertIn("timeout-minutes: 20", job)
        self.assertIn("fetch-depth: 0", job)
        self.assertIn("persist-credentials: false", job)
        self.assertIn("cache-read-only: true", job)
        self.assertIn("quality_base", text)
        self.assertIn("--base \"$BASE_SHA\"", job)
        self.assertIn("github.event.pull_request.base.sha", job)
        self.assertIn("github.event.before", job)
        self.assertIn("inputs.quality_base", job)
        self.assertIn("github.event_name == 'workflow_dispatch' && inputs.quality_base != ''", job)
        self.assertIn("android_release_candidate", job)
        self.assertIn("toJSON(github.event.inputs)", job)
        self.assertIn("quality_base is quality-only", job)
        self.assertIn('git fetch --no-tags origin "$base"', job)
        self.assertIn("git merge-base", job)
        self.assertLess(job.index("Reject incompatible manual modes"), job.index("actions/checkout@v7"))
        self.assertLess(job.index("Resolve the explicit quality base"), job.index("Set up JDK 21"))
        self.assertIn("ubuntu-latest", job)
        self.assertIn("blacksmith-8vcpu-ubuntu-2404", job)
        self.assertNotIn("sdkmanager", job)
        self.assertNotIn("needs:", job)
        self.assertNotIn("assemble", job)
        self.assertNotIn("desktopApp", job)
        self.assertNotIn("buildNativeEngine", job)
        self.assertIn("quality-only", text.split("quality_base:", 1)[1].split("permissions:", 1)[0].lower())

    def test_manual_quality_base_is_quality_only_for_normal_jobs(self):
        text = (ROOT / ".github/workflows/build_pull_request.yml").read_text()
        build = text.split("  build:\n", 1)[1].split("  kotlin-quality:\n", 1)[0]
        desktop = text.split("  desktop-compile:\n", 1)[1]
        self.assertIn("inputs.quality_base == ''", build)
        self.assertIn("inputs.quality_base == ''", desktop)
        self.assertIn("inputs.desktop_app_image != true", build)


if __name__ == "__main__":
    unittest.main()
