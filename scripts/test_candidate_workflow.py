"""Secret-free contracts for the opt-in ARM64 release-candidate path.

Standard library only: no JVM, Gradle, Android SDK, network, APK signing or real APK access.
The workflow shell steps run against fake ``gh``/``curl``/SDK tools, and the only APK-ish files
are disposable synthetic ZIPs under a temporary directory.
"""

import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import textwrap
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/build_pull_request.yml"
RELEASE_WORKFLOW = ROOT / ".github/workflows/_release-artifacts.yml"
CANDIDATE_JOB = "android-release-candidate"
SIGNING_NAMES = ("KEYSTORE", "KEY_ALIAS", "KEYSTORE_PASSWORD", "KEY_PASSWORD")
BASELINE_URL = (
    "https://github.com/NatuneGroup/Natune/releases/download/N20261103/"
    "app-gms-mobile-arm64-nightly.apk"
)
BASELINE_SHA256 = "287096e15d568c8ad6e52c8a91dd55c04b5f904ea53d15a2d595ce4e16fb94bc"
BASELINE_BYTES = "20306627"
BASELINE_VERSION_CODE = "1793753723"
BASELINE_SIGNER_SHA256 = "6db8326a550861f0f96b9adcb706207d75ac4480de454fb8af95daa5d6c7f9d9"
SOURCE_SHA = "a" * 40
VERSION_NAME = "candidate-" + SOURCE_SHA[:12]
RESERVED_VERSION_CODE = "1793753800"
PACKAGE = "moe.rukamori.archivetune"
CLIENT_ENV_PREFIXES = (
    "ARCHIVETUNE_",
    "TELEGRAM_",
    "SOURCE_PROVIDER_",
    "POOL_CLIENT_",
    "FEEDBACK_",
    "START_IO_",
)


def workflow_text():
    return WORKFLOW.read_text(encoding="utf-8")


def job_block(job, text=None, workflow=None):
    text = text if text is not None else (workflow or WORKFLOW).read_text(encoding="utf-8")
    marker = f"\n  {job}:\n"
    if marker not in text:
        raise AssertionError(f"No job {job!r}")
    return re.split(r"(?m)^  [a-z][a-z-]*:", text.split(marker, 1)[1], maxsplit=1)[0]


def job_names(text=None):
    text = text if text is not None else workflow_text()
    return re.findall(r"(?m)^  ([a-z][a-z-]*):$", text.split("\njobs:\n", 1)[1])


def step_block(name, job=CANDIDATE_JOB, text=None):
    scope = job_block(job, text) if job else (text if text is not None else workflow_text())
    marker = f"      - name: {name}\n"
    if marker not in scope:
        raise AssertionError(f"No step {name!r} in job {job!r}")
    body = scope.split(marker, 1)[1]
    return re.split(r"(?m)^      - |^  [a-z][a-z-]*:\n", body, maxsplit=1)[0]


def run_script(name, job=CANDIDATE_JOB, text=None):
    body = step_block(name, job, text)
    run = re.search(r"(?m)^        run: \|\n((?: {10}.*\n|\n)+)", body)
    if run is None:
        raise AssertionError(f"No block script in step {name!r}")
    return textwrap.dedent(run[1])


def parse_if(job, text=None):
    body = job_block(job, text)
    folded = re.search(r"(?m)^    if: >-\n((?: {6}.*\n)+)", body)
    if folded:
        return " ".join(line.strip() for line in folded.group(1).splitlines())
    plain = re.search(r"(?m)^    if: (.+)$", body)
    if plain is None:
        raise AssertionError(f"No job-level if in {job!r}")
    return plain.group(1).strip()


def condition_holds(expression, *, event, desktop=False, candidate=False, quality="",
                    repo="NatuneGroup/Natune", ref_type="branch"):
    dispatch = event == "workflow_dispatch"
    values = {
        "github.event_name": repr(event),
        "github.repository": repr(repo),
        "github.ref_type": repr(ref_type),
        "inputs.desktop_app_image": ("True" if desktop else "False") if dispatch else "None",
        "inputs.android_release_candidate": ("True" if candidate else "False") if dispatch else "None",
        "inputs.quality_base": repr(quality) if dispatch else "None",
    }
    translated = expression
    for identifier in sorted(values, key=len, reverse=True):
        translated = translated.replace(identifier, values[identifier])
    translated = translated.replace("&&", " and ").replace("||", " or ")
    translated = re.sub(r"!(?!=)", " not ", translated)
    translated = re.sub(r"\btrue\b", "True", translated)
    translated = re.sub(r"\bfalse\b", "False", translated)
    return bool(eval(translated, {"__builtins__": {}}, {}))


def env_keys(step_text):
    if "        env:\n" not in step_text:
        return set()
    tail = step_text.split("        env:\n", 1)[1]
    tail = re.split(r"(?m)^        (?:run|uses|with|if|shell):", tail, maxsplit=1)[0]
    return set(re.findall(r"(?m)^          ([A-Za-z_][A-Za-z0-9_]*):", tail))


def release_android_env_keys():
    android = job_block("android", workflow=RELEASE_WORKFLOW)
    tail = android.split("    env:\n", 1)[1].split("    steps:", 1)[0]
    return set(re.findall(r"(?m)^      ([A-Za-z_][A-Za-z0-9_]*):", tail))


def tool(directory, name, source):
    path = Path(directory) / name
    path.write_text(textwrap.dedent(source).lstrip(), encoding="utf-8")
    path.chmod(0o755)
    return path


def bash(script, directory, env_overrides=None, extra_path=None, timeout=30):
    env = os.environ.copy()
    for name in SIGNING_NAMES:
        env.pop(name, None)
    if extra_path:
        env["PATH"] = str(extra_path) + os.pathsep + env["PATH"]
    env.update(env_overrides or {})
    return subprocess.run(
        ["bash", "-c", script], cwd=directory, env=env,
        capture_output=True, text=True, timeout=timeout,
    )


def write_zip(path, extra=()):
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("AndroidManifest.xml", b"binary-manifest")
        archive.writestr("classes.dex", b"dex")
        archive.writestr("resources.arsc", b"arsc")
        archive.writestr("lib/arm64-v8a/libc++_shared.so", b"native")
        for name in extra:
            archive.writestr(name, b"optional")
    return path


def badging(version_code=RESERVED_VERSION_CODE, version_name=VERSION_NAME,
            package=PACKAGE, min_sdk="24", native_code="'arm64-v8a'", debuggable=False):
    lines = [
        f"package: name='{package}' versionCode='{version_code}' versionName='{version_name}' "
        "platformBuildVersionName='15' platformBuildVersionCode='36' compileSdkVersion='37' "
        "compileSdkVersionCodename='17'",
        f"sdkVersion:'{min_sdk}'",
        "targetSdkVersion:'37'",
    ]
    if debuggable:
        lines.append("application-debuggable")
    if native_code:
        lines.append(f"native-code: {native_code}")
    return "\n".join(lines) + "\n"


def apk_metadata(version_code=RESERVED_VERSION_CODE, version_name=VERSION_NAME,
                 package=PACKAGE, variant="gmsMobileArm64Release",
                 output_file="app-gms-mobile-arm64-release.apk"):
    return {
        "version": 3,
        "artifactType": {"type": "APK", "kind": "Directory"},
        "applicationId": package,
        "variantName": variant,
        "elements": [
            {
                "type": "SINGLE",
                "filters": [],
                "attributes": [],
                "versionCode": int(version_code),
                "versionName": version_name,
                "outputFile": output_file,
            }
        ],
    }


class WorkflowStructureTest(unittest.TestCase):
    def test_inputs_default_off_and_full_mode_matrix_keep_their_paths(self):
        text = workflow_text()
        on_block = text.split("\non:\n", 1)[1].split("\npermissions:\n", 1)[0]
        quality_input = on_block.split("      quality_base:\n", 1)[1].split("      # Artifact-only", 1)[0]
        self.assertIn("type: string", quality_input)
        self.assertIn("default: ''", quality_input)
        candidate_input = on_block.split("      android_release_candidate:\n", 1)[1].split(
            "      candidate_version_code:\n", 1
        )[0]
        self.assertIn("type: boolean", candidate_input)
        self.assertIn("default: false", candidate_input)
        version_input = on_block.split("      candidate_version_code:\n", 1)[1]
        self.assertIn("type: string", version_input)
        self.assertIn("default: ''", version_input)

        build = parse_if("build")
        desktop = parse_if("desktop-compile")
        quality = parse_if("kotlin-quality")
        candidate = parse_if(CANDIDATE_JOB)
        base = "a" * 40
        cases = [
            # event, desktop, candidate, quality, build, desktop, quality, candidate
            ("pull_request", False, False, "", True, True, False, False),
            ("push", False, False, "", True, True, False, False),
            ("workflow_dispatch", False, False, "", True, True, False, False),
            ("workflow_dispatch", True, False, "", False, True, False, False),
            ("workflow_dispatch", False, True, "", False, False, False, True),
            ("workflow_dispatch", False, False, base, False, False, True, False),
            ("workflow_dispatch", False, True, base, False, False, True, False),
            ("workflow_dispatch", True, False, base, False, False, True, False),
            ("workflow_dispatch", True, True, "", False, False, False, True),
            ("workflow_dispatch", True, True, base, False, False, True, False),
        ]
        for event, desktop_image, candidate_flag, quality_base, build_ok, desktop_ok, quality_ok, candidate_ok in cases:
            with self.subTest(event=event, desktop=desktop_image, candidate=candidate_flag, quality=quality_base):
                self.assertIs(build_ok, condition_holds(
                    build, event=event, desktop=desktop_image, candidate=candidate_flag,
                    quality=quality_base))
                self.assertIs(desktop_ok, condition_holds(
                    desktop, event=event, desktop=desktop_image, candidate=candidate_flag,
                    quality=quality_base))
                self.assertIs(quality_ok, condition_holds(
                    quality, event=event, desktop=desktop_image, candidate=candidate_flag,
                    quality=quality_base))
                self.assertIs(candidate_ok, condition_holds(
                    candidate, event=event, desktop=desktop_image, candidate=candidate_flag,
                    quality=quality_base))
        self.assertIs(False, condition_holds(
            candidate, event="workflow_dispatch", candidate=True, repo="Someone/Fork"))
        # A tag dispatch still enables the job so its first step can fail closed; it must not leave
        # every job skipped as a green no-op.
        self.assertIs(True, condition_holds(
            candidate, event="workflow_dispatch", candidate=True, ref_type="tag"))
        self.assertIn("REF_TYPE", run_script("Validate candidate dispatch and signing inputs"))
        self.assertEqual(
            ["build", "kotlin-quality", "desktop-compile", CANDIDATE_JOB], job_names(text))

    def test_candidate_job_is_manual_same_repository_and_bounded(self):
        text = workflow_text()
        candidate = job_block(CANDIDATE_JOB, text)
        guard = parse_if(CANDIDATE_JOB, text)
        self.assertIn("github.event_name == 'workflow_dispatch'", guard)
        self.assertIn("github.repository == 'NatuneGroup/Natune'", guard)
        self.assertIn("inputs.android_release_candidate == true", guard)
        self.assertIn("inputs.quality_base == ''", guard)
        self.assertNotIn("pull_request", candidate)
        self.assertIn("runs-on: blacksmith-8vcpu-ubuntu-2404", candidate)
        self.assertIn("timeout-minutes: 30", candidate)
        self.assertNotIn("    permissions:", candidate)
        self.assertRegex(text, r"(?m)^permissions:\n  contents: read\n")

    def test_candidate_job_has_no_publication_release_helper_or_fallback(self):
        candidate = job_block(CANDIDATE_JOB)
        for forbidden in (
            "_release-artifacts.yml",
            "gh release",
            "gh api --method",
            "git push",
            "git tag",
            "staging-",
            "contents: write",
            "continue-on-error",
            "release create",
            "release upload",
            "update_manifest",
            "publish:",
        ):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, candidate)

    def test_upload_is_required_single_apk_with_short_retention(self):
        upload = step_block("Upload the signed candidate APK and its metadata")
        self.assertIn("if-no-files-found: error", upload)
        self.assertIn("retention-days: 3", upload)
        self.assertNotIn("continue-on-error", upload)
        self.assertIn(
            "${{ runner.temp }}/natune-candidate/natune-android-arm64-release-candidate.apk",
            upload)
        self.assertEqual(1, upload.count(".apk"))

    def test_exact_dispatch_source_is_checked_out_and_asserted(self):
        checkout = step_block("Check out the exact reviewed dispatch commit")
        self.assertIn("ref: ${{ github.sha }}", checkout)
        self.assertIn("submodules: recursive", checkout)
        self.assertIn("persist-credentials: false", checkout)
        self.assertNotIn("candidate_sha", workflow_text())
        assertion = run_script("Assert the checked-out source is the dispatched commit")
        self.assertIn("git rev-parse HEAD", assertion)
        self.assertIn("EXPECTED_SHA", assertion)
        self.assertIn("SOURCE_SHA=$ACTUAL_SHA", assertion)

    def test_pinned_public_baseline_constants_are_exact(self):
        text = workflow_text()
        self.assertIn(BASELINE_URL, text)
        self.assertIn(BASELINE_SHA256, text)
        self.assertIn(f"BASELINE_BYTES: '{BASELINE_BYTES}'", text)
        self.assertIn(f"BASELINE_VERSION_CODE: '{BASELINE_VERSION_CODE}'", text)
        self.assertIn(BASELINE_SIGNER_SHA256, text)
        baseline = step_block("Verify the trusted public baseline before trusting its signer")
        self.assertIn(f"EXPECTED_SIGNER_SHA256: {BASELINE_SIGNER_SHA256}", baseline)
        signing = step_block("Sign and verify the candidate APK")
        self.assertIn(f"EXPECTED_SIGNER_SHA256: {BASELINE_SIGNER_SHA256}", signing)
        gate = step_block("Validate the reserved version above source and published floors")
        self.assertIn(f"BASELINE_VERSION_CODE: '{BASELINE_VERSION_CODE}'", gate)
        final = step_block("Record the final signed APK metadata, checksum and size report")
        self.assertIn(f"SIGNER_SHA256: {BASELINE_SIGNER_SHA256}", final)
        self.assertIn(f"BASELINE_SHA256: {BASELINE_SHA256}", final)
        self.assertIn(f"BASELINE_BYTES: '{BASELINE_BYTES}'", final)
        self.assertIn(f"BASELINE_VERSION_CODE: '{BASELINE_VERSION_CODE}'", final)

    def test_published_version_acquisition_is_complete_and_file_backed(self):
        gate = step_block("Validate the reserved version above source and published floors")
        self.assertIn('mktemp "$RUNNER_TEMP/candidate-release-bodies.XXXXXX"', gate)
        self.assertIn('> "$RELEASE_BODIES"', gate)
        self.assertIn("if ! gh api --paginate", gate)
        self.assertIn("API read did not complete", gate)
        self.assertIn("declaration.fullmatch", gate)
        self.assertNotIn("|| true", gate)
        self.assertNotIn("grep -oE 'at-build:", gate)
        # Release notes stay in the temporary file, not an environment string.
        self.assertNotIn("GH_BODIES", gate)
        self.assertNotIn('PUBLISHED_MAX="$(gh api', gate)
        self.assertIn('variant_name != "gmsMobileArm64Release"', workflow_text())
        self.assertIn('for expected in ("gms", "mobile", "arm64", "release")',
                      workflow_text())

    def test_declared_quality_and_candidate_inputs_and_manual_quality_guard(self):
        text = workflow_text()
        on_block = text.split("\non:\n", 1)[1].split("\npermissions:\n", 1)[0]
        quality_input = on_block.split("      quality_base:\n", 1)[1].split("      # Artifact-only", 1)[0]
        self.assertIn("description: 'Quality-only manual run", quality_input)
        self.assertIn("type: string", quality_input)
        self.assertIn("default: ''", quality_input)
        self.assertIn("      android_release_candidate:\n", on_block)
        self.assertIn("      candidate_version_code:\n", on_block)
        self.assertIn("inputs.quality_base != ''", parse_if("kotlin-quality"))
        self.assertIn("inputs.quality_base == ''", parse_if(CANDIDATE_JOB))
        self.assertIn("inputs.android_release_candidate != true", parse_if("desktop-compile"))
        self.assertIn("inputs.quality_base == ''", parse_if("desktop-compile"))
        self.assertEqual(
            ["build", "kotlin-quality", "desktop-compile", CANDIDATE_JOB], job_names(text))

    def test_fixed_release_task_and_standard_nightly_shape(self):
        assemble = step_block("Assemble only the GMS mobile ARM64 release APK")
        script = run_script("Assemble only the GMS mobile ARM64 release APK")
        self.assertIn(":app:assembleGmsMobileArm64Release", script)
        self.assertIn("--no-daemon --max-workers=2", script)
        self.assertIn("-Pautomix=false -PslimTdlib=true", script)
        self.assertNotIn("Debug", script)
        self.assertNotIn("qaOptimized", script)
        self.assertNotIn("assembleGmsMobileUniversalDebug", script)
        self.assertIn("IS_NIGHTLY_BUILD: 'true'", assemble)
        self.assertIn("NIGHTLY_BUILD_HASH: ${{ github.sha }}", assemble)
        self.assertIn("PULL_REQUEST: 'false'", assemble)
        self.assertIn('VERSION_NAME_OVERRIDE="candidate-${SOURCE_SHA:0:12}"', script)
        self.assertNotIn("1793753723", script)
        self.assertNotIn("VERSION_CODE_OVERRIDE: 1793", assemble)

    def test_provider_environment_matches_the_release_android_leg(self):
        assemble = step_block("Assemble only the GMS mobile ARM64 release APK")
        candidate_keys = {
            key for key in env_keys(assemble) if key.startswith(CLIENT_ENV_PREFIXES)
        }
        release_keys = {
            key for key in release_android_env_keys() if key.startswith(CLIENT_ENV_PREFIXES)
        }
        self.assertEqual(release_keys, candidate_keys)

    def test_signing_secrets_are_scoped_to_the_manual_candidate_job(self):
        candidate = job_block(CANDIDATE_JOB)
        self.assertIn("KEYSTORE: ${{ secrets.KEYSTORE }}", candidate)
        self.assertIn("KEY_ALIAS: ${{ secrets.KEY_ALIAS }}", candidate)
        self.assertIn("KEYSTORE_PASSWORD: ${{ secrets.KEYSTORE_PASSWORD }}", candidate)
        self.assertIn("KEY_PASSWORD: ${{ secrets.KEY_PASSWORD }}", candidate)
        # The existing default jobs keep their original conditions; only candidate mode changes them.
        self.assertIn("inputs.desktop_app_image != true", parse_if("build"))
        self.assertNotIn("KEYSTORE", job_block("build"))
        self.assertNotIn("KEYSTORE", job_block("desktop-compile"))


class CandidatePreflightTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.script = run_script("Validate candidate dispatch and signing inputs")

    def preflight(self, **overrides):
        env = {
            "CANDIDATE": "true",
            "REF_TYPE": "branch",
            "DESKTOP_APP_IMAGE": "false",
            "QUALITY_BASE": "",
            "CANDIDATE_VERSION_CODE": RESERVED_VERSION_CODE,
            "KEYSTORE": "fixture-keystore-value",
            "KEY_ALIAS": "fixture-alias-value",
            "KEYSTORE_PASSWORD": "fixture-store-password",
            "KEY_PASSWORD": "fixture-key-password",
        }
        env.update(overrides)
        return bash(self.script, ROOT, env)

    def test_complete_dispatch_passes_without_printing_secret_values(self):
        result = self.preflight()
        self.assertEqual(0, result.returncode, result.stderr)
        for value in (
            "fixture-keystore-value", "fixture-alias-value",
            "fixture-store-password", "fixture-key-password",
        ):
            self.assertNotIn(value, result.stdout + result.stderr)

    def test_each_missing_signing_secret_fails_closed(self):
        for name in SIGNING_NAMES:
            with self.subTest(missing=name):
                result = self.preflight(**{name: ""})
                self.assertNotEqual(0, result.returncode)
                self.assertIn(name, result.stdout + result.stderr)

    def test_candidate_and_desktop_image_are_mutually_exclusive(self):
        result = self.preflight(DESKTOP_APP_IMAGE="true")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("cannot be combined with desktop_app_image or quality_base", result.stdout + result.stderr)

    def test_candidate_and_quality_are_mutually_exclusive(self):
        result = self.preflight(QUALITY_BASE="a" * 40)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("cannot be combined with desktop_app_image or quality_base", result.stdout + result.stderr)

    def test_candidate_mode_requires_a_branch_ref(self):
        result = self.preflight(REF_TYPE="tag")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("dispatched from a reviewed branch", result.stdout + result.stderr)

    def test_candidate_flag_must_be_true(self):
        result = self.preflight(CANDIDATE="false")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("android_release_candidate must be true", result.stdout + result.stderr)

    def test_version_code_must_be_a_reserved_positive_integer(self):
        for value in ("", "0", "0150", "1.5", "abc", "-1", " 123", "2147483648",
                      "99999999999999999999"):
            with self.subTest(value=value):
                result = self.preflight(CANDIDATE_VERSION_CODE=value)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("candidate_version_code", result.stdout + result.stderr)
        for value in ("6834", "1793753724", "2147483647"):
            with self.subTest(value=value):
                self.assertEqual(0, self.preflight(CANDIDATE_VERSION_CODE=value).returncode)


class QualityPreflightTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.script = run_script("Reject incompatible manual modes", job="kotlin-quality")

    def preflight(self, quality="a" * 40, desktop="false", candidate=""):
        return bash(self.script, ROOT, {
            "QUALITY_BASE_VALUE": quality,
            "DESKTOP_APP_IMAGE_VALUE": desktop,
            "INPUTS_JSON": json.dumps({"android_release_candidate": candidate}),
        })

    def test_quality_only_mode_passes(self):
        result = self.preflight()
        self.assertEqual(0, result.returncode, result.stderr)

    def test_quality_rejects_desktop_and_candidate_in_preflight(self):
        for desktop, candidate, label in (
            ("true", "", "desktop"),
            ("false", "true", "candidate"),
            ("true", "true", "both"),
        ):
            with self.subTest(mode=label):
                result = self.preflight(desktop=desktop, candidate=candidate)
                self.assertNotEqual(0, result.returncode)
                self.assertIn(
                    "cannot be combined with desktop_app_image or android_release_candidate",
                    result.stdout + result.stderr,
                )

    def test_quality_ignores_non_boolean_false_candidate_text(self):
        self.assertEqual(0, self.preflight(candidate="false").returncode)


class BuildPrCacheProofTest(unittest.TestCase):
    STEP = "Verify the produced Linux native dependency prefix"

    def test_proof_is_guarded_bounded_nonblocking_and_honest(self):
        step = step_block(self.STEP, job="desktop-compile")
        self.assertIn("if: inputs.desktop_app_image == true", step)
        self.assertIn("timeout-minutes: 2", step)
        self.assertIn("continue-on-error: true", step)
        self.assertIn("LC_ALL: C", step)
        script = run_script(self.STEP, job="desktop-compile")
        self.assertIn("manifest --kind desktop --platform linux --target-arch x64", script)
        self.assertIn("verify --kind desktop --platform linux --target-arch x64", script)
        self.assertIn("desktopApp/build/native/.native-deps-cache.json", script)
        self.assertIn('echo "NATIVE CACHE VERIFICATION: $status" >> "$GITHUB_STEP_SUMMARY"', script)
        self.assertIn("VERIFIED (identity, inventory and ABI only", script)
        self.assertIn("UNVERIFIED (the real Linux prefix", script)
        self.assertNotIn("cache/save", script)
        self.assertNotIn("build-native-deps.sh", script)

    def run_proof(self, directory, ok):
        root = Path(directory)
        bin_dir = root / "bin"
        bin_dir.mkdir()
        tool(bin_dir, "python3", """
            #!/usr/bin/python3
            import os, pathlib, sys
            args = sys.argv[1:]
            ok = os.environ.get("FAKE_CACHE_OK") == "1"
            if "manifest" in args:
                if ok:
                    path = pathlib.Path("desktopApp/build/native/.native-deps-cache.json")
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text("{}")
                sys.exit(0 if ok else 1)
            sys.exit(0 if ok else 1)
        """)
        runner_temp = root / "runner-temp"
        runner_temp.mkdir()
        summary = root / "summary"
        result = bash(
            run_script(self.STEP, job="desktop-compile"),
            root,
            env_overrides={
                "RUNNER_TEMP": str(runner_temp),
                "GITHUB_STEP_SUMMARY": str(summary),
                "FAKE_CACHE_OK": "1" if ok else "0",
            },
            extra_path=bin_dir,
        )
        return result, summary.read_text() if summary.exists() else ""

    def test_failure_is_visible_unverified_and_does_not_fail_the_step(self):
        with tempfile.TemporaryDirectory() as directory:
            result, summary = self.run_proof(directory, ok=False)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("UNVERIFIED", result.stdout + result.stderr)
            self.assertIn("UNVERIFIED", summary)
            self.assertNotIn("VERIFIED (identity", summary)

    def test_success_reports_only_identity_inventory_and_abi(self):
        with tempfile.TemporaryDirectory() as directory:
            result, summary = self.run_proof(directory, ok=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("VERIFIED", result.stdout + result.stderr)
            self.assertIn("identity, inventory and ABI only", summary)
            self.assertIn("no reuse, speed-up or runtime proof", summary)


class VersionGateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.script = run_script("Validate the reserved version above source and published floors")

    def gate(self, version, bodies, *, gh_fail=False, floor=6833):
        directory = Path(self.enterContext(tempfile.TemporaryDirectory()))
        (directory / "app").mkdir()
        (directory / "app/build.gradle.kts").write_text(
            f'val baseVersionName = "15.0.0"\nval trustedUpgradeVersionCodeFloor = {floor}\n',
            encoding="utf-8",
        )
        runner_temp = directory / "runner"
        runner_temp.mkdir()
        bin_dir = directory / "bin"
        bin_dir.mkdir()
        tool(bin_dir, "gh", """
            #!/usr/bin/env python3
            import os
            import sys
            print(os.environ.get("GH_BODIES", ""))
            if os.environ.get("GH_FAIL") == "1":
                sys.exit(1)
        """)
        return bash(
            self.script, directory,
            {
                "CANDIDATE_VERSION_CODE": str(version),
                "BASELINE_VERSION_CODE": BASELINE_VERSION_CODE,
                "GITHUB_REPOSITORY": "NatuneGroup/Natune",
                "RUNNER_TEMP": str(runner_temp),
                "GH_FAIL": "1" if gh_fail else "0",
                "GH_BODIES": "\n".join(bodies),
            },
            extra_path=bin_dir,
        )

    def test_version_above_all_floors_passes(self):
        result = self.gate(1793753724, ["<!-- at-build:1793753723 -->"])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("newest_published_version_code=1793753723", result.stdout)

    def test_equal_or_lower_source_baseline_and_published_codes_fail(self):
        cases = [
            (6833, ["<!-- at-build:1793753723 -->"], "source floor"),
            (1793753723, ["<!-- at-build:1793753723 -->"], "measured baseline"),
            (1793753724, ["<!-- at-build:1800000000 -->"], "newest published build"),
        ]
        for version, bodies, message in cases:
            with self.subTest(version=version):
                result = self.gate(version, bodies)
                self.assertNotEqual(0, result.returncode)
                self.assertIn(message, result.stdout + result.stderr)

    def test_partial_api_output_then_failure_never_parses(self):
        result = self.gate(1793753724, ["<!-- at-build:1793753723 -->"], gh_fail=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("API read did not complete", result.stdout + result.stderr)
        self.assertNotIn("newest_published_version_code", result.stdout)

    def test_api_failure_without_output_fails_closed(self):
        result = self.gate(1793753724, [], gh_fail=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("API read did not complete", result.stdout + result.stderr)

    def test_empty_or_no_usable_published_marker_fails_closed(self):
        for bodies in ([], [""], ["no marker"], ["null"]):
            with self.subTest(bodies=bodies):
                result = self.gate(1793753724, bodies)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("no usable build declaration", result.stdout + result.stderr)

    def test_maximum_published_marker_wins_across_channels(self):
        result = self.gate(
            1900000000,
            [
                "stable notes\n<!-- at-build:1700000000 -->\nmore notes",
                "nightly notes\n<!-- at-build:1793753723 -->",
                "<!-- at-build:1800000000 -->",
            ],
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("newest_published_version_code=1800000000", result.stdout)

    def test_malformed_marker_next_to_a_valid_marker_fails_closed(self):
        for malformed in (
            "<!-- at-build:+1800000000 -->",
            "<!-- at-build:1793753723garbage -->",
            "<!-- at-build:1793753723 --> trailing",
        ):
            with self.subTest(malformed=malformed):
                result = self.gate(1793753724, ["<!-- at-build:1793753723 -->", malformed])
                self.assertNotEqual(0, result.returncode)
                self.assertIn("malformed at-build declaration", result.stdout + result.stderr)
                self.assertNotIn("newest_published_version_code", result.stdout)

    def test_malformed_zero_negative_or_nondecimal_markers_fail_closed(self):
        for marker in (
            "<!-- at-build:0 -->",
            "<!-- at-build:-1 -->",
            "<!-- at-build:1.5 -->",
            "<!-- at-build:abc -->",
            "<!-- at-build:01793753723 -->",
            "<!-- at-build: 1793753723 -->",
            "at-build:1793753723",
            "prefix <!-- at-build:1793753723 -->",
            "<!-- at-build:1793753723",
            "<!-- at-build:1793753723 ->",
        ):
            with self.subTest(marker=marker):
                result = self.gate(2147483647, [marker])
                self.assertNotEqual(0, result.returncode)
                self.assertIn("malformed at-build declaration", result.stdout + result.stderr)

    def test_out_of_range_published_markers_fail_closed(self):
        for marker in (
            "<!-- at-build:2147483648 -->",
            "<!-- at-build:99999999999999999999 -->",
        ):
            with self.subTest(marker=marker):
                result = self.gate(2147483647, [marker])
                self.assertNotEqual(0, result.returncode)
                self.assertIn("positive Int range", result.stdout + result.stderr)

    def test_source_floor_is_read_from_the_current_build_script(self):
        result = self.gate(6999, ["<!-- at-build:1793753723 -->"], floor=7000)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("source floor 7000", result.stdout + result.stderr)


class BaselineSignerGateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.script = run_script("Verify the trusted public baseline before trusting its signer")

    def gate(self, *, body=None, expected_sha=None, expected_size=None,
             expected_version=BASELINE_VERSION_CODE, fake_version=None,
             expected_signer=BASELINE_SIGNER_SHA256, fake_signer=None,
             verify_fail=False):
        body = body if body is not None else b"fixture public baseline"
        sha = expected_sha if expected_sha is not None else hashlib.sha256(body).hexdigest()
        size = expected_size if expected_size is not None else str(len(body))
        fake_version = fake_version if fake_version is not None else expected_version
        fake_signer = fake_signer if fake_signer is not None else expected_signer
        directory = Path(self.enterContext(tempfile.TemporaryDirectory()))
        runner_temp = directory / "runner"
        runner_temp.mkdir()
        tools = directory / "build-tools"
        tools.mkdir()
        tool(tools, "aapt2", """
            #!/usr/bin/env python3
            import os
            print(f"package: name='moe.rukamori.archivetune' "
                  f"versionCode='{os.environ['FAKE_BASELINE_VERSION']}'")
        """)
        tool(tools, "apksigner", """
            #!/usr/bin/env python3
            import os
            import sys
            if os.environ.get("BASELINE_VERIFY_FAIL") == "1":
                print("DOES NOT VERIFY")
                sys.exit(1)
            print("Signer #1 certificate DN: CN=fixture-baseline-owner")
            print("Signer #1 certificate SHA-256 digest: " + os.environ["FAKE_BASELINE_SIGNER"])
        """)
        bin_dir = directory / "bin"
        bin_dir.mkdir()
        tool(bin_dir, "curl", """
            #!/usr/bin/env python3
            import os
            import sys
            args = sys.argv[1:]
            output = args[args.index("--output") + 1]
            with open(output, "wb") as baseline:
                baseline.write(os.environ["CURL_BODY"].encode())
        """)
        result = bash(
            self.script, directory,
            {
                "RUNNER_TEMP": str(runner_temp),
                "BUILD_TOOLS": str(tools),
                "BASELINE_URL": BASELINE_URL,
                "BASELINE_SHA256": sha,
                "BASELINE_BYTES": size,
                "BASELINE_VERSION_CODE": expected_version,
                "EXPECTED_SIGNER_SHA256": expected_signer,
                "CURL_BODY": body.decode(),
                "FAKE_BASELINE_VERSION": fake_version,
                "FAKE_BASELINE_SIGNER": fake_signer,
                "BASELINE_VERIFY_FAIL": "1" if verify_fail else "0",
            },
            extra_path=bin_dir,
        )
        return result, runner_temp

    def test_pinned_bytes_version_and_verified_signer_pass_without_logging_dn(self):
        result, runner_temp = self.gate()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("Verified the pinned public baseline", result.stdout)
        self.assertNotIn("fixture-baseline-owner", result.stdout + result.stderr)
        self.assertFalse((runner_temp / "public-baseline-arm64-nightly.apk").exists())

    def test_wrong_baseline_bytes_fail_before_apksigner(self):
        result, _ = self.gate(body=b"different bytes", expected_sha="0" * 64)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("does not match the pinned bytes and SHA-256", result.stdout + result.stderr)

    def test_wrong_baseline_version_code_fails(self):
        result, _ = self.gate(fake_version="1700000000")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("expected versionCode", result.stdout + result.stderr)

    def test_unverified_baseline_signature_fails(self):
        result, _ = self.gate(verify_fail=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("did not verify", result.stdout + result.stderr)

    def test_baseline_certificate_must_match_the_reviewed_pin(self):
        result, _ = self.gate(fake_signer="0" * 64)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("does not match the reviewed signer pin", result.stdout + result.stderr)
        self.assertNotIn("fixture-baseline-owner", result.stdout + result.stderr)


class HeadAssertionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.script = run_script("Assert the checked-out source is the dispatched commit")

    def assert_head(self, fake_head, expected=SOURCE_SHA):
        directory = Path(self.enterContext(tempfile.TemporaryDirectory()))
        bin_dir = directory / "bin"
        bin_dir.mkdir()
        tool(bin_dir, "git", """
            #!/bin/sh
            echo "$FAKE_GIT_HEAD"
        """)
        github_env = directory / "github-env"
        github_env.write_text("", encoding="utf-8")
        result = bash(
            self.script, directory,
            {
                "EXPECTED_SHA": expected,
                "DISPATCH_REF": "refs/heads/ci/android-arm64-candidate",
                "FAKE_GIT_HEAD": fake_head,
                "GITHUB_ENV": str(github_env),
            },
            extra_path=bin_dir,
        )
        return result, github_env

    def test_matching_exact_head_is_recorded(self):
        result, github_env = self.assert_head(SOURCE_SHA)
        self.assertEqual(0, result.returncode, result.stderr)
        recorded = github_env.read_text(encoding="utf-8")
        self.assertIn(f"SOURCE_SHA={SOURCE_SHA}", recorded)
        self.assertIn("DISPATCH_REF=refs/heads/ci/android-arm64-candidate", recorded)

    def test_wrong_or_short_head_fails(self):
        for fake_head, expected in (("b" * 40, SOURCE_SHA), (SOURCE_SHA[:12], SOURCE_SHA)):
            with self.subTest(fake_head=fake_head):
                result, _ = self.assert_head(fake_head, expected)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("not the dispatched commit", result.stdout + result.stderr)


class StageValidationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.script = run_script("Stage and validate the assembled release APK")

    def stage(self, *, badging_text=None, extra_entries=(), metadata=None, path=None,
              apk_count=1):
        directory = Path(self.enterContext(tempfile.TemporaryDirectory()))
        runner_temp = directory / "runner"
        runner_temp.mkdir()
        apk_dir = directory / "app/build/outputs/apk" / (path or "gms/mobile/arm64/release")
        output_file = "app-gms-mobile-arm64-release.apk"
        apk = write_zip(apk_dir / output_file, extra_entries)
        for index in range(1, apk_count):
            write_zip(apk_dir / f"extra-{index}.apk")
        metadata_document = metadata if metadata is not None else apk_metadata()
        (apk_dir / "output-metadata.json").write_text(
            json.dumps(metadata_document), encoding="utf-8")
        badging_file = directory / "badging.txt"
        badging_file.write_text(badging_text if badging_text is not None else badging(),
                                encoding="utf-8")
        tools = directory / "build-tools"
        tools.mkdir()
        tool(tools, "aapt2", """
            #!/usr/bin/env python3
            import os
            print(open(os.environ["FAKE_BADGING"], encoding="utf-8").read(), end="")
        """)
        github_env = directory / "github-env"
        github_env.write_text("", encoding="utf-8")
        result = bash(
            self.script, directory,
            {
                "BUILD_TOOLS": str(tools),
                "RUNNER_TEMP": str(runner_temp),
                "GITHUB_ENV": str(github_env),
                "CANDIDATE_VERSION_CODE": RESERVED_VERSION_CODE,
                "SOURCE_SHA": SOURCE_SHA,
                "FAKE_BADGING": str(badging_file),
            },
        )
        return result, apk, runner_temp, github_env

    def test_single_valid_release_apk_is_staged(self):
        result, apk, runner_temp, github_env = self.stage()
        self.assertEqual(0, result.returncode, result.stderr)
        staged = runner_temp / "natune-candidate/natune-android-arm64-release-candidate.apk"
        self.assertTrue(staged.is_file())
        self.assertEqual(apk.read_bytes(), staged.read_bytes())
        recorded = github_env.read_text(encoding="utf-8")
        self.assertIn(f"CANDIDATE_APK={staged}", recorded)

    def test_wrong_package_version_min_sdk_abi_or_debuggable_fails(self):
        cases = {
            "package": badging(package="com.example.app"),
            "version code": badging(version_code="1700000000"),
            "version name": badging(version_name="15.0.0"),
            "min sdk": badging(min_sdk="26"),
            "abi": badging(native_code="'x86_64'"),
            "debuggable": badging(debuggable=True),
        }
        for label, text in cases.items():
            with self.subTest(label=label):
                result, _, _, _ = self.stage(badging_text=text)
                self.assertNotEqual(0, result.returncode)

    def test_optional_models_or_tdlib_entries_fail_the_slim_shape(self):
        for entry in ("assets/beat_this_int8.onnx", "lib/arm64-v8a/libonnxruntime.so",
                      "lib/arm64-v8a/libtdjni.so"):
            with self.subTest(entry=entry):
                result, _, _, _ = self.stage(extra_entries=(entry,))
                self.assertNotEqual(0, result.returncode)
                self.assertIn("optional model/TDLib", result.stdout + result.stderr)

    def test_wrong_apk_count_or_non_release_path_fails(self):
        result, _, _, _ = self.stage(apk_count=2)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Expected exactly one release APK", result.stdout + result.stderr)
        result, _, _, _ = self.stage(path="gms/mobile/arm64/debug")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("is not a Release APK", result.stdout + result.stderr)

    def test_agp_metadata_must_describe_the_gms_arm64_release(self):
        cases = {
            "foss variant": apk_metadata(variant="fossMobileArm64Release"),
            "package": apk_metadata(package="com.example.app"),
            "version": apk_metadata(version_code="1700000000"),
            "output": apk_metadata(output_file="other.apk"),
        }
        for label, document in cases.items():
            with self.subTest(label=label):
                result, _, _, _ = self.stage(metadata=document)
                self.assertNotEqual(0, result.returncode)

    def test_tv_universal_debug_or_case_variant_is_rejected(self):
        for variant in (
            "gmsTvArm64Release",
            "gmsMobileUniversalRelease",
            "gmsMobileArm64Debug",
            "gmsMobileArm64ReleaseExtra",
            "gmsmobilearm64release",
        ):
            with self.subTest(variant=variant):
                result, _, _, _ = self.stage(metadata=apk_metadata(variant=variant))
                self.assertNotEqual(0, result.returncode)
                self.assertIn("gmsMobileArm64Release", result.stdout + result.stderr)


class SigningTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.script = run_script("Sign and verify the candidate APK")

    def sign(self, *, verify_fail=False, v2="true", certs_fail=False,
             expected_signer=None, fake_signer=None, secrets=None):
        directory = Path(self.enterContext(tempfile.TemporaryDirectory()))
        runner_temp = directory / "runner"
        runner_temp.mkdir()
        stage = runner_temp / "natune-candidate"
        stage.mkdir()
        apk = stage / "natune-android-arm64-release-candidate.apk"
        apk.write_bytes(b"unsigned-apk-bytes")
        tools = directory / "build-tools"
        tools.mkdir()
        calls = directory / "calls.jsonl"
        keystore_copy = directory / "decoded-keystore"
        tool(tools, "zipalign", """
            #!/usr/bin/env python3
            import os
            import shutil
            import sys
            with open(os.environ["ZIPALIGN_CALLS"], "a") as log:
                log.write(" ".join(sys.argv[1:]) + "\\n")
            shutil.copyfile(sys.argv[-2], sys.argv[-1])
        """)
        tool(tools, "apksigner", """
            #!/usr/bin/env python3
            import json
            import os
            import shutil
            import sys
            args = sys.argv[1:]
            with open(os.environ["APKSIGNER_CALLS"], "a") as log:
                log.write(json.dumps(args) + "\\n")
            if args[0] == "sign":
                shutil.copyfile(args[args.index("--ks") + 1], os.environ["KEYSTORE_COPY"])
                with open(args[-1], "ab") as apk:
                    apk.write(b"-signed")
                sys.exit(0)
            if args[0] == "verify" and "--print-certs" in args:
                if os.environ.get("CERTS_FAIL") == "1":
                    sys.exit(1)
                print("Signer #1 certificate DN: CN=fixture-owner")
                print("Signer #1 certificate SHA-256 digest: " + os.environ["FAKE_SIGNER"])
                sys.exit(0)
            if args[0] == "verify" and "--verbose" in args:
                if os.environ.get("VERIFY_FAIL") == "1":
                    sys.exit(1)
                print("Verifies")
                print("Verified using v1 scheme (JAR signing): false")
                print("Verified using v2 scheme (APK Signature Scheme v2): "
                      + os.environ.get("V2_OK", "true"))
                sys.exit(0)
            sys.exit("unexpected apksigner invocation: " + repr(args))
        """)
        expected = expected_signer if expected_signer is not None else BASELINE_SIGNER_SHA256
        printed = fake_signer if fake_signer is not None else expected
        values = {
            "KEYSTORE": base64.b64encode(b"fixture-keystore-bytes").decode(),
            "KEY_ALIAS": "fixture-alias",
            "KEYSTORE_PASSWORD": "fixture-store-password",
            "KEY_PASSWORD": "fixture-key-password",
        }
        values.update(secrets or {})
        env = {
            "BUILD_TOOLS": str(tools),
            "RUNNER_TEMP": str(runner_temp),
            "CANDIDATE_APK": str(apk),
            "EXPECTED_SIGNER_SHA256": expected,
            "APKSIGNER_CALLS": str(calls),
            "ZIPALIGN_CALLS": str(directory / "zipalign.log"),
            "KEYSTORE_COPY": str(keystore_copy),
            "VERIFY_FAIL": "1" if verify_fail else "0",
            "V2_OK": v2,
            "CERTS_FAIL": "1" if certs_fail else "0",
            "FAKE_SIGNER": printed,
        }
        env.update(values)
        result = bash(self.script, directory, env)
        return result, apk, keystore_copy, calls, values

    def test_sign_aligns_with_historical_keystore_and_verifies_the_pin(self):
        result, apk, keystore_copy, calls, values = self.sign()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(b"unsigned-apk-bytes-signed", apk.read_bytes())
        self.assertEqual(
            b"fixture-keystore-bytes", base64.b64decode(values["KEYSTORE"]))
        self.assertEqual(base64.b64decode(values["KEYSTORE"]), keystore_copy.read_bytes())
        invocations = [json.loads(line) for line in calls.read_text().splitlines()]
        sign = invocations[0]
        self.assertEqual("sign", sign[0])
        self.assertIn("--v1-signing-enabled", sign)
        self.assertIn("false", sign)
        self.assertIn("verify", invocations[1])
        self.assertIn("--verbose", invocations[1])
        self.assertIn("--print-certs", invocations[2])
        for value in values.values():
            if value:
                self.assertNotIn(value, result.stdout + result.stderr)
        self.assertNotIn("fixture-owner", result.stdout + result.stderr)

    def test_missing_signing_secret_fails_before_any_tool_runs(self):
        for name in SIGNING_NAMES:
            with self.subTest(missing=name):
                result, _, _, calls, _ = self.sign(secrets={name: ""})
                self.assertNotEqual(0, result.returncode)
                self.assertIn(name, result.stdout + result.stderr)
                self.assertFalse(calls.exists())

    def test_unverified_candidate_or_missing_v2_verification_fails(self):
        result, _, _, _, _ = self.sign(verify_fail=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("did not verify", result.stdout + result.stderr)
        result, _, _, _, _ = self.sign(v2="false")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("not verified by APK Signature Scheme v2", result.stdout + result.stderr)
        result, _, _, _, _ = self.sign(certs_fail=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("could not read", result.stdout + result.stderr)

    def test_candidate_certificate_mismatch_fails(self):
        result, _, _, _, _ = self.sign(fake_signer="0" * 64)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("does not match the trusted historical signer pin", result.stdout + result.stderr)
        self.assertNotIn("fixture-owner", result.stdout + result.stderr)


class FinalMetadataTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.script = run_script("Record the final signed APK metadata, checksum and size report")

    def record(self, *, badging_text=None, extra_entries=(), extra_stage_file=False):
        directory = Path(self.enterContext(tempfile.TemporaryDirectory()))
        runner_temp = directory / "runner"
        runner_temp.mkdir()
        stage = runner_temp / "natune-candidate"
        stage.mkdir()
        apk = write_zip(
            stage / "natune-android-arm64-release-candidate.apk", extra_entries)
        if extra_stage_file:
            (stage / "unexpected-baseline.apk").write_bytes(b"must not upload")
        tools = directory / "build-tools"
        tools.mkdir()
        badging_file = directory / "badging.txt"
        badging_file.write_text(badging_text if badging_text is not None else badging(),
                                encoding="utf-8")
        tool(tools, "aapt2", """
            #!/usr/bin/env python3
            import os
            print(open(os.environ["FAKE_BADGING"], encoding="utf-8").read(), end="")
        """)
        bin_dir = directory / "bin"
        bin_dir.mkdir()
        tool(bin_dir, "java", """
            #!/bin/sh
            echo 'openjdk version "21.0.8" 2025-07-15' >&2
        """)
        result = bash(
            self.script, ROOT,
            {
                "BUILD_TOOLS": str(tools),
                "BUILD_TOOLS_NAME": "35.0.0",
                "RUNNER_TEMP": str(runner_temp),
                "CANDIDATE_APK": str(apk),
                "STAGE": str(stage),
                "CANDIDATE_VERSION_CODE": RESERVED_VERSION_CODE,
                "SOURCE_SHA": SOURCE_SHA,
                "DISPATCH_REF": "refs/heads/ci/android-arm64-candidate",
                "SIGNER_SHA256": BASELINE_SIGNER_SHA256,
                "BASELINE_SHA256": BASELINE_SHA256,
                "BASELINE_BYTES": BASELINE_BYTES,
                "BASELINE_VERSION_CODE": BASELINE_VERSION_CODE,
                "GITHUB_RUN_ID": "12345",
                "GITHUB_RUN_ATTEMPT": "1",
                "GITHUB_EVENT_NAME": "workflow_dispatch",
                "FAKE_BADGING": str(badging_file),
            },
            extra_path=bin_dir,
        )
        return result, apk, stage

    def test_final_signed_bytes_drive_checksum_metadata_and_size(self):
        result, apk, stage = self.record()
        self.assertEqual(0, result.returncode, result.stderr)
        payload = apk.read_bytes()
        digest = hashlib.sha256(payload).hexdigest()
        checksums = (stage / "SHA256SUMS").read_text(encoding="utf-8")
        self.assertEqual(
            f"{digest}  natune-android-arm64-release-candidate.apk\n", checksums)
        metadata = json.loads((stage / "candidate-metadata.json").read_text(encoding="utf-8"))
        self.assertEqual(SOURCE_SHA, metadata["source_sha"])
        self.assertEqual(int(RESERVED_VERSION_CODE), metadata["version_code"])
        self.assertEqual(VERSION_NAME, metadata["version_name"])
        self.assertEqual(digest, metadata["apk"]["sha256"])
        self.assertEqual(len(payload), metadata["apk"]["bytes"])
        self.assertEqual(BASELINE_SIGNER_SHA256, metadata["signer_sha256"])
        self.assertEqual(BASELINE_SHA256, metadata["trusted_baseline"]["sha256"])
        self.assertEqual(False, metadata["published"])
        self.assertEqual("candidate", metadata["channel"])
        self.assertEqual(":app:assembleGmsMobileArm64Release", metadata["gradle_task"])
        self.assertEqual({"automix": False, "slimTdlib": True}, metadata["build_flags"])
        size_report = json.loads((stage / "apk-size.json").read_text(encoding="utf-8"))
        self.assertEqual(digest, size_report["artifact"]["sha256"])
        self.assertEqual(len(payload), size_report["artifact"]["bytes"])
        self.assertEqual(
            {
                "natune-android-arm64-release-candidate.apk",
                "SHA256SUMS",
                "candidate-metadata.json",
                "apk-size.json",
            },
            {path.name for path in stage.iterdir() if path.is_file()},
        )

    def test_unexpected_staged_file_blocks_the_hand_off(self):
        result, _, _ = self.record(extra_stage_file=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("holds unexpected files", result.stdout + result.stderr)

    def test_final_package_version_min_sdk_abi_or_debuggable_mismatch_fails(self):
        cases = {
            "package": badging(package="com.example.app"),
            "version code": badging(version_code="1700000000"),
            "version name": badging(version_name="15.0.0"),
            "min sdk": badging(min_sdk="26"),
            "abi": badging(native_code="'x86_64'"),
            "debuggable": badging(debuggable=True),
        }
        for label, text in cases.items():
            with self.subTest(label=label):
                result, _, stage = self.record(badging_text=text)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse((stage / "candidate-metadata.json").exists())


if __name__ == "__main__":
    unittest.main()
