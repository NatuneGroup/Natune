"""Synthetic regressions for the native dependency cache helper and workflow policy.

Every filesystem tree and tool identity is synthetic; no native, JVM, CMake or Xcode command runs.
"""

from __future__ import annotations

import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from scripts import native_dependency_cache as cache
from scripts.test_release_workflow import ROOT


WORKFLOWS = ROOT / ".github/workflows"


def write(path: Path, data: bytes = b"cache fixture") -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def copy_producers(workspace: Path) -> None:
    for relative in (
        "desktopApp/native/scripts/build-native-deps.sh",
        "scripts/build_ios_native_core.sh",
    ):
        target = workspace / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / relative, target)


def pins(workspace: Path, kind: str) -> dict[str, str]:
    return cache.producer(workspace, kind)[2]


def make_desktop_tree(workspace: Path, platform: str = "linux") -> None:
    version = pins(workspace, cache.DESKTOP)
    prefix = workspace / "desktopApp/build/native/prefix"
    if platform == "windows":
        for name in (
            "avcodec-62.dll", "avformat-62.dll", "avfilter-11.dll", "avutil-60.dll",
            "swresample-6.dll", "swscale-9.dll", "libssl-3-x64.dll", "libcrypto-3-x64.dll",
            "libxml2-2.dll", "zlib1.dll",
        ):
            write(prefix / "bin" / name)
        write(prefix / "lib/libz.a")
    else:
        for name in (
            "libavcodec.so.62", "libavformat.so.62", "libavfilter.so.11", "libavutil.so.60",
            "libswresample.so.6", "libswscale.so.9", "libssl.so.3", "libcrypto.so.3",
            "libxml2.so.2", "libz.so.1", "libz.a",
        ):
            write(prefix / "lib" / name)
    for header in (
        "libavformat/avformat.h", "libavcodec/avcodec.h", "libavfilter/avfilter.h",
        "libavutil/avutil.h", "libswresample/swresample.h", "libswscale/swscale.h",
        "openssl/ssl.h", "libxml2/libxml/parser.h", "zlib.h",
    ):
        write(prefix / "include" / header)
    for stamp in ("zlib", "openssl", "openssl-static", "libxml2", "ffmpeg"):
        write(prefix / f".stamp-{stamp}", b"")
    write(workspace / "desktopApp/build/native/openssl-static/lib/libcrypto.a")
    write(workspace / "desktopApp/build/native/openssl-static/lib/libssl.a")
    write(workspace / "desktopApp/build/native/src" / f"miniaudio-{version['miniaudio_version']}" / "miniaudio.h")
    write(workspace / "desktopApp/build/native/src" / f"miniaudio-{version['miniaudio_version']}" / "LICENSE")
    write(workspace / "desktopApp/build/native/src" / f"ffmpeg-{version['ffmpeg_version']}/COPYING.LGPLv3")
    write(workspace / "desktopApp/build/native/src" / f"openssl-{version['openssl_version']}/LICENSE.txt")
    write(workspace / "desktopApp/build/native/src" / f"libxml2-{version['libxml2_version']}/Copyright")


def fake_inputs(
    workspace: Path,
    kind: str,
    platform: str,
    arch: str,
    tools: dict[str, str] | None = None,
) -> dict[str, object]:
    return cache.collect_inputs(
        kind,
        platform,
        arch,
        workspace,
        tools=tools if tools is not None else {"cc": "fixture compiler"},
        environment={},
    )


class NativeCacheProbeTest(unittest.TestCase):
    def test_readelf_real_machine_lines(self) -> None:
        x64 = "  Machine:                           Advanced Micro Devices X86-64\n"
        arm = "  Machine:                           AArch64\n"
        with patch.object(cache, "run_text", return_value=x64):
            self.assertEqual({"x64"}, cache._elf_architectures(Path("fixture")))
        with patch.object(cache, "run_text", return_value=arm):
            self.assertEqual({"arm64"}, cache._elf_architectures(Path("fixture")))

    def test_objdump_real_pe_lines(self) -> None:
        x64 = "fixture:     file format pei-x86-64\narchitecture: i386:x86-64, flags 0x0000013b:\n"
        arm = "fixture:     file format pei-aarch64\narchitecture: aarch64, flags 0x0000013b:\n"
        with patch.object(cache, "run_text", return_value=x64):
            self.assertEqual({"x64"}, cache._pe_architectures(Path("fixture")))
        with patch.object(cache, "run_text", return_value=arm):
            self.assertEqual({"arm64"}, cache._pe_architectures(Path("fixture")))

    def test_unsupported_architecture_is_not_treated_as_safe(self) -> None:
        for arch in ("sparc", "arm64e"):
            with self.assertRaisesRegex(cache.CacheError, "unknown binary architecture"):
                cache.canonical_arch(arch)

    def test_custom_compiler_override_is_path_hashed_and_changes_in_place(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            compiler = Path(directory) / "custom-cc"
            compiler.write_text("#!/bin/sh\necho 'custom compiler one'\n")
            compiler.chmod(0o755)
            with patch.dict(os.environ, {"CC": str(compiler)}):
                first = cache._record_named("cc", "CC", "cc")
                compiler.write_text("#!/bin/sh\necho 'custom compiler two'\n")
                second = cache._record_named("cc", "CC", "cc")
        self.assertIn("custom compiler one", first)
        self.assertNotEqual(first, second)
        self.assertIn("sha256=", first)

    def test_every_reported_elf_or_pe_member_must_be_recognized(self) -> None:
        elf_mixed = "  Machine: Advanced Micro Devices X86-64\n  Machine: MIPS R3000\n"
        pe_mixed = (
            "a.o: file format pe-x86-64\narchitecture: i386:x86-64\n"
            "b.o: file format pe-i386\narchitecture: i386\n"
        )
        with patch.object(cache, "run_text", return_value=elf_mixed):
            with self.assertRaisesRegex(cache.CacheError, "unsupported ELF machine"):
                cache._elf_architectures(Path("fixture"))
        with patch.object(cache, "run_text", return_value=pe_mixed):
            with self.assertRaisesRegex(cache.CacheError, "unsupported PE architecture"):
                cache._pe_architectures(Path("fixture"))
        with patch.object(cache, "run_text", return_value="  Machine: MIPS R3000\n"):
            with self.assertRaisesRegex(cache.CacheError, "unsupported ELF machine"):
                cache._elf_architectures(Path("fixture"))
        with patch.object(cache, "run_text", return_value="x: file format elf32-i386\narchitecture: i386\n"):
            with self.assertRaisesRegex(cache.CacheError, "unsupported PE architecture"):
                cache._pe_architectures(Path("fixture"))

    def test_bulk_archive_readers_report_all_members(self) -> None:
        elf = "File: libx.a(a.o)\n  Machine: Advanced Micro Devices X86-64\nFile: libx.a(b.o)\n  Machine: AArch64\n"
        pe = "a.o: file format pe-x86-64\narchitecture: i386:x86-64\nb.o: file format pei-aarch64\narchitecture: aarch64\n"
        with patch.object(cache, "run_text", return_value=elf):
            self.assertEqual({"x64", "arm64"}, cache.probe_all_architectures(Path("libx.a"), "linux"))
        with patch.object(cache, "run_text", return_value=pe):
            self.assertEqual({"x64", "arm64"}, cache.probe_all_architectures(Path("libx.a"), "windows"))

    def setUp(self) -> None:
        self.directory = tempfile.mkdtemp(prefix="native-cache-key-")
        self.workspace = Path(self.directory)
        copy_producers(self.workspace)

    def tearDown(self) -> None:
        shutil.rmtree(self.directory, ignore_errors=True)

    def test_key_is_deterministic_for_the_same_explicit_inputs(self) -> None:
        first = fake_inputs(self.workspace, cache.DESKTOP, "linux", "x64")
        second = fake_inputs(self.workspace, cache.DESKTOP, "linux", "x64")
        self.assertEqual(cache.compute_key(first), cache.compute_key(second))
        self.assertEqual(first, second)

    def test_key_changes_with_toolchain_platform_arch_and_workspace(self) -> None:
        base = fake_inputs(self.workspace, cache.DESKTOP, "linux", "x64")
        changed_tool = fake_inputs(
            self.workspace, cache.DESKTOP, "linux", "x64", tools={"cc": "another compiler"}
        )
        other_platform = fake_inputs(self.workspace, cache.DESKTOP, "linux", "arm64")
        other_workspace = tempfile.mkdtemp(prefix="native-cache-other-")
        self.addCleanup(shutil.rmtree, other_workspace, True)
        copy_producers(Path(other_workspace))
        relocated = fake_inputs(Path(other_workspace), cache.DESKTOP, "linux", "x64")
        self.assertNotEqual(cache.compute_key(base), cache.compute_key(changed_tool))
        self.assertNotEqual(cache.compute_key(base), cache.compute_key(other_platform))
        self.assertNotEqual(cache.compute_key(base), cache.compute_key(relocated))

    def test_key_changes_when_the_recipe_bytes_change(self) -> None:
        before = fake_inputs(self.workspace, cache.DESKTOP, "linux", "x64")
        recipe = self.workspace / "desktopApp/native/scripts/build-native-deps.sh"
        recipe.write_text(recipe.read_text() + "\n# changed recipe\n")
        after = fake_inputs(self.workspace, cache.DESKTOP, "linux", "x64")
        self.assertNotEqual(cache.compute_key(before), cache.compute_key(after))

    def test_key_does_not_include_a_commit_sha(self) -> None:
        inputs = fake_inputs(self.workspace, cache.DESKTOP, "linux", "x64")
        self.assertNotIn("github.sha", json.dumps(inputs))
        self.assertNotIn("source_commit", json.dumps(inputs))

    def test_compiler_flags_are_not_path_normalized_into_a_collision(self) -> None:
        first = cache.collect_inputs(
            cache.DESKTOP, "linux", "x64", self.workspace,
            tools={"cc": "fixture"}, environment={"CFLAGS": "-O0 -Ifoo/../inc"},
        )
        second = cache.collect_inputs(
            cache.DESKTOP, "linux", "x64", self.workspace,
            tools={"cc": "fixture"}, environment={"CFLAGS": "-O3 -Ifoo/../inc"},
        )
        self.assertNotEqual(cache.compute_key(first), cache.compute_key(second))
        self.assertEqual("-O3 -Ifoo/../inc", second["environment"]["CFLAGS"])
        with patch.dict(os.environ, {"CFLAGS": "-O3 -Ifoo/../inc"}):
            self.assertEqual("-O3 -Ifoo/../inc", cache._environment(cache.DESKTOP)["CFLAGS"])

    def test_optional_manifest_filesystem_failure_removes_safe_stale_manifest(self) -> None:
        manifest = self.workspace / "desktopApp/build/native/.native-deps-cache.json"
        write(manifest, b'{"stale": true}')
        with patch.object(cache, "write_manifest", side_effect=OSError("disk full")), \
                contextlib.redirect_stderr(io.StringIO()):
            result = cache.main([
                "manifest", "--kind", "desktop", "--platform", "linux", "--target-arch", "x64",
                "--workspace", str(self.workspace), "--optional",
            ])
        self.assertEqual(0, result)
        self.assertFalse(manifest.exists())

    def test_bad_json_manifest_is_an_invalid_miss_not_a_traceback(self) -> None:
        manifest = self.workspace / "desktopApp/build/native/.native-deps-cache.json"
        write(manifest, b"[]")
        with patch.object(cache, "collect_inputs", return_value={}):
            with self.assertRaisesRegex(cache.CacheError, "not a JSON object"):
                cache.verify_manifest(self.workspace, cache.DESKTOP, "linux", "x64")

    def test_compound_compiler_override_puts_flags_after_the_executable(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            compiler = Path(directory) / "cc"
            log = Path(directory) / "args"
            compiler.write_text(
                "#!/bin/sh\nprintf '%s\\n' \"$*\" > \"$ARGS\"\necho 'compound compiler version'\n"
            )
            compiler.chmod(0o755)
            with patch.dict(os.environ, {"CC": f"{compiler} --flag", "ARGS": str(log)}):
                record = cache._record_named("cc", "CC", "cc")
            self.assertEqual("--flag --version", log.read_text().strip())
        self.assertIn("--flag", record)
        self.assertIn("compound compiler version", record)

    def test_optional_key_disables_caching_without_failing_the_run(self) -> None:
        stdout = io.StringIO()
        stderr = io.StringIO()
        with patch.object(cache, "tool_identity", side_effect=cache.CacheError("unsupported tool")), \
                contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            result = cache.main([
                "key", "--kind", "desktop", "--platform", "linux", "--target-arch", "x64",
                "--workspace", str(self.workspace), "--optional",
            ])
        self.assertEqual(0, result)
        self.assertIn("key=\n", stdout.getvalue())
        self.assertIn("native cache disabled: unsupported tool", stderr.getvalue())

    def test_optional_manifest_failure_disables_cache_without_failing_the_build(self) -> None:
        make_desktop_tree(self.workspace)
        manifest = self.workspace / "desktopApp/build/native/.native-deps-cache.json"
        stderr = io.StringIO()
        with patch.object(cache, "tool_identity", side_effect=cache.CacheError("unsupported probe")), \
                contextlib.redirect_stderr(stderr):
            result = cache.main([
                "manifest", "--kind", "desktop", "--platform", "linux", "--target-arch", "x64",
                "--workspace", str(self.workspace), "--optional",
            ])
        self.assertEqual(0, result)
        self.assertFalse(manifest.exists())
        self.assertIn("native cache disabled: unsupported probe", stderr.getvalue())

    def test_target_arch_must_match_the_msys2_or_rosetta_shell(self) -> None:
        with self.assertRaisesRegex(cache.CacheError, "not the requested"):
            fake_inputs(
                self.workspace,
                cache.DESKTOP,
                "linux",
                "x64",
                tools={"uname": "Darwin arm64"},
            )

class NativeCacheManifestTest(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = tempfile.mkdtemp(prefix="native-cache-manifest-")
        self.workspace = Path(self.directory)
        copy_producers(self.workspace)

    def tearDown(self) -> None:
        shutil.rmtree(self.directory, ignore_errors=True)

    def write_desktop(self) -> dict[str, object]:
        make_desktop_tree(self.workspace)
        inputs = fake_inputs(self.workspace, cache.DESKTOP, "linux", "x64")
        with patch.object(cache, "probe_all_architectures", return_value={"x64"}):
            cache.write_manifest(self.workspace, cache.DESKTOP, "linux", "x64", inputs)
        return inputs

    def verify_desktop(self, tools: dict[str, str] | None = None, arch: set[str] | None = None) -> None:
        with patch.object(cache, "tool_identity", return_value=tools or {"cc": "fixture compiler"}), patch.object(
            cache, "_environment", return_value={}
        ), patch.object(cache, "probe_all_architectures", return_value=arch or {"x64"}):
            cache.verify_manifest(self.workspace, cache.DESKTOP, "linux", "x64")

    def test_manifest_round_trip_checks_content_and_arch(self) -> None:
        self.write_desktop()
        self.verify_desktop()

    def test_missing_inventory_is_rejected(self) -> None:
        self.write_desktop()
        (self.workspace / "desktopApp/build/native/prefix/lib/libz.so.1").unlink()
        with self.assertRaisesRegex(cache.CacheError, "Linux shared library"):
            self.verify_desktop()

    def test_changed_content_is_rejected(self) -> None:
        self.write_desktop()
        write(self.workspace / "desktopApp/build/native/prefix/include/zlib.h", b"tampered")
        with self.assertRaisesRegex(cache.CacheError, "content differs"):
            self.verify_desktop()

    def test_changed_tool_identity_is_rejected(self) -> None:
        self.write_desktop()
        with self.assertRaisesRegex(cache.CacheError, "input identity"):
            self.verify_desktop(tools={"cc": "different compiler"})

    def test_wrong_architecture_is_rejected(self) -> None:
        self.write_desktop()
        with self.assertRaisesRegex(cache.CacheError, "wrong binary architecture"):
            self.verify_desktop(arch={"arm64"})

    def test_ios_download_manifest_uses_script_checksums(self) -> None:
        archive = self.workspace / "shared-data/build/ios-native-work/downloads/fixture.tar.xz"
        write(archive)
        expected = {"fixture.tar.xz": sha(archive.read_bytes())}
        with patch.object(cache, "download_inventory", return_value=expected):
            inputs = fake_inputs(self.workspace, cache.IOS_DOWNLOADS, "ios", "universal", tools={})
            cache.write_manifest(self.workspace, cache.IOS_DOWNLOADS, "ios", "universal", inputs)
            cache.verify_manifest(self.workspace, cache.IOS_DOWNLOADS, "ios", "universal")
            write(archive.parent / "unexpected.tar.gz")
            with self.assertRaisesRegex(cache.CacheError, "unexpected files"):
                cache.verify_manifest(self.workspace, cache.IOS_DOWNLOADS, "ios", "universal")


class NativeCacheSymlinkTest(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = tempfile.mkdtemp(prefix="native-cache-symlink-")
        self.workspace = Path(self.directory)
        self.prefix = self.workspace / "desktopApp/build/native/prefix"
        self.prefix.mkdir(parents=True)

    def tearDown(self) -> None:
        shutil.rmtree(self.directory, ignore_errors=True)

    def entries(self):
        return {
            entry["path"]: entry
            for entry in cache.collect_entries(
                self.workspace, cache.DESKTOP, "linux", relative_paths=["desktopApp/build/native/prefix"]
            )
        }

    def test_alias_and_target_are_both_preserved_in_either_creation_order(self) -> None:
        for alias_first in (True, False):
            with tempfile.TemporaryDirectory() as directory:
                workspace = Path(directory)
                prefix = workspace / "desktopApp/build/native/prefix"
                prefix.mkdir(parents=True)
                target = prefix / "libz.so.1"
                alias = prefix / "libz.so"
                if alias_first:
                    alias.symlink_to("libz.so.1")
                    target.write_bytes(b"zlib target")
                else:
                    target.write_bytes(b"zlib target")
                    alias.symlink_to("libz.so.1")
                entries = {
                    entry["path"]: entry
                    for entry in cache.collect_entries(
                        workspace, cache.DESKTOP, "linux",
                        relative_paths=["desktopApp/build/native/prefix"],
                    )
                }
                alias_path = "desktopApp/build/native/prefix/libz.so"
                target_path = "desktopApp/build/native/prefix/libz.so.1"
                self.assertEqual("symlink", entries[alias_path]["type"])
                self.assertEqual("libz.so.1", entries[alias_path]["target"])
                self.assertEqual("file", entries[target_path]["type"])
                self.assertEqual(sha(b"zlib target"), entries[target_path]["sha256"])

    def test_broken_and_outside_targets_are_rejected(self) -> None:
        broken = self.prefix / "broken.so"
        broken.symlink_to("missing.so")
        with self.assertRaisesRegex(cache.CacheError, "broken symlink"):
            self.entries()
        broken.unlink()
        outside = self.workspace / "outside"
        outside.write_bytes(b"outside")
        (self.prefix / "outside.so").symlink_to(outside)
        with self.assertRaisesRegex(cache.CacheError, "leaves the cached tree"):
            self.entries()

    def test_retargeting_and_target_byte_changes_change_the_manifest_identity(self) -> None:
        target = self.prefix / "libz.so.1"
        target.write_bytes(b"one")
        alias = self.prefix / "libz.so"
        alias.symlink_to("libz.so.1")
        before = self.entries()
        target.write_bytes(b"two")
        after_bytes = self.entries()
        self.assertNotEqual(before, after_bytes)
        second = self.prefix / "libz.so.2"
        second.write_bytes(b"two")
        alias.unlink()
        alias.symlink_to("libz.so.2")
        after_target = self.entries()
        self.assertEqual("libz.so.2", after_target["desktopApp/build/native/prefix/libz.so"]["target"])

    def test_invalidation_unlinks_only_a_final_prefix_symlink(self) -> None:
        outside = Path(tempfile.mkdtemp(prefix="native-cache-external-"))
        self.addCleanup(shutil.rmtree, outside, True)
        (outside / "keep").write_text("keep")
        native = self.workspace / "desktopApp/build/native"
        native.mkdir(parents=True, exist_ok=True)
        shutil.rmtree(self.prefix)
        (native / "prefix").symlink_to(outside, target_is_directory=True)
        removed = cache.invalidate(self.workspace, cache.DESKTOP, "linux")
        self.assertIn("desktopApp/build/native/prefix", removed)
        self.assertTrue((outside / "keep").is_file())
        self.assertFalse((native / "prefix").exists())

    def test_native_root_symlink_escape_is_rejected_before_reading_or_deleting(self) -> None:
        outside = Path(tempfile.mkdtemp(prefix="native-cache-external-"))
        self.addCleanup(shutil.rmtree, outside, True)
        (outside / "prefix").mkdir(parents=True)
        (outside / "prefix/keep").write_text("keep")
        native = self.workspace / "desktopApp/build/native"
        shutil.rmtree(native)
        native.symlink_to(outside, target_is_directory=True)
        with self.assertRaisesRegex(cache.CacheError, "escapes the checkout"):
            cache.invalidate(self.workspace, cache.DESKTOP, "linux")
        with self.assertRaisesRegex(cache.CacheError, "escapes the checkout"):
            cache.collect_entries(self.workspace, cache.DESKTOP, "linux",
                                  relative_paths=["desktopApp/build/native/prefix"])
        with self.assertRaisesRegex(cache.CacheError, "escapes the checkout"):
            cache.write_manifest(self.workspace, cache.DESKTOP, "linux", "x64", {})
        with self.assertRaisesRegex(cache.CacheError, "escapes the checkout"):
            cache.verify_manifest(self.workspace, cache.DESKTOP, "linux", "x64")
        self.assertTrue((outside / "prefix/keep").is_file())

    def test_higher_build_ancestor_symlink_escape_is_rejected(self) -> None:
        outside = Path(tempfile.mkdtemp(prefix="native-cache-external-")) / "desktopApp"
        self.addCleanup(shutil.rmtree, outside.parent, True)
        (outside / "build/native/prefix").mkdir(parents=True)
        (outside / "build/native/prefix/keep").write_text("keep")
        desktop = self.workspace / "desktopApp"
        shutil.rmtree(desktop)
        desktop.symlink_to(outside, target_is_directory=True)
        with self.assertRaisesRegex(cache.CacheError, "escapes the checkout"):
            cache.invalidate(self.workspace, cache.DESKTOP, "linux")
        self.assertTrue((outside / "build/native/prefix/keep").is_file())


class NativeCacheInvalidationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = tempfile.mkdtemp(prefix="native-cache-invalidate-")
        self.workspace = Path(self.directory)
        copy_producers(self.workspace)

    def tearDown(self) -> None:
        shutil.rmtree(self.directory, ignore_errors=True)

    def test_desktop_invalidation_removes_only_generated_inputs(self) -> None:
        write(self.workspace / "desktopApp/build/native/prefix/lib/libz.so.1")
        write(self.workspace / "desktopApp/build/native/openssl-static/lib/libz.a")
        write(self.workspace / "desktopApp/build/native/src/ffmpeg-8.1.3/README")
        write(self.workspace / "desktopApp/build/native/.native-deps-cache.json")
        write(self.workspace / "desktopApp/build/native/logs/keep.log")
        write(self.workspace / "desktopApp/build/keep.txt")
        removed = cache.invalidate(self.workspace, cache.DESKTOP, "linux")
        self.assertEqual(
            sorted(removed),
            [
                "desktopApp/build/native/.native-deps-cache.json",
                "desktopApp/build/native/openssl-static",
                "desktopApp/build/native/prefix",
                "desktopApp/build/native/src",
            ],
        )
        self.assertTrue((self.workspace / "desktopApp/build/native/logs/keep.log").is_file())
        self.assertTrue((self.workspace / "desktopApp/build/keep.txt").is_file())


class NativeCacheWorkflowPolicyTest(unittest.TestCase):
    def test_native_caches_restore_and_save_under_a_trusted_event(self) -> None:
        release = (WORKFLOWS / "_release-artifacts.yml").read_text()
        compile_workflow = (WORKFLOWS / "ios_compile.yml").read_text()
        for text in (release, compile_workflow):
            self.assertIn("actions/cache/restore@v4", text)
            self.assertIn("actions/cache/save@v4", text)
            self.assertNotIn("pull_request_target", text)
        for name in ("_release-artifacts.yml", "ios_compile.yml", "build_pull_request.yml"):
            self.assertNotIn("pull_request_target", (WORKFLOWS / name).read_text())
        for text in (release, compile_workflow):
            self.assertIn("github.event_name == 'push'", text)
            self.assertIn("github.event_name == 'workflow_dispatch'", text)
            self.assertIn("github.repository == 'NatuneGroup/Natune'", text)

    def test_ios_compile_konan_cache_does_not_save_on_pull_requests(self) -> None:
        text = (WORKFLOWS / "ios_compile.yml").read_text()
        restore = text.split("name: Restore the Kotlin/Native cache", 1)[1].split("- name:", 1)[0]
        save = text.split("name: Save the Kotlin/Native cache", 1)[1].split("- name:", 1)[0]
        self.assertIn("uses: actions/cache/restore@v4", restore)
        self.assertNotIn("restore-keys", restore)
        self.assertIn("uses: actions/cache/save@v4", save)
        self.assertIn("github.event_name == 'push'", save)
        self.assertIn("github.event_name == 'workflow_dispatch'", save)
        self.assertIn("github.repository == 'NatuneGroup/Natune'", save)
        self.assertIn("steps.konan-cache.outputs.cache-hit != 'true'", save)

    def test_native_cache_keys_are_exact_helper_outputs(self) -> None:
        text = (WORKFLOWS / "_release-artifacts.yml").read_text()
        self.assertIn("steps.native-key-win.outputs.key", text)
        self.assertIn("steps.native-key-unix.outputs.key", text)
        self.assertIn("steps.ios-downloads-key.outputs.key", text)
        self.assertIn("--optional >> \"$GITHUB_OUTPUT\"", text)
        self.assertIn("hashFiles('desktopApp/build/native/.native-deps-cache.json') != ''", text)

    def test_old_source_without_helper_runs_uncached_and_emits_no_cache_key(self) -> None:
        text = (WORKFLOWS / "_release-artifacts.yml").read_text()
        start = text.index("if [[ ! -f scripts/native_dependency_cache.py ]]; then")
        end = text.index("fi\n", start) + 3
        snippet = text[start:end]
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "output"
            env = dict(os.environ, GITHUB_OUTPUT=str(output))
            result = subprocess.run(
                ["bash", "-c", snippet], cwd=directory, env=env,
                text=True, capture_output=True, timeout=10,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("run uncached", result.stdout.lower())
            content = output.read_text()
            self.assertIn("key=\n", content)
            self.assertIn("paths<<NATIVE_CACHE_PATHS\n", content)
            self.assertNotIn("native-deps-", content)
        self.assertIn("-f scripts/native_dependency_cache.py", text)
        self.assertIn("bash desktopApp/native/scripts/build-native-deps.sh", text)
        self.assertIn("steps.ios-downloads-key.outputs.key != ''", text)
        self.assertIn("if [[ ! -f scripts/native_dependency_cache.py ]]",
                      (WORKFLOWS / "ios_compile.yml").read_text())

    def test_native_scripts_parse(self) -> None:
        for relative in (
            "desktopApp/native/scripts/build-native-deps.sh",
            "scripts/build_ios_native_core.sh",
        ):
            subprocess.run(["bash", "-n", str(ROOT / relative)], check=True)


if __name__ == "__main__":
    unittest.main()
