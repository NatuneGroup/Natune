"""Native release regressions using fake tools only; no native or JVM compilation."""

import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from scripts.test_release_workflow import ROOT, tool


STAGER = ROOT / "desktopApp/native/scripts/stage-macos-package.py"
DEPS = ROOT / "desktopApp/native/scripts/build-native-deps.sh"
TDJNI = ROOT / "desktopApp/tdlight-jvm/native/build-tdjni.sh"
WINDOWS_DEPS = ROOT / "desktopApp/native/scripts/windows-native-deps.py"


def load_stager():
    spec = importlib.util.spec_from_file_location("macos_package", STAGER)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def load_windows_deps():
    spec = importlib.util.spec_from_file_location("windows_native_deps", WINDOWS_DEPS)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class NativeReleasePackagingTest(unittest.TestCase):
    def test_browser_install_owns_only_its_file_not_the_playback_closure_directory(self):
        build = (ROOT / "desktopApp/build.gradle.kts").read_text()
        install = build.split('val installNativeBrowser = ', 1)[1].split('\nval ', 1)[0]
        self.assertIn('outputs.file(hostNativeDirectory.map { it.file(System.mapLibraryName("archivetune_browser")) })', install)
        self.assertNotIn('outputs.dir(hostNativeDirectory)', install)
        self.assertIn('"--arch", hostArch', build)
        self.assertIn('-DCMAKE_OSX_ARCHITECTURES=', build)

    def test_macos_explicit_architecture_overrides_an_arm_python_interpreter(self):
        module = load_stager()
        with patch.object(module.platform, "machine", return_value="arm64"), patch.object(module, "stage") as stage:
            module.main(["--arch", "x64"])
        stage.assert_called_once_with("x64")

    def macos_fixture(self, directory):
        root = Path(directory) / "desktopApp"
        build = root / "build"
        prefix = build / "native/prefix/lib"
        prefix.mkdir(parents=True)
        (prefix / "libavformat.62.dylib").write_bytes(b"media fixture")
        for path in (
            root.parent / "LICENSE", build / "native/src/ffmpeg-8.1.3/COPYING.LGPLv3",
            build / "native/src/openssl-3.5.9/LICENSE.txt", build / "native/src/libxml2-2.15.4/Copyright",
            build / "native/src/miniaudio-0.11.25/LICENSE",
        ):
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("notice fixture")
        return root

    def macos_tool(self, command, **kwargs):
        if command[0] == "cmake":
            destination = Path(command[command.index("--prefix") + 1])
            (destination / "libarchivetune_engine.dylib").write_bytes(b"engine fixture")
        if command[0] == "otool":
            library = Path(command[-1])
            if command[1] == "-D":
                output = f"{library}:\n@rpath/{library.name}\n"
            elif command[1] == "-L":
                output = f"{library}:\n@rpath/{library.name} (self)\n/usr/lib/libSystem.B.dylib (system)\n"
            else:
                output = f"{library}:\nLC_RPATH\ncmdsize 32\npath @loader_path (offset 12)\n"
            return subprocess.CompletedProcess(command, 0, stdout=output)
        return subprocess.CompletedProcess(command, 0, stdout="")

    def test_macos_stages_the_engine_and_media_in_the_packaging_jvm_directory(self):
        module = load_stager()
        with tempfile.TemporaryDirectory() as directory:
            root = self.macos_fixture(directory)
            with patch.object(module.subprocess, "run", side_effect=self.macos_tool) as run:
                module.stage("x64", root)
            destination = root / "build/distribution-resources/macos-x64/native"
            self.assertTrue((destination / "libarchivetune_engine.dylib").is_file())
            self.assertTrue((destination / "libavformat.62.dylib").is_file())
            self.assertFalse((root / "build/distribution-resources/macos-arm64").exists())
            audits = [call.args[0] for call in run.call_args_list if call.args[0][0] == "lipo"]
            self.assertEqual(2, len(audits))
            # lipo takes the input file first; the operation-first form makes it read the path as an arch flag.
            for args in audits:
                self.assertTrue(Path(args[1]).name.endswith(".dylib"), args)
                self.assertEqual(["-verify_arch", "x86_64"], list(args[2:]))

    def test_macos_reports_the_architecture_it_verifies_against(self):
        module = load_stager()
        output = io.StringIO()
        with tempfile.TemporaryDirectory() as directory:
            root = self.macos_fixture(directory)
            with patch.object(module.subprocess, "run", side_effect=self.macos_tool), \
                    contextlib.redirect_stderr(output):
                module.stage("arm64", root)
        self.assertIn("lipo architecture arm64", output.getvalue())

    def test_ios_closure_keeps_the_ffmpeg_key_check_and_its_short_gnupg_home(self):
        verify = (ROOT / "scripts/build_ios_native_core.sh").read_text() \
            .split("verify_ffmpeg_signature() {", 1)[1].split("\n}\n", 1)[0]
        # The check stays a real signature check against the pinned release fingerprint.
        self.assertIn('grep -q "^\\[GNUPG:\\] VALIDSIG .* $FFMPEG_SIGNING_FPR\\$"', verify)
        self.assertIn("--batch --yes --no-tty --import", verify)
        # The keyring lives in a short temporary home: macOS refuses the long gpg-agent socket path.
        self.assertIn('mktemp -d "$tmp/archivetune-ffmpeg-gpg.XXXXXX"', verify)
        self.assertNotIn('gnupg="$WORK/gnupg"', verify)
        # A failure prints the gpg log instead of only naming the file.
        self.assertIn('tail -n 20 "$log" >&2', verify)

    def test_macos_rejects_a_wrong_architecture_instead_of_omitting_the_audit(self):
        module = load_stager()
        def wrong_arch(command, **kwargs):
            if command[0] == "lipo":
                raise subprocess.CalledProcessError(1, command)
            return self.macos_tool(command, **kwargs)
        with tempfile.TemporaryDirectory() as directory:
            root = self.macos_fixture(directory)
            with patch.object(module.subprocess, "run", side_effect=wrong_arch):
                with self.assertRaises(subprocess.CalledProcessError):
                    module.stage("x64", root)

    def test_native_deps_mingw_zlib_uses_the_install_target_and_reports_failures(self):
        script = DEPS.read_text()
        # install builds libz.a, zlib1.dll and libz.dll.a; the default target would also build the examples.
        self.assertIn('"$MAKE_TOOL" -f win32/Makefile.gcc -j"$JOBS" install', script)
        self.assertEqual(1, script.count("win32/Makefile.gcc"))
        self.assertIn("fail_log", script)

    def test_native_deps_prints_the_failing_log_instead_of_exiting_silently(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            log = root / "build.log"
            log.write_text("\n".join(f"line {index}" for index in range(1, 101)) + "\n")
            env = dict(os.environ, DEPS_SCRIPT=str(DEPS), LOG=str(log))
            result = subprocess.run(["bash", "-c", 'source "$DEPS_SCRIPT"; fail_log "$LOG"'],
                                    env=env, text=True, capture_output=True, timeout=10)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("line 100", result.stderr)
            self.assertNotIn("line 1\n", result.stderr)

    def test_download_retries_keep_checksum_validation_and_reuse_verified_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bin_dir = root / "bin"
            bin_dir.mkdir()
            tool(bin_dir, "curl", """
                #!/usr/bin/env python3
                import json, os, pathlib, sys
                args = sys.argv[1:]
                with open(os.environ["CURL_CALLS"], "a") as log:
                    log.write(json.dumps(args) + "\\n")
                pathlib.Path(args[args.index("-o") + 1]).write_bytes(b"download fixture")
            """)
            env = dict(os.environ, PATH=str(bin_dir) + os.pathsep + os.environ["PATH"],
                       CURL_CALLS=str(root / "curl-calls"), ARCHIVETUNE_NATIVE_BUILD=str(root / "build"),
                       DEPS_SCRIPT=str(DEPS), DEST=str(root / "archive"),
                       DIGEST=hashlib.sha256(b"download fixture").hexdigest())
            command = 'source "$DEPS_SCRIPT"; fetch https://example.invalid/archive "$DEST" "$DIGEST"'
            for _ in range(2):
                result = subprocess.run(["bash", "-c", command], env=env, text=True, capture_output=True, timeout=10)
                self.assertEqual(0, result.returncode, result.stderr)
            calls = [json.loads(line) for line in (root / "curl-calls").read_text().splitlines()]
            self.assertEqual(1, len(calls))
            # The recipe resumes partial transfers with its own bounded loop; curl's --retry would restart
            # from byte zero and is deliberately not used.
            for option in ("--continue-at", "--speed-limit", "--speed-time", "--connect-timeout", "--max-time"):
                self.assertIn(option, calls[0])
            (root / "archive").write_bytes(b"corrupt fixture")
            result = subprocess.run(["bash", "-c", command], env=env, capture_output=True, timeout=10)
            self.assertNotEqual(0, result.returncode)
            self.assertFalse((root / "archive").exists())

    def test_ios_producer_uses_the_verified_zlib_fossil_url_and_digest(self) -> None:
        text = (ROOT / "scripts/build_ios_native_core.sh").read_text()
        self.assertIn(
            'fetch "https://zlib.net/fossils/zlib-$ZLIB_VERSION.tar.gz"', text
        )
        self.assertNotIn('fetch "https://zlib.net/zlib-$ZLIB_VERSION.tar.gz"', text)
        desktop = DEPS.read_text()
        digest = re.search(r"^ZLIB_SHA256=([0-9a-f]{64})$", desktop, re.M).group(1)
        self.assertIn(f"ZLIB_SHA256={digest}", text)

    def download_harness(self, directory, failures, body):
        root = Path(directory)
        bin_dir = root / "bin"
        bin_dir.mkdir()
        tool(bin_dir, "curl", """
            #!/usr/bin/env python3
            import os, pathlib, sys
            args = sys.argv[1:]
            state = pathlib.Path(os.environ["CURL_STATE"])
            count = int(state.read_text()) + 1 if state.exists() else 1
            state.write_text(str(count))
            target = pathlib.Path(args[args.index("-o") + 1])
            if count <= int(os.environ.get("CURL_FAIL_ATTEMPTS", "0")):
                target.write_bytes(b"partial fixture")
                sys.exit(7)
            target.write_bytes(bytes.fromhex(os.environ["CURL_BODY"]))
        """)
        tool(bin_dir, "sleep", '#!/bin/sh\necho "$*" >> "$SLEEP_CALLS"\n')
        tool(bin_dir, "mv", """
            #!/usr/bin/env python3
            import os, pathlib, shutil, sys
            with open(os.environ["MV_CALLS"], "a") as log:
                log.write(" ".join(sys.argv[1:]) + "\\n")
            shutil.move(sys.argv[1], sys.argv[2])
        """)
        env = dict(
            os.environ,
            PATH=str(bin_dir) + os.pathsep + os.environ["PATH"],
            ARCHIVETUNE_NATIVE_BUILD=str(root / "build"),
            DEPS_SCRIPT=str(DEPS),
            DEST=str(root / "archive"),
            DIGEST=hashlib.sha256(b"download fixture").hexdigest(),
            CURL_STATE=str(root / "curl-state"),
            CURL_FAIL_ATTEMPTS=str(failures),
            CURL_BODY=body.hex(),
            SLEEP_CALLS=str(root / "sleep-calls"),
            MV_CALLS=str(root / "mv-calls"),
        )
        return root, env, 'source "$DEPS_SCRIPT"; fetch https://example.invalid/archive "$DEST" "$DIGEST"'

    def test_download_resumes_after_a_transient_failure_without_sleeping_for_real(self):
        with tempfile.TemporaryDirectory() as directory:
            root, env, command = self.download_harness(directory, 1, b"download fixture")
            result = subprocess.run(["bash", "-c", command], env=env, text=True, capture_output=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(b"download fixture", (root / "archive").read_bytes())
            self.assertFalse((root / "archive.part").exists())
            self.assertEqual("5", (root / "sleep-calls").read_text().strip())
            self.assertEqual("2", (root / "curl-state").read_text())
            self.assertEqual(
                f"{root / 'archive.part'} {root / 'archive'}",
                (root / "mv-calls").read_text().strip(),
            )

    def test_download_exhausts_eight_attempts_and_never_promotes_the_partial_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root, env, command = self.download_harness(directory, 8, b"download fixture")
            result = subprocess.run(["bash", "-c", command], env=env, text=True, capture_output=True, timeout=10)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("giving up", result.stdout + result.stderr)
            self.assertFalse((root / "archive").exists())
            self.assertTrue((root / "archive.part").exists())
            self.assertEqual("8", (root / "curl-state").read_text())
            self.assertEqual(7, len((root / "sleep-calls").read_text().splitlines()))
            self.assertFalse((root / "mv-calls").exists())

    def test_download_rejects_a_checksum_mismatch_without_promoting_the_partial_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root, env, command = self.download_harness(directory, 0, b"corrupt fixture")
            result = subprocess.run(["bash", "-c", command], env=env, text=True, capture_output=True, timeout=10)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("checksum mismatch for the partial", result.stdout + result.stderr)
            self.assertFalse((root / "archive").exists())
            self.assertFalse((root / "archive.part").exists())
            self.assertFalse((root / "mv-calls").exists())

    def mingw_fixture(self, directory, runtime="jvm.dll"):
        root = Path(directory)
        build = root / "build"
        (build / "src/.git").mkdir(parents=True)
        example = build / "src/example/java"
        example.mkdir(parents=True)
        (example / "CMakeLists.txt").write_text(
            'set(TOOL ${CMAKE_CURRENT_SOURCE_DIR}/td/bin/td_generate_java_api)\n'
            'target_link_libraries(tdjni PRIVATE Td::TdStatic)\n'
        )
        license_text = "Boost Software License - Version 1.0\nfixture\n"
        (build / "src/LICENSE_1_0.txt").write_text(license_text)
        for name in ("openssl/lib/libcrypto.a", "openssl/lib/libssl.a", "zlib/lib/libz.a"):
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"static archive fixture")
        bin_dir = root / "bin"
        bin_dir.mkdir()
        tool(bin_dir, "uname", '#!/bin/sh\ncase "$1" in -s) echo MINGW64_NT-10.0;; -m) echo x86_64;; esac\n')
        tool(bin_dir, "git", '#!/bin/sh\ncase " $* " in *" rev-parse "*) echo "$TDLIGHT_PIN";; esac\n')
        tool(bin_dir, "objdump", '#!/bin/sh\nprintf "DLL Name: KERNEL32.dll\\nDLL Name: %s\\n" "$IMPORT_DLL"\n')
        tool(bin_dir, "cmake", """
            #!/usr/bin/env python3
            import json, os, pathlib, sys
            args = sys.argv[1:]
            root = pathlib.Path(os.environ["ARCHIVETUNE_TDLIGHT_BUILD"])
            with open(os.environ["CMAKE_CALLS"], "a") as log:
                log.write(json.dumps(args) + "\\n")
            if "--build" in args:
                if args[args.index("--target") + 1] == "install":
                    (root / "prefix").mkdir()
                else:
                    build = pathlib.Path(args[args.index("--build") + 1])
                    build.mkdir()
                    (build / "tdjni.dll").write_bytes(b"JNI fixture")
        """)
        pin = re.search(r'^TDLIGHT_COMMIT="([^"]+)"', TDJNI.read_text(), re.M)[1]
        env = dict(os.environ, PATH=str(bin_dir) + os.pathsep + os.environ["PATH"],
                   ARCHIVETUNE_TDLIGHT_BUILD=str(build), TDLIGHT_OPENSSL_PREFIX=str(root / "openssl"),
                   TDLIGHT_ZLIB_PREFIX=str(root / "zlib"), TDLIGHT_PIN=pin, IMPORT_DLL=runtime,
                   CMAKE_CALLS=str(root / "cmake-calls"))
        result = subprocess.run(["bash", str(TDJNI)], env=env, capture_output=True, text=True, timeout=30)
        return result, build, license_text

    def test_mingw_tdlight_uses_the_matching_static_prefixes_and_stages_the_jvm_dll_name(self):
        with tempfile.TemporaryDirectory() as directory:
            result, build, license_text = self.mingw_fixture(directory)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(b"JNI fixture", (build / "stage/windows-x64/native/tdjni.dll").read_bytes())
            self.assertEqual(license_text, (build / "stage/notices/TDLight-Boost-1.0-LICENSE.txt").read_text())
            configurations = [json.loads(line) for line in (Path(directory) / "cmake-calls").read_text().splitlines()
                              if '"-S"' in line]
            self.assertEqual(2, len(configurations))
            self.assertTrue(all("MinGW Makefiles" in args for args in configurations))
            self.assertTrue(all(any("ZLIB_LIBRARY=" in arg and arg.endswith("/libz.a") for arg in args)
                                for args in configurations))
            jni_project = (build / "java-src/CMakeLists.txt").read_text()
            self.assertIn("td_generate_java_api.exe", jni_project)
            self.assertIn('PROPERTIES PREFIX ""', jni_project)
            self.assertIn("-static-libstdc++", jni_project)
            self.assertFalse((build / "java-src/td").is_symlink())

    def test_mingw_tdlight_rejects_an_unstaged_compiler_runtime_dll(self):
        with tempfile.TemporaryDirectory() as directory:
            result, build, _ = self.mingw_fixture(directory, runtime="libgcc_s_seh-1.dll")
            self.assertNotEqual(0, result.returncode)
            self.assertIn("static linking is required", result.stderr)
            self.assertFalse((build / "stage/windows-x64/native/tdjni.dll").exists())


    def windows_native_directory(self, directory, closure):
        """Placeholder DLLs plus the module under test; the PE import tables stay fixtures."""
        module = load_windows_deps()
        for name in closure:
            (Path(directory) / name).write_bytes(b"DLL fixture")
        tables = {name: list(imports) for name, imports in closure.items()}

        def fake_imports(path):
            return 0x8664, {dependency: [] for dependency in tables[Path(path).name]}, {}

        return module, Path(directory), fake_imports

    def test_windows_manifest_lists_the_engine_closure_in_dependency_order(self):
        closure = {
            "archivetune_engine.dll": ["avcodec-62.dll", "kernel32.dll", "api-ms-win-crt-private-l1-1-0.dll"],
            "avcodec-62.dll": ["avutil-60.dll", "zlib1.dll"],
            "avutil-60.dll": ["libwinpthread-1.dll"],
            "libwinpthread-1.dll": [],
            "zlib1.dll": [],
        }
        with tempfile.TemporaryDirectory() as directory:
            module, native, fake_imports = self.windows_native_directory(directory, closure)
            with patch.object(module, "imports", side_effect=fake_imports):
                order = module.write_manifest(native)
            # Dependencies first, the engine itself last: that is what the Windows loader preloads.
            self.assertEqual(["libwinpthread-1.dll", "avutil-60.dll", "zlib1.dll", "avcodec-62.dll"], order)
            entries = [line for line in (native / module.MANIFEST_NAME).read_text().splitlines()
                       if line and not line.startswith("#")]
            self.assertEqual("avcodec-62.dll=avutil-60.dll,zlib1.dll", entries[-2])
            self.assertEqual("archivetune_engine.dll=avcodec-62.dll", entries[-1])
            self.assertNotIn("kernel32", "\n".join(entries))

    def test_windows_audit_rejects_an_unstaged_import(self):
        closure = {"archivetune_engine.dll": ["avcodec-62.dll", "libmissing-1.dll"]}
        with tempfile.TemporaryDirectory() as directory:
            module, native, fake_imports = self.windows_native_directory(directory, closure)
            with patch.object(module, "imports", side_effect=fake_imports):
                problems, _, machine_problems = module.audit(native)
            self.assertTrue(any("libmissing-1.dll" in problem for problem in problems), problems)
            self.assertEqual([], machine_problems)

    def test_windows_check_rejects_a_stale_manifest(self):
        closure = {"archivetune_engine.dll": ["avcodec-62.dll"], "avcodec-62.dll": []}
        with tempfile.TemporaryDirectory() as directory:
            module, native, fake_imports = self.windows_native_directory(directory, closure)
            (native / module.MANIFEST_NAME).write_text("avcodec-62.dll=\narchivetune_engine.dll=avcodec-62.dll\n")
            with patch.object(module, "imports", side_effect=fake_imports), \
                    contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(0, module.main([str(native), "--check"]))
            (native / module.MANIFEST_NAME).write_text("archivetune_engine.dll=avcodec-62.dll\navcodec-62.dll=\n")
            with patch.object(module, "imports", side_effect=fake_imports), \
                    contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(1, module.main([str(native), "--check"]))

    def test_windows_audit_rejects_a_32_bit_dll(self):
        module = load_windows_deps()
        with tempfile.TemporaryDirectory() as directory:
            native = Path(directory)
            for name in ("archivetune_engine.dll", "avcodec-62.dll"):
                (native / name).write_bytes(b"DLL fixture")

            def fake_imports(path):
                machine = 0x8664 if Path(path).name.startswith("archivetune") else 0x014C
                return machine, {"avcodec-62.dll": []} if Path(path).name.startswith("archivetune") else {}, {}

            with patch.object(module, "imports", side_effect=fake_imports):
                _, _, machine_problems = module.audit(native)
            self.assertTrue(any("avcodec-62.dll" in problem for problem in machine_problems), machine_problems)

    def test_windows_api_sets_and_crt_redistributables_are_system_libraries(self):
        module = load_windows_deps()
        for name in ("api-ms-win-crt-private-l1-1-0.dll", "api-ms-win-core-synch-l1-2-0.dll",
                     "ucrtbase.dll", "vcruntime140.dll", "msvcp140.dll", "kernel32.dll", "msvcrt.dll"):
            self.assertTrue(module.is_system(name), name)
        for name in ("avcodec-62.dll", "zlib1.dll", "tdjni.dll"):
            self.assertFalse(module.is_system(name), name)


if __name__ == "__main__":
    unittest.main()
