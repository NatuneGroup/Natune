"""Secret-free release-step regressions. Native/JVM builds and GitHub calls are never executed."""

import itertools
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/_release-artifacts.yml"
NIGHTLY_VARIANTS = (
    "gms-mobile-arm64", "gms-mobile-armeabi", "gms-mobile-x86_64",
    "gms-mobile-universal", "foss-mobile-universal", "gms-tv-universal",
)
SIGNING_NAMES = ("KEYSTORE", "KEY_ALIAS", "KEYSTORE_PASSWORD", "KEY_PASSWORD")


def step_script(name, workflow=WORKFLOW):
    body = workflow.read_text().split(f"      - name: {name}\n", 1)[1]
    body = re.split(r"(?m)^      - |^  [a-z][a-z-]*:\n", body, maxsplit=1)[0]
    run = re.search(r"(?m)^        run: \|\n((?: {10}.*\n|\n)+)", body)
    if run is None:
        raise AssertionError(f"No block script in step {name}")
    return textwrap.dedent(run[1])


def tool(directory, name, source):
    path = directory / name
    path.write_text(textwrap.dedent(source).lstrip())
    path.chmod(0o755)


def bash_step(name, directory, **overrides):
    env = os.environ.copy()
    env.update({name: "" for name in SIGNING_NAMES})
    env.update(overrides)
    return subprocess.run(
        ["bash", "-c", step_script(name)], cwd=directory, env=env,
        capture_output=True, text=True, timeout=30,
    )


class ReleaseWorkflowTest(unittest.TestCase):
    def test_sdk_setup_is_the_working_pr_step_and_passes_separate_package_arguments(self):
        name = "Install Android SDK packages"
        self.assertEqual(step_script(name, ROOT / ".github/workflows/build_pull_request.yml"), step_script(name))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sdk_bin = root / "sdk/cmdline-tools/latest/bin"
            sdk_bin.mkdir(parents=True)
            arguments = root / "sdk-arguments"
            tool(sdk_bin, "sdkmanager", '#!/bin/sh\nprintf "%s\\n" "$@" >> "$SDK_ARGUMENTS"\n')
            result = bash_step(name, root, ANDROID_HOME=str(root / "sdk"), SDK_ARGUMENTS=str(arguments))
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual([
                "--licenses", "platform-tools", "platforms;android-37.0", "build-tools;35.0.0", "ndk;30.0.16248370",
            ], arguments.read_text().splitlines())

    def test_six_nightly_variants_and_the_stable_only_x86_row_resolve_the_existing_tasks(self):
        workflow = WORKFLOW.read_text()
        rows = re.search(r"(?m)^        variant:\n((?:          - [\w-]+\n)+)", workflow)[1]
        self.assertEqual(list(NIGHTLY_VARIANTS), re.findall(r"- ([\w-]+)", rows))
        self.assertIn("inputs.channel == 'stable' && 'gms-mobile-x86' || 'gms-mobile-arm64'", workflow)
        for channel in ("nightly", "stable"):
            variants = NIGHTLY_VARIANTS + (("gms-mobile-x86",) if channel == "stable" else ())
            for variant in variants:
                with self.subTest(channel=channel, variant=variant), tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    output = root / "output"
                    result = bash_step("Resolve variant and output names", root,
                                       CHANNEL=channel, VARIANT=variant, GITHUB_OUTPUT=str(output))
                    self.assertEqual(0, result.returncode, result.stderr)
                    distribution, device, abi = variant.split("-")
                    suffix = "nightly" if channel == "nightly" else "release"
                    self.assertEqual([
                        f"gradle_task=assemble{distribution.capitalize()}{device.capitalize()}{abi[0].upper()}{abi[1:]}Release",
                        f"apk_name=app-{variant}-{suffix}.apk",
                    ], output.read_text().splitlines())

    def test_every_incomplete_signing_secret_set_leaves_the_apk_unsigned_and_unchanged(self):
        for present in itertools.product((False, True), repeat=len(SIGNING_NAMES)):
            if all(present):
                continue
            with self.subTest(present=present), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                apk = root / "release-assets/fixture.apk"
                apk.parent.mkdir()
                apk.write_bytes(b"unsigned APK fixture")
                output = root / "output"
                secrets = dict(zip(SIGNING_NAMES, ("configured" if value else "" for value in present)))
                result = bash_step("Sign APK when the historical keystore is configured", root,
                                   APK_NAME=apk.name, GITHUB_OUTPUT=str(output), **secrets)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual("signing_status=unsigned\n", output.read_text())
                self.assertEqual(b"unsigned APK fixture", apk.read_bytes())

    def publish_fixture(self, directory, channel, *, missing=False, stale=False):
        root = Path(directory)
        assets = root / "downloaded-artifacts"
        assets.mkdir()
        suffix = "nightly" if channel == "nightly" else "release"
        variants = NIGHTLY_VARIANTS + (("gms-mobile-x86",) if channel == "stable" else ())
        names = [f"app-{variant}-{suffix}.apk" for variant in variants]
        names += [f"natune-desktop-windows-x64-fixture.{ext}" for ext in ("msi", "exe", "zip")]
        names += [f"natune-desktop-linux-{arch}-fixture.{ext}" for arch in ("x64", "arm64") for ext in ("deb", "AppImage")]
        names += [f"natune-desktop-macos-{arch}-fixture.dmg" for arch in ("x64", "arm64")]
        names += ["natune-ios-fixture.ipa"]
        for name in (names[:-1] if missing else names):
            (assets / name).write_bytes(b"unsigned artifact fixture")
        scripts = root / ".github/scripts"
        scripts.mkdir(parents=True)
        shutil.copyfile(ROOT / ".github/scripts/update_manifest.py", scripts / "update_manifest.py")
        bin_dir = root / "bin"
        bin_dir.mkdir()
        tool(bin_dir, "git", "#!/bin/sh\nexit 0\n")
        tool(bin_dir, "gh", """
            #!/usr/bin/env python3
            import json, os, sys
            args = sys.argv[1:]
            with open(os.environ["GH_CALLS"], "a") as output:
                output.write(json.dumps(args) + "\\n")
            if args[0] == "api" and "/commits/" in args[1]:
                print(os.environ["REMOTE_SHA"])
            elif args[:2] == ["release", "create"]:
                pass
            elif args[:2] == ["release", "view"] and "publishedAt" in args:
                print("2030-01-01T00:00:00Z")
            elif args[:2] == ["release", "view"] or (args[0] == "api" and "/git/ref/tags/" in args[1]):
                sys.exit(1)
            else:
                sys.exit("Unexpected GitHub command: " + repr(args))
        """)
        temporary = root / "tmp"
        temporary.mkdir()
        commit = "a" * 40
        result = bash_step("Create release and update-feed manifest", root,
                           CHANNEL=channel, TAG_NAME="N20300101" if channel == "nightly" else "v1.2.3",
                           VERSION_NAME="fixture", VERSION_CODE="9001", COMMIT_SHA=commit,
                           REMOTE_SHA="b" * 40 if stale else commit, ANDROID_SIGNING_STATUS="unsigned",
                           GITHUB_REPOSITORY="NatuneGroup/ArchiveTune-KMP", GITHUB_OUTPUT=str(root / "output"),
                           RUNNER_TEMP=str(temporary), GH_CALLS=str(root / "gh-calls"), GH_TOKEN="",
                           PATH=str(bin_dir) + os.pathsep + os.environ["PATH"])
        return result, names

    def test_unsigned_nightly_and_stable_publish_the_channel_inventory_and_private_manifest(self):
        for channel, count in (("nightly", 16), ("stable", 17)):
            with self.subTest(channel=channel), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                result, names = self.publish_fixture(root, channel)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual("did_publish=true\n", (root / "output").read_text())
                calls = [json.loads(line) for line in (root / "gh-calls").read_text().splitlines()]
                create = next(args for args in calls if args[:2] == ["release", "create"])
                self.assertEqual(count, len(names))
                self.assertEqual(set(names), {Path(arg).name for arg in create if arg.startswith("downloaded-artifacts/")})
                self.assertIn("Android APKs are unsigned", (root / "tmp/release-notes.md").read_text())
                manifest = json.loads((root / f"{channel}.json").read_text())
                self.assertEqual(count - 10, len(manifest["assets"]))
                for asset in manifest["assets"]:
                    self.assertTrue(asset["url"].startswith("https://github.com/NatuneGroup/ArchiveTune-KMP/releases/download/"))
                    self.assertEqual(64, len(asset["sha256"]))

    def test_incomplete_inventory_is_not_published(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            result, _ = self.publish_fixture(root, "nightly", missing=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("Expected 16 release assets including 6 nightly APKs", result.stdout)
            self.assertNotIn('"create"', (root / "gh-calls").read_text())

    def test_stale_head_is_not_published(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            result, _ = self.publish_fixture(root, "nightly", stale=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual("did_publish=false\n", (root / "output").read_text())
            self.assertNotIn('"create"', (root / "gh-calls").read_text())

    def test_release_handoff_artifacts_expire_after_three_days(self):
        retention = re.findall(r"retention-days: (\d+)", WORKFLOW.read_text())
        self.assertEqual(["3"] * 4, retention)

    def test_windows_packaging_uses_the_sdk_root_and_windows_launcher_layout(self):
        sdk = step_script("Download WebView2 SDK (Windows)")
        self.assertIn('"WEBVIEW2_SDK=$sdkRoot"', sdk)
        self.assertIn("build\\native\\include\\WebView2.h", sdk)
        stage = step_script("Stage Windows installers and portable ZIP")
        self.assertIn("(Join-Path $appImage 'Natune.exe')", stage)
        self.assertNotIn("bin/Natune.exe", stage)


if __name__ == "__main__":
    unittest.main()
