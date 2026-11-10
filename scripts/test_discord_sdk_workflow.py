"""Secret-free contracts for bundling the Discord Social SDK into shipping Android builds."""

import os
from pathlib import Path
import re
import subprocess
import tempfile
import textwrap
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[1]
ACTION = ROOT / ".github/actions/fetch-discord-sdk/action.yml"
RELEASE = ROOT / ".github/workflows/_release-artifacts.yml"
CANDIDATE = ROOT / ".github/workflows/build_pull_request.yml"
CHECK = ROOT / "scripts/check_discord_sdk_apk.py"
SDK_PATH = "third_party/discord-social-sdk/android/discord_partner_sdk.aar"


def action_script():
    run = re.search(r"(?m)^      run: \|\n((?: {8}.*\n|\n)+)", ACTION.read_text())
    return textwrap.dedent(run[1])


def action_env():
    block = ACTION.read_text().split("      env:\n", 1)[1].split("      run: |", 1)[0]
    return dict(re.findall(r"(?m)^        ([A-Z][A-Z0-9_]*): (\S+)$", block))


def run_action(directory, token, payload):
    bin_dir = directory / "bin"
    bin_dir.mkdir()
    curl = bin_dir / "curl"
    curl.write_text(textwrap.dedent(f"""\
        #!/usr/bin/env python3
        import sys
        args = sys.argv[1:]
        with open(args[args.index("--output") + 1], "wb") as out:
            out.write({payload!r})
        with open({str(directory / "curl-args")!r}, "w") as log:
            log.write("\\n".join(args))
    """))
    curl.chmod(0o755)
    env = os.environ.copy()
    env.update(action_env())
    env["DISCORD_SDK_TOKEN"] = token
    env["PATH"] = str(bin_dir) + os.pathsep + env["PATH"]
    return subprocess.run(["bash", "-c", action_script()], cwd=directory, env=env,
                          capture_output=True, text=True, timeout=30)


def apk(directory, names):
    path = directory / "app.apk"
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("classes.dex", b"dex")
        for name in names:
            archive.writestr(name, b"so")
    return path


class FetchActionTest(unittest.TestCase):
    def test_missing_token_fails_instead_of_shipping_without_presence(self):
        with tempfile.TemporaryDirectory() as directory:
            result = run_action(Path(directory), "", b"aar")
            self.assertNotEqual(0, result.returncode)
            self.assertIn("refusing to build without Discord Rich Presence", result.stdout + result.stderr)
            self.assertFalse((Path(directory) / SDK_PATH).exists())

    def test_digest_mismatch_removes_the_download(self):
        with tempfile.TemporaryDirectory() as directory:
            result = run_action(Path(directory), "token", b"not the sdk")
            self.assertNotEqual(0, result.returncode)
            self.assertIn("does not match the pinned", result.stdout + result.stderr)
            self.assertFalse((Path(directory) / SDK_PATH).exists())

    def test_download_is_pinned_to_a_commit_of_the_private_mirror(self):
        env = action_env()
        self.assertRegex(env["SDK_COMMIT"], r"^[0-9a-f]{40}$")
        self.assertRegex(env["SDK_SHA256"], r"^[0-9a-f]{64}$")
        self.assertEqual(SDK_PATH, env["SDK_PATH"])
        with tempfile.TemporaryDirectory() as directory:
            run_action(Path(directory), "token", b"x")
            args = (Path(directory) / "curl-args").read_text()
            self.assertIn("repos/NatuneGroup/discord-social-sdk/contents/android/discord_partner_sdk.aar?ref="
                          + env["SDK_COMMIT"], args)
            self.assertIn("Authorization: Bearer token", args)

    def test_download_retries_dropped_transfers(self):
        # Plain --retry skips transport failures such as an HTTP/2 stream reset (exit 92).
        with tempfile.TemporaryDirectory() as directory:
            run_action(Path(directory), "token", b"x")
            args = (Path(directory) / "curl-args").read_text().split("\n")
            self.assertIn("--retry-all-errors", args)
            self.assertIn("--retry", args)


class ApkCheckTest(unittest.TestCase):
    def check(self, names):
        with tempfile.TemporaryDirectory() as directory:
            return subprocess.run(["python3", str(CHECK), str(apk(Path(directory), names))],
                                  capture_output=True, text=True, timeout=30)

    def test_every_packaged_abi_needs_both_presence_libraries(self):
        complete = [f"lib/{abi}/{lib}" for abi in ("arm64-v8a", "x86_64")
                    for lib in ("libdiscord_partner_sdk.so", "libnatune_discord_social.so", "libarchivetune_dsp.so")]
        self.assertEqual(0, self.check(complete).returncode)
        missing = [name for name in complete if name != "lib/x86_64/libnatune_discord_social.so"]
        result = self.check(missing)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("lib/x86_64/libnatune_discord_social.so", result.stdout)

    def test_sdk_free_apk_is_rejected(self):
        self.assertNotEqual(0, self.check(["lib/arm64-v8a/libarchivetune_dsp.so"]).returncode)
        self.assertNotEqual(0, self.check([]).returncode)


class WorkflowWiringTest(unittest.TestCase):
    def steps(self, workflow):
        return re.findall(r"(?m)^      - name: (.+)$", workflow.read_text())

    def test_release_and_candidate_fetch_before_assembling_with_the_sdk_property(self):
        for workflow, assemble in ((RELEASE, "Assemble Android APK"),
                                   (CANDIDATE, "Assemble only the GMS mobile ARM64 release APK")):
            with self.subTest(workflow=workflow.name):
                text = workflow.read_text()
                names = self.steps(workflow)
                fetch = "Fetch the Discord Social SDK for Rich Presence"
                self.assertLess(names.index(fetch), names.index(assemble))
                self.assertIn("uses: ./.github/actions/fetch-discord-sdk\n        with:\n"
                              "          token: ${{ secrets.DISCORD_SDK_TOKEN }}", text)
                assemble_body = text.split(f"      - name: {assemble}\n", 1)[1].split("      - name:", 1)[0]
                self.assertIn("-Pnatune.discordSdk=true", assemble_body)
                verify = [name for name in names if name.startswith("Verify Discord Rich Presence")]
                self.assertEqual(1, len(verify))
                self.assertLess(names.index(assemble), names.index(verify[0]))

    def test_pull_request_builds_never_receive_the_sdk_token(self):
        text = CANDIDATE.read_text()
        candidate = text.split("\n  android-release-candidate:\n", 1)[1]
        before = text.split("\n  android-release-candidate:\n", 1)[0]
        self.assertNotIn("DISCORD_SDK_TOKEN", before)
        self.assertIn("DISCORD_SDK_TOKEN", candidate)
        self.assertIn("github.event_name == 'workflow_dispatch'", candidate.split("runs-on:", 1)[0])


if __name__ == "__main__":
    unittest.main()
