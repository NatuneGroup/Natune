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
OPTIONAL_FIXTURES = (
    "natune-desktop-macos-x64-fixture.dmg",
    "natune-desktop-macos-arm64-fixture.dmg",
    "natune-ios-fixture.ipa",
)
# The desktop updater takes the first manifest asset for its platform and architecture.
DESKTOP_MANIFEST_ORDER = (
    "natune-desktop-windows-x64-fixture.exe",
    "natune-desktop-windows-x64-fixture.msi",
    "natune-desktop-windows-x64-fixture.zip",
    "natune-desktop-linux-x64-fixture.AppImage",
    "natune-desktop-linux-x64-fixture.deb",
    "natune-desktop-linux-arm64-fixture.AppImage",
    "natune-desktop-linux-arm64-fixture.deb",
)


def step_script(name, workflow=WORKFLOW):
    body = workflow.read_text().split(f"      - name: {name}\n", 1)[1]
    body = re.split(r"(?m)^      - |^  [a-z][a-z-]*:\n", body, maxsplit=1)[0]
    run = re.search(r"(?m)^        run: \|\n((?: {10}.*\n|\n)+)", body)
    if run is None:
        raise AssertionError(f"No block script in step {name}")
    return textwrap.dedent(run[1])


def job_block(text, job):
    return re.split(r"(?m)^  [a-z][a-z-]*:", text.split(f"\n  {job}:\n", 1)[1], maxsplit=1)[0]


def tool(directory, name, source):
    path = directory / name
    path.write_text(textwrap.dedent(source).lstrip())
    path.chmod(0o755)


def step_blocks(workflow=WORKFLOW):
    """Every named step with its own text, in file order; a step ends at the next step or job."""
    text = workflow.read_text()
    positions = [(match.start(), match.group(1)) for match in re.finditer(r"(?m)^      - name: (.+)$", text)]
    blocks = []
    for index, (start, name) in enumerate(positions):
        end = positions[index + 1][0] if index + 1 < len(positions) else len(text)
        blocks.append((name, re.split(r"(?m)^  [a-z][a-z-]*:\n", text[start:end], maxsplit=1)[0]))
    return blocks


def staging_tools(directory):
    """A `gh` stand-in: records calls, serves staging tag refs, writes downloaded fixture assets."""
    bin_dir = directory / "bin"
    bin_dir.mkdir()
    tool(bin_dir, "gh", """
        #!/usr/bin/env python3
        import json, os, sys
        args = sys.argv[1:]
        with open(os.environ["GH_CALLS"], "a") as calls:
            calls.write(json.dumps(args) + "\\n")
        if args[0] == "api":
            for ref in [line for line in os.environ.get("STAGING_REFS", "").splitlines() if line]:
                print(ref)
            sys.exit(0)
        if args[:2] == ["release", "view"]:
            sys.exit(0 if os.environ.get("STAGING_RELEASE_EXISTS") == "1" else 1)
        if args[:2] == ["release", "download"]:
            target = args[args.index("--dir") + 1]
            os.makedirs(target, exist_ok=True)
            name = args[args.index("--pattern") + 1] if "--pattern" in args else "asset-" + args[2] + ".apk"
            with open(os.path.join(target, name), "wb") as asset:
                asset.write(b"staged release fixture")
            sys.exit(0)
        if args[:2] in (["release", "create"], ["release", "upload"], ["release", "delete"]):
            sys.exit(0)
        sys.exit("Unexpected GitHub command: " + repr(args))
    """)
    return str(bin_dir) + os.pathsep + os.environ["PATH"]


def staging_step(name, directory, **overrides):
    env = os.environ.copy()
    env.update(overrides)
    return subprocess.run(
        ["bash", "-c", step_script(name)], cwd=directory, env=env,
        capture_output=True, text=True, timeout=30,
    )


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

    def test_stage_apk_lists_the_single_release_apk_before_copying_it(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            release = root / "app/build/outputs/apk/gmsMobileUniversal/release"
            release.mkdir(parents=True)
            (release / "app-gmsMobileUniversal-release.apk").write_bytes(b"release APK fixture")
            # AGP writes its listing metadata beside the APK; it must not count as a second APK.
            (release / "output-metadata.json").write_text("{}")
            result = bash_step("Stage APK", root, APK_NAME="app-gms-mobile-universal-nightly.apk",
                               GITHUB_OUTPUT=str(root / "output"))
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("app-gmsMobileUniversal-release.apk", result.stdout)
            self.assertIn("APK files under app/build/outputs/apk:", result.stdout)
            staged = root / "release-assets/app-gms-mobile-universal-nightly.apk"
            self.assertEqual(b"release APK fixture", staged.read_bytes())

    def test_stage_apk_names_every_candidate_when_the_count_is_wrong(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            release = root / "app/build/outputs/apk/gmsMobileUniversal/release"
            release.mkdir(parents=True)
            for name in ("app-gmsMobileUniversal-release.apk", "app-gmsMobileUniversal-release-unsigned.apk"):
                (release / name).write_bytes(b"release APK fixture")
            result = bash_step("Stage APK", root, APK_NAME="app-gms-mobile-universal-nightly.apk",
                               GITHUB_OUTPUT=str(root / "output"))
            self.assertNotEqual(0, result.returncode)
            self.assertIn("Expected exactly one release APK, found 2.", result.stdout)
            self.assertIn("app-gmsMobileUniversal-release-unsigned.apk", result.stdout)
            self.assertFalse((root / "release-assets").exists())

    def test_stage_apk_fails_with_the_build_tree_when_no_apk_directory_exists(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "app/build/outputs").mkdir(parents=True)
            result = bash_step("Stage APK", root, APK_NAME="app-gms-mobile-universal-nightly.apk",
                               GITHUB_OUTPUT=str(root / "output"))
            self.assertNotEqual(0, result.returncode)
            self.assertIn("app/build/outputs/apk does not exist", result.stdout)

    def test_signing_uses_build_tools_that_actually_contain_both_tools(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / "release-assets/fixture.apk"
            apk.parent.mkdir()
            apk.write_bytes(b"unsigned APK fixture")
            build_tools = root / "sdk/build-tools"
            (build_tools / "35.0.0").mkdir(parents=True)
            (build_tools / "34.0.0").mkdir(parents=True)
            tool(build_tools / "34.0.0", "zipalign", """
                #!/usr/bin/env python3
                import os, sys
                with open(os.environ["TOOL_CALLS"], "a") as calls:
                    calls.write("zipalign " + " ".join(sys.argv[1:]) + "\\n")
                open(sys.argv[-1], "wb").write(b"aligned APK fixture")
            """)
            tool(build_tools / "34.0.0", "apksigner", """
                #!/usr/bin/env python3
                import os, sys
                with open(os.environ["TOOL_CALLS"], "a") as calls:
                    calls.write("apksigner " + " ".join(sys.argv[1:]) + "\\n")
                open(sys.argv[-1], "ab").write(b" signed")
            """)
            temporary = root / "tmp"
            temporary.mkdir()
            output = root / "output"
            result = bash_step("Sign APK when the historical keystore is configured", root,
                               APK_NAME=apk.name, GITHUB_OUTPUT=str(output),
                               ANDROID_HOME=str(root / "sdk"), RUNNER_TEMP=str(temporary),
                               TOOL_CALLS=str(root / "tool-calls"), KEYSTORE="a2V5c3RvcmU=",
                               KEY_ALIAS="alias", KEYSTORE_PASSWORD="store", KEY_PASSWORD="key")
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual("signing_status=signed\n", output.read_text())
            self.assertIn("Using build-tools: " + str(build_tools / "34.0.0"), result.stdout)
            calls = (root / "tool-calls").read_text().splitlines()
            self.assertEqual(["zipalign", "apksigner"], [call.split(" ", 1)[0] for call in calls])
            self.assertEqual(b"aligned APK fixture signed", apk.read_bytes())

    def test_signing_reports_the_sdk_root_when_build_tools_cannot_be_found(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / "release-assets/fixture.apk"
            apk.parent.mkdir()
            apk.write_bytes(b"unsigned APK fixture")
            temporary = root / "tmp"
            temporary.mkdir()
            result = bash_step("Sign APK when the historical keystore is configured", root,
                               APK_NAME=apk.name, GITHUB_OUTPUT=str(root / "output"),
                               ANDROID_HOME=str(root / "missing-sdk"), HOME=str(root / "home"),
                               RUNNER_TEMP=str(temporary),
                               KEYSTORE="a2V5c3RvcmU=", KEY_ALIAS="alias",
                               KEYSTORE_PASSWORD="store", KEY_PASSWORD="key")
            self.assertNotEqual(0, result.returncode)
            self.assertIn("Android SDK root: ", result.stdout)
            self.assertIn("Android build-tools do not contain apksigner and zipalign.", result.stdout)
            self.assertEqual(b"unsigned APK fixture", apk.read_bytes())

    def publish_fixture(self, directory, channel, *, drop=(), stale=False, flags=None):
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
        for name in names:
            if name in drop:
                continue
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
            elif args[:2] == ["release", "view"]:
                if os.environ.get("RELEASE_READ_ERROR"):
                    sys.exit("HTTP 503")
                sha = os.environ.get("EXISTING_RELEASE_SHA")
                if not sha:
                    sys.exit("release not found")
                print(json.dumps({"targetCommitish": sha, "body": os.environ.get("EXISTING_RELEASE_BODY", "")}))
            elif args[0] == "api" and "/git/ref/tags/" in args[1]:
                if os.environ.get("TAG_READ_ERROR"):
                    sys.exit("HTTP 503")
                sha = os.environ.get("EXISTING_TAG_SHA")
                if not sha:
                    sys.exit("gh: Not Found (HTTP 404)")
                print(json.dumps({"object": {"type": os.environ.get("EXISTING_TAG_TYPE", "commit"), "sha": sha}}))
            elif args[0] == "api" and "/git/tags/" in args[1]:
                print(json.dumps({"type": "commit", "sha": os.environ["ANNOTATED_COMMIT_SHA"]}))
            elif args[:2] == ["release", "delete"] or (args[0] == "api" and "DELETE" in args):
                pass
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
                           GITHUB_REPOSITORY="NatuneGroup/Natune", GITHUB_OUTPUT=str(root / "output"),
                           RUNNER_TEMP=str(temporary), GH_CALLS=str(root / "gh-calls"), GH_TOKEN="",
                           PATH=str(bin_dir) + os.pathsep + os.environ["PATH"], **(flags or {}))
        return result, names

    def test_nightly_refuses_another_build_without_deleting_or_creating(self):
        for flags in (
            {"EXISTING_RELEASE_SHA": "b" * 40, "EXISTING_RELEASE_BODY": "<!-- at-build:9001 -->"},
            {"EXISTING_RELEASE_SHA": "a" * 40, "EXISTING_RELEASE_BODY": "<!-- at-build:9002 -->"},
            {"EXISTING_RELEASE_SHA": "a" * 40, "EXISTING_RELEASE_BODY": "<!-- at-build:90010 -->"},
            {"EXISTING_TAG_SHA": "b" * 40},
            {"EXISTING_RELEASE_SHA": "a" * 40, "EXISTING_RELEASE_BODY": "at-build:9001", "EXISTING_TAG_SHA": "b" * 40},
            {"RELEASE_READ_ERROR": "1", "EXISTING_TAG_SHA": "a" * 40},
            {"TAG_READ_ERROR": "1", "EXISTING_RELEASE_SHA": "a" * 40, "EXISTING_RELEASE_BODY": "at-build:9001"},
        ):
            with self.subTest(flags=flags), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                result, _ = self.publish_fixture(root, "nightly", flags=flags)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("::error::Nightly", result.stdout)
                calls = [json.loads(line) for line in (root / "gh-calls").read_text().splitlines()]
                self.assertFalse(any(args[:2] in (["release", "delete"], ["release", "create"]) or
                                     "DELETE" in args for args in calls))

    def test_nightly_same_build_rerun_and_its_bare_tag_can_be_replaced(self):
        for kind in ("release", "bare", "annotated"):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                flags = {"EXISTING_TAG_SHA": "a" * 40}
                if kind == "annotated":
                    flags.update(EXISTING_TAG_TYPE="tag", EXISTING_TAG_SHA="c" * 40, ANNOTATED_COMMIT_SHA="a" * 40)
                if kind == "release":
                    flags.update(EXISTING_RELEASE_SHA="a" * 40, EXISTING_RELEASE_BODY="<!-- at-build:9001 -->")
                result, _ = self.publish_fixture(root, "nightly", flags=flags)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                calls = [json.loads(line) for line in (root / "gh-calls").read_text().splitlines()]
                self.assertEqual(1, sum(args[:2] == ["release", "create"] for args in calls))
                self.assertEqual(1, sum(args[:2] == ["release", "delete"] or "DELETE" in args for args in calls))
                self.assertTrue((root / "nightly.json").is_file())

    def prune_fixture(self, directory, manifest, *, unreadable=False):
        root = Path(directory)
        bin_dir = root / "bin"
        bin_dir.mkdir()
        tool(bin_dir, "gh", """
            #!/usr/bin/env python3
            import json, os, sys
            args = sys.argv[1:]
            with open(os.environ["GH_CALLS"], "a") as calls:
                calls.write(json.dumps(args) + "\\n")
            if args[:2] == ["release", "download"]:
                if os.environ["UNREADABLE"] == "1":
                    sys.exit(1)
                print(os.environ["MANIFEST"])
            elif args[0] == "api":
                for minute in range(8):
                    print(f"N20300101000{minute}\\t2030-01-01T00:0{minute}:00Z")
            elif args[:2] != ["release", "delete"]:
                sys.exit("Unexpected GitHub command: " + repr(args))
        """)
        result = bash_step("Keep only the newest five nightly releases", root,
                           TAG_NAME="N203001010007", GITHUB_REPOSITORY="NatuneGroup/Natune",
                           GH_CALLS=str(root / "gh-calls"), MANIFEST=manifest,
                           UNREADABLE="1" if unreadable else "0",
                           PATH=str(bin_dir) + os.pathsep + os.environ["PATH"])
        calls = [json.loads(line) for line in (root / "gh-calls").read_text().splitlines()]
        return result, calls

    def test_prune_preserves_the_published_feed_tag_even_outside_the_newest_five(self):
        with tempfile.TemporaryDirectory() as directory:
            result, calls = self.prune_fixture(directory, '{"tag":"N203001010001"}')
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertEqual(["N203001010002", "N203001010000"],
                             [args[2] for args in calls if args[:2] == ["release", "delete"]])

    def test_prune_deletes_nothing_when_the_manifest_cannot_be_read_or_parsed(self):
        for manifest, unreadable in (("", True), ("not json", False), ('{"tag":null}', False),
                                     ('{"tag":"v1.2.3"}', False)):
            with self.subTest(manifest=manifest, unreadable=unreadable), tempfile.TemporaryDirectory() as directory:
                result, calls = self.prune_fixture(directory, manifest, unreadable=unreadable)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertIn("::warning::", result.stdout)
                self.assertFalse(any(args[:2] == ["release", "delete"] for args in calls))
                self.assertFalse(any(args[0] == "api" for args in calls))

    def test_prepare_allocates_minute_tags_and_bumps_release_and_bare_tag_collisions(self):
        for occupied in ("free", "collisions", "exhausted"):
            with self.subTest(occupied=occupied), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                (root / "app").mkdir()
                shutil.copyfile(ROOT / "app/build.gradle.kts", root / "app/build.gradle.kts")
                (root / ".github/scripts").mkdir(parents=True)
                shutil.copyfile(ROOT / ".github/scripts/next-version.py", root / ".github/scripts/next-version.py")
                bin_dir = root / "bin"
                bin_dir.mkdir()
                tool(bin_dir, "git", "#!/bin/sh\nexit 0\n")
                tool(bin_dir, "date", """
                    #!/usr/bin/env python3
                    from datetime import datetime, timedelta, timezone
                    import sys
                    args = sys.argv[1:]
                    moment = datetime(2030, 1, 1, 23, 59, tzinfo=timezone.utc)
                    if "-d" in args:
                        text = args[args.index("-d") + 1]
                        moment = datetime.strptime(text, "%Y-%m-%d %H:%M:%S UTC +1 minute").replace(tzinfo=timezone.utc) + timedelta(minutes=1)
                    print(int(moment.timestamp()) if args[-1] == "+%s" else moment.strftime(args[-1][1:]))
                """)
                tool(bin_dir, "gh", """
                    #!/usr/bin/env python3
                    import json, os, sys
                    args = sys.argv[1:]
                    with open(os.environ["GH_CALLS"], "a") as calls:
                        calls.write(json.dumps(args) + "\\n")
                    if os.environ["OCCUPIED"] == "exhausted":
                        sys.exit(0)
                    if os.environ["OCCUPIED"] == "collisions":
                        if args[:3] == ["release", "view", "N203001012359"]:
                            sys.exit(0)
                        if args[:2] == ["api", "repos/NatuneGroup/Natune/git/ref/tags/N203001020000"]:
                            sys.exit(0)
                    sys.exit(1)
                """)
                environment = os.environ.copy()
                environment.update(FORCE="true", GITHUB_REPOSITORY="NatuneGroup/Natune", GITHUB_SHA="a" * 40,
                                   GITHUB_OUTPUT=str(root / "output"), GH_CALLS=str(root / "gh-calls"),
                                   OCCUPIED=occupied, PATH=str(bin_dir) + os.pathsep + os.environ["PATH"])
                result = subprocess.run(["bash", "-c", step_script("Decide whether to build", ROOT / ".github/workflows/nightly.yml")],
                                        cwd=root, env=environment, capture_output=True, text=True, timeout=30)
                if occupied == "exhausted":
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn("after 20 tries", result.stdout)
                    self.assertFalse((root / "output").exists())
                    self.assertEqual(20, len((root / "gh-calls").read_text().splitlines()))
                    continue
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                outputs = dict(line.split("=", 1) for line in (root / "output").read_text().splitlines())
                expected = "N203001020001" if occupied == "collisions" else "N203001012359"
                self.assertEqual(expected, outputs["tag_name"])
                self.assertEqual(expected, outputs["version_name"])
                self.assertRegex(outputs["tag_name"], r"^N[0-9]{12}$")
                self.assertEqual("true", outputs["should_build"])
                self.assertEqual("15.1.0", outputs["package_version"])
                self.assertEqual(str(1893542340 + 6833), outputs["version_code"])

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
                # macOS and iOS files never ship from the publish job, even when they are already downloaded.
                required = set(names) - set(OPTIONAL_FIXTURES)
                self.assertEqual(required, {Path(arg).name for arg in create if arg.startswith("downloaded-artifacts/")})
                notes = (root / "tmp/release-notes.md").read_text()
                self.assertIn("Android APKs are unsigned", notes)
                self.assertNotIn("Partial build", notes)
                manifest = json.loads((root / f"{channel}.json").read_text())
                apks = [name for name in names if name.endswith(".apk")]
                manifest_names = [asset["name"] for asset in manifest["assets"]]
                self.assertEqual(set(apks), set(manifest_names[:len(apks)]))
                self.assertEqual(list(DESKTOP_MANIFEST_ORDER), manifest_names[len(apks):])
                for asset in manifest["assets"]:
                    self.assertTrue(asset["url"].startswith("https://github.com/NatuneGroup/Natune/releases/download/"))
                    self.assertEqual(64, len(asset["sha256"]))

    def test_incomplete_inventory_is_not_published(self):
        # A required Android or Windows/Linux leg that delivered nothing still stops the release.
        for missing_name in ("app-gms-mobile-arm64-nightly.apk", "natune-desktop-windows-x64-fixture.zip"):
            with self.subTest(missing=missing_name), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                result, _ = self.publish_fixture(root, "nightly", drop=(missing_name,))
                self.assertNotEqual(0, result.returncode)
                self.assertIn("::error::", result.stdout)
                self.assertNotIn('"create"', (root / "gh-calls").read_text())

    def test_missing_macos_and_ios_assets_do_not_change_the_published_release(self):
        # The release contents must not depend on whether the optional legs finished before publishing.
        with tempfile.TemporaryDirectory() as complete_dir, tempfile.TemporaryDirectory() as partial_dir:
            created = []
            for directory, drop in ((complete_dir, ()), (partial_dir, OPTIONAL_FIXTURES)):
                root = Path(directory)
                result, _ = self.publish_fixture(root, "nightly", drop=drop)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual("did_publish=true\n", (root / "output").read_text())
                calls = [json.loads(line) for line in (root / "gh-calls").read_text().splitlines()]
                create = next(args for args in calls if args[:2] == ["release", "create"])
                created.append(sorted(Path(arg).name for arg in create if arg.startswith("downloaded-artifacts/")))
                self.assertEqual(
                    [asset["name"] for asset in json.loads((root / "nightly.json").read_text())["assets"]
                     if "macos" in asset["name"] or "ios" in asset["name"]],
                    [],
                )
            self.assertEqual(created[0], created[1])

    def attach_fixture(self, directory, *, present=OPTIONAL_FIXTURES, release_sha="a" * 40,
                       release_body="notes <!-- at-build:9001 -->", release_exists=True, flags=None):
        root = Path(directory)
        assets = root / "optional-artifacts"
        assets.mkdir()
        for name in present:
            (assets / name).write_bytes(b"optional artifact fixture")
        bin_dir = root / "bin"
        bin_dir.mkdir()
        tool(bin_dir, "gh", """
            #!/usr/bin/env python3
            import json, os, sys
            args = sys.argv[1:]
            with open(os.environ["GH_CALLS"], "a") as output:
                output.write(json.dumps(args) + "\\n")
            if args[:2] == ["release", "view"]:
                if os.environ["RELEASE_EXISTS"] != "1":
                    sys.exit(1)
                print(json.dumps({"body": os.environ["RELEASE_BODY"], "targetCommitish": os.environ["RELEASE_SHA"]}))
            elif args[:2] in (["release", "upload"], ["release", "edit"]):
                if args[:2] == ["release", "edit"]:
                    notes = args[args.index("--notes-file") + 1]
                    with open(os.environ["EDITED_NOTES"], "w") as copy, open(notes) as source:
                        copy.write(source.read())
            else:
                sys.exit("Unexpected GitHub command: " + repr(args))
        """)
        temporary = root / "tmp"
        temporary.mkdir()
        environment = dict(
            TAG_NAME="N20300101", VERSION_NAME="fixture", VERSION_CODE="9001", COMMIT_SHA="a" * 40,
            BUILD_DESKTOP="true", BUILD_IOS="true", MACOS_RESULT="success", IOS_RESULT="failure",
            GITHUB_REPOSITORY="NatuneGroup/Natune", RUNNER_TEMP=str(temporary),
            GH_CALLS=str(root / "gh-calls"), GH_TOKEN="", RELEASE_EXISTS="1" if release_exists else "0",
            RELEASE_SHA=release_sha, RELEASE_BODY=release_body, EDITED_NOTES=str(root / "edited-notes"),
            PATH=str(bin_dir) + os.pathsep + os.environ["PATH"],
        )
        environment.update(flags or {})
        result = bash_step("Attach optional assets to the published release", root, **environment)
        calls_path = root / "gh-calls"
        calls = [json.loads(line) for line in calls_path.read_text().splitlines()] if calls_path.exists() else []
        return result, calls

    def test_optional_assets_are_attached_to_the_published_release(self):
        with tempfile.TemporaryDirectory() as directory:
            result, calls = self.attach_fixture(directory)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            uploads = [args for args in calls if args[:2] == ["release", "upload"]]
            self.assertEqual(1, len(uploads))
            self.assertEqual("N20300101", uploads[0][2])
            self.assertIn("--clobber", uploads[0])
            self.assertEqual(set(OPTIONAL_FIXTURES), {Path(arg).name for arg in uploads[0] if arg.startswith("optional-artifacts/")})
            self.assertEqual([], [args for args in calls if args[:2] == ["release", "edit"]])
            self.assertNotIn("::warning::", result.stdout)

    def test_missing_optional_assets_are_listed_on_the_published_release(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            present = OPTIONAL_FIXTURES[:2]
            result, calls = self.attach_fixture(root, present=present)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("Optional artifact 'natune-ios-fixture.ipa' is absent", result.stdout)
            uploads = [args for args in calls if args[:2] == ["release", "upload"]]
            self.assertEqual(set(present), {Path(arg).name for arg in uploads[0] if arg.startswith("optional-artifacts/")})
            edits = [args for args in calls if args[:2] == ["release", "edit"]]
            self.assertEqual(1, len(edits))
            notes = (root / "edited-notes").read_text()
            self.assertIn("at-build:9001", notes)
            self.assertIn("Partial build", notes)
            self.assertIn("natune-ios-fixture.ipa", notes)
            self.assertNotIn("natune-desktop-macos-x64-fixture.dmg", notes)

    def test_disabled_ios_leg_is_not_reported_missing(self):
        with tempfile.TemporaryDirectory() as directory:
            result, calls = self.attach_fixture(directory, present=OPTIONAL_FIXTURES[:2], flags={"BUILD_IOS": "false"})
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertNotIn("::warning::", result.stdout)
            self.assertEqual([], [args for args in calls if args[:2] == ["release", "edit"]])

    def test_optional_assets_never_reach_a_release_from_another_build(self):
        cases = (
            {"release_exists": False},
            {"release_sha": "b" * 40},
            {"release_body": "notes <!-- at-build:9002 -->"},
        )
        for overrides in cases:
            with self.subTest(**overrides), tempfile.TemporaryDirectory() as directory:
                result, calls = self.attach_fixture(directory, **overrides)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertIn("::warning::", result.stdout)
                self.assertEqual([], [args for args in calls if args[:2] in (["release", "upload"], ["release", "edit"])])

    def test_publishing_without_a_required_build_leg_is_rejected_before_the_release_exists(self):
        # publish: true with a required leg disabled fails before any release exists — previously the
        # release was created and the manifest step then failed on the missing inventory.
        for flags in (
            {"BUILD_ANDROID": "false"},
            {"BUILD_DESKTOP": "false"},
            {"BUILD_ANDROID": "false", "BUILD_DESKTOP": "false"},
        ):
            with self.subTest(flags=flags), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                result, _ = self.publish_fixture(root, "nightly", flags=flags)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("::error::", result.stdout)
                self.assertFalse((root / "gh-calls").exists(), "no gh call may run before the rejection")

    def test_publishing_with_only_the_optional_ios_leg_disabled_still_ships(self):
        # build_ios=false drops the IPA from the optional inventory; the release still publishes.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            result, _ = self.publish_fixture(
                root, "nightly", drop=("natune-ios-fixture.ipa",), flags={"BUILD_IOS": "false"})
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual("did_publish=true\n", (root / "output").read_text())

    def test_publish_gate_needs_required_results_not_always(self):
        # The publish job no longer fires under always(): it needs !cancelled() and a successful
        # Android plus Windows/Linux result; the macOS and iOS legs stay optional.
        text = WORKFLOW.read_text()
        publish_block = re.split(r"(?m)^  [a-z][a-z-]*:", text.split("  publish-release:", 1)[1], maxsplit=1)[0]
        condition = re.search(r"(?ms)^    if: (.+?)\n    [a-z]", publish_block).group(1)
        self.assertNotIn("always()", condition)
        self.assertIn("!cancelled()", condition)
        self.assertIn("inputs.publish", condition)
        self.assertIn("needs.android.result == 'success'", condition)
        self.assertIn("needs.desktop.result == 'success'", condition)
        self.assertNotIn("needs.desktop-macos.result", condition)
        self.assertNotIn("needs.ios.result", condition)
        # Waiting on the optional legs would let an unavailable macOS runner hold the release.
        for job in ("desktop-macos", "ios"):
            self.assertNotIn("      - " + job + "\n", publish_block)
        attach_block = job_block(text, "attach-optional-assets")
        for job in ("publish-release", "desktop-macos", "ios"):
            self.assertIn("      - " + job + "\n", attach_block)
        self.assertIn("if: ${{ !cancelled() && needs.publish-release.result == 'success' }}", attach_block)
        self.assertIn("contents: write", attach_block)
        feed_block = job_block(text, "publish-update-feed")
        for job in ("desktop-macos", "ios", "attach-optional-assets"):
            self.assertNotIn("      - " + job + "\n", feed_block)

    def test_staging_restore_leaves_optional_legs_to_the_attach_job(self):
        script = step_script("Restore the staging release hand-off")
        self.assertIn("*-desktop-macos-*|*-ios)", script)
        self.assertIn("::error::", script)
        optional = step_script("Restore the optional staging release hand-offs")
        self.assertIn("::warning::", optional)
        self.assertNotIn("::error::", optional)
        removal = dict(step_blocks())["Remove the optional staging release hand-offs"]
        self.assertIn("steps.attach-optional.outcome != 'failure'", removal)

    def test_stale_head_is_not_published(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            result, _ = self.publish_fixture(root, "nightly", stale=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual("did_publish=false\n", (root / "output").read_text())
            self.assertNotIn('"create"', (root / "gh-calls").read_text())

    def test_release_handoff_artifacts_expire_after_three_days(self):
        retention = re.findall(r"retention-days: (\d+)", WORKFLOW.read_text())
        self.assertEqual(["3"] * 5, retention)

    def test_every_artifact_hand_off_has_a_staging_release_fallback(self):
        blocks = step_blocks()
        primaries = [
            (index, name, body) for index, (name, body) in enumerate(blocks)
            if "uses: actions/upload-artifact@" in body or "uses: actions/download-artifact@" in body
        ]
        self.assertEqual(8, len(primaries))
        for index, name, body in primaries:
            with self.subTest(step=name):
                identifier = re.search(r"(?m)^        id: ([\w-]+)$", body)[1]
                self.assertIn("continue-on-error: true", body)
                later = [(other, text) for other, text in blocks[index + 1:] if f"steps.{identifier}.outcome" in text]
                self.assertTrue(later, f"{name} has no fallback step")
                fallback = later[0][1]
                failure_guard = re.search(rf"(?m)^        if: .*steps\.{identifier}\.outcome == 'failure'", fallback)
                if "upload-artifact" in body:
                    # Nothing is duplicated into a release when the artifact upload worked.
                    self.assertIsNotNone(failure_guard, f"{name} fallback is not guarded on the upload outcome")
                    self.assertIn("gh release upload", fallback)
                    self.assertIn("--prerelease", fallback)
                    self.assertNotIn("inputs.publish", fallback)
                else:
                    self.assertTrue(
                        failure_guard is not None
                        or f"ARTIFACTS_OUTCOME: ${{{{ steps.{identifier}.outcome }}}}" in fallback,
                        f"{name} has no guarded fallback",
                    )
                    self.assertIn("gh release download", fallback)
        merge = dict(blocks)["Download build artifacts"]
        self.assertIn("merge-multiple: true", merge)

    def test_only_the_jobs_that_write_release_assets_get_contents_write(self):
        workflow = WORKFLOW.read_text()
        self.assertEqual(7, len(re.findall(r"(?m)^      contents: write$", workflow)))
        self.assertNotIn("contents: read", workflow)

    def test_android_upload_failure_parks_the_apk_on_a_prerelease_hand_off(self):
        name = "Publish Android APK to a staging release when artifact upload fails"
        for exists in (False, True):
            with self.subTest(exists=exists), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                path = staging_tools(root)
                (root / "release-assets").mkdir()
                (root / "release-assets/app-gms-mobile-arm64-nightly.apk").write_bytes(b"unsigned APK fixture")
                result = staging_step(name, root,
                                      GITHUB_RUN_ID="40906373528", GITHUB_REPOSITORY="NatuneGroup/Natune",
                                      GH_TOKEN="", GH_CALLS=str(root / "gh-calls"),
                                      APK_NAME="app-gms-mobile-arm64-nightly.apk", VARIANT="gms-mobile-arm64",
                                      CHANNEL="nightly", COMMIT_SHA="a" * 40,
                                      STAGING_RELEASE_EXISTS="1" if exists else "0", PATH=path)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                calls = [json.loads(line) for line in (root / "gh-calls").read_text().splitlines()]
                tag = "staging-40906373528-android-gms-mobile-arm64"
                self.assertEqual(["release", "view", tag], calls[0][:3])
                creates = [args for args in calls if args[:2] == ["release", "create"]]
                uploads = [args for args in calls if args[:2] == ["release", "upload"]]
                self.assertEqual(1, len(uploads))
                self.assertEqual(["release", "upload", tag], uploads[0][:3])
                self.assertIn("release-assets/app-gms-mobile-arm64-nightly.apk", uploads[0])
                self.assertIn("--clobber", uploads[0])
                if exists:
                    self.assertEqual([], creates)
                else:
                    self.assertEqual(1, len(creates))
                    self.assertEqual(tag, creates[0][2])
                    self.assertIn("--prerelease", creates[0])
                    self.assertIn("--latest=false", creates[0])

    def test_publish_restores_staging_assets_only_when_the_artifact_download_failed(self):
        name = "Restore the staging release hand-off"
        android = "refs/tags/staging-40906373528-android-gms-mobile-arm64"
        ios = "refs/tags/staging-40906373528-ios"
        cases = (
            ("success", (), 0, ()),
            ("failure", (), 1, ()),
            # Optional macOS/iOS hand-offs are left for attach-optional-assets.
            ("failure", (android, ios), 0, ("staging-40906373528-android-gms-mobile-arm64",)),
            ("success", (ios,), 0, ()),
        )
        for outcome, refs, code, expected in cases:
            with self.subTest(outcome=outcome, refs=refs), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                path = staging_tools(root)
                result = staging_step(name, root, GITHUB_RUN_ID="40906373528",
                                      GITHUB_REPOSITORY="NatuneGroup/Natune", GH_TOKEN="",
                                      GH_CALLS=str(root / "gh-calls"), ARTIFACTS_OUTCOME=outcome,
                                      STAGING_REFS="\n".join(refs), PATH=path)
                self.assertEqual(code, result.returncode, result.stdout + result.stderr)
                calls_path = root / "gh-calls"
                calls = [json.loads(line) for line in calls_path.read_text().splitlines()] if calls_path.exists() else []
                downloads = [args[2] for args in calls if args[:2] == ["release", "download"]]
                self.assertEqual(list(expected), downloads)
                staged = sorted(entry.name for entry in (root / "downloaded-artifacts").iterdir())
                self.assertEqual([f"asset-{tag}.apk" for tag in expected], staged)
                if code:
                    self.assertIn("::error::", result.stdout)

    def test_attach_job_restores_only_the_optional_staging_hand_offs(self):
        name = "Restore the optional staging release hand-offs"
        refs = (
            "refs/tags/staging-40906373528-android-gms-mobile-arm64",
            "refs/tags/staging-40906373528-desktop-linux-x64",
            "refs/tags/staging-40906373528-desktop-macos-arm64",
            "refs/tags/staging-40906373528-ios",
            "refs/tags/staging-40906373528-update-feed",
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = staging_tools(root)
            result = staging_step(name, root, GITHUB_RUN_ID="40906373528",
                                  GITHUB_REPOSITORY="NatuneGroup/Natune", GH_TOKEN="",
                                  GH_CALLS=str(root / "gh-calls"), ARTIFACTS_OUTCOME="failure",
                                  STAGING_REFS="\n".join(refs), PATH=path)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            calls = [json.loads(line) for line in (root / "gh-calls").read_text().splitlines()]
            self.assertEqual(
                ["staging-40906373528-desktop-macos-arm64", "staging-40906373528-ios"],
                [args[2] for args in calls if args[:2] == ["release", "download"]],
            )

    def test_update_manifest_rejects_duplicate_asset_names(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for folder in ("a", "b"):
                (root / folder).mkdir()
                (root / folder / "natune-desktop-linux-x64-fixture.deb").write_bytes(b"fixture")
            result = subprocess.run(
                ["python3", str(ROOT / ".github/scripts/update_manifest.py"), "--channel", "nightly",
                 "--tag", "N1", "--version-code", "1", "--commit", "a" * 40,
                 "--published-at", "2030-01-01T00:00:00Z", "--release-url", "https://example.invalid",
                 str(root / "a/natune-desktop-linux-x64-fixture.deb"),
                 str(root / "b/natune-desktop-linux-x64-fixture.deb")],
                capture_output=True, text=True, timeout=30,
            )
            self.assertNotEqual(0, result.returncode)
            self.assertIn("asset names must be unique", result.stderr)

    def test_update_feed_manifest_falls_back_to_its_staging_release(self):
        name = "Restore the update-feed manifest from its staging release"
        for exists, code in ((False, 1), (True, 0)):
            with self.subTest(exists=exists), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                path = staging_tools(root)
                result = staging_step(name, root, GITHUB_RUN_ID="40906373528",
                                      GITHUB_REPOSITORY="NatuneGroup/Natune", GH_TOKEN="",
                                      GH_CALLS=str(root / "gh-calls"), MANIFEST="nightly.json",
                                      STAGING_RELEASE_EXISTS="1" if exists else "0", PATH=path)
                self.assertEqual(code, result.returncode, result.stdout + result.stderr)
                if not exists:
                    self.assertIn("::error::", result.stdout)
                    continue
                self.assertEqual({"nightly.json"}, {entry.name for entry in (root / "feed-artifact").iterdir()})
                calls = [json.loads(line) for line in (root / "gh-calls").read_text().splitlines()]
                download = [args for args in calls if args[:2] == ["release", "download"]][0]
                self.assertEqual("staging-40906373528-update-feed", download[2])
                self.assertEqual("nightly.json", download[download.index("--pattern") + 1])

    def test_published_release_removes_the_staging_hand_off_but_not_the_update_feed_one(self):
        name = "Delete the staging release hand-off"
        # --publish=false smoke runs never reach this step, so their staging releases stay downloadable.
        self.assertIn("if: steps.publish.outputs.did_publish == 'true'", dict(step_blocks())[name])
        refs = (
            "refs/tags/staging-40906373528-android-gms-mobile-arm64",
            "refs/tags/staging-40906373528-desktop-linux-x64",
            "refs/tags/staging-40906373528-desktop-macos-x64",
            "refs/tags/staging-40906373528-ios",
            "refs/tags/staging-40906373528-update-feed",
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = staging_tools(root)
            result = staging_step(name, root, GITHUB_RUN_ID="40906373528",
                                  GITHUB_REPOSITORY="NatuneGroup/Natune", GH_TOKEN="",
                                  GH_CALLS=str(root / "gh-calls"), STAGING_REFS="\n".join(refs), PATH=path)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            calls = [json.loads(line) for line in (root / "gh-calls").read_text().splitlines()]
            deletes = [args for args in calls if args[:2] == ["release", "delete"]]
            self.assertEqual([
                "staging-40906373528-android-gms-mobile-arm64",
                "staging-40906373528-desktop-linux-x64",
            ], [args[2] for args in deletes])
            for args in deletes:
                self.assertIn("--cleanup-tag", args)
                self.assertIn("--yes", args)

    def test_windows_packaging_uses_the_sdk_root_and_windows_launcher_layout(self):
        sdk = step_script("Download WebView2 SDK (Windows)")
        self.assertIn('"WEBVIEW2_SDK=$sdkRoot"', sdk)
        self.assertIn("build\\native\\include\\WebView2.h", sdk)
        stage = step_script("Stage Windows installers and portable ZIP")
        self.assertIn("(Join-Path $appImage 'Natune.exe')", stage)
        self.assertNotIn("bin/Natune.exe", stage)


    def test_desktop_matrix_normalizes_only_the_linux_only_appimage_field(self):
        text = WORKFLOW.read_text()
        macos = job_block(text, "desktop-macos")
        matrix = macos.split("        include:\n", 1)[1].split("    env:\n", 1)[0]
        rows = re.findall(r"(?m)^          - platform: (\w+)\n((?:            [^\n]+\n)+)", matrix)
        self.assertEqual(["macos", "macos"], [platform for platform, _ in rows])
        for _, body in rows:
            self.assertIn("appimage_arch: ''", body)
        desktop = job_block(text, "desktop")
        linux_matrix = desktop.split("        include:\n", 1)[1].split("    env:\n", 1)[0]
        self.assertIn("appimage_arch: x86_64", linux_matrix)
        self.assertIn("appimage_arch: aarch64", linux_matrix)
        self.assertEqual(2, linux_matrix.count("appimage_arch:"))


if __name__ == "__main__":
    unittest.main()
