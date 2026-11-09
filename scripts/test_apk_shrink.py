"""APK reduction contracts and synthetic ELF audits; no build tools or native loading."""

from pathlib import Path
import hashlib
import struct
import tempfile
import tomllib
import unittest
import zipfile

from scripts.recompress_apk import recompress_apk

from scripts.check_android_native_stl import audit_apk, elf_needed

ROOT = Path(__file__).resolve().parents[1]


def elf_fixture(bits=64, dependencies=()):
    data = bytearray(768)
    data[:6] = b"\x7fELF" + bytes((2 if bits == 64 else 1, 1))
    strings = b"\0" + b"".join(name.encode() + b"\0" for name in dependencies)
    tags = [(5, 0x1000 + 512), (10, len(strings))]
    position = 1
    for name in dependencies:
        tags.append((1, position))
        position += len(name) + 1
    tags.append((0, 0))
    dynamicformat = "<qQ" if bits == 64 else "<iI"
    dynamic = b"".join(struct.pack(dynamicformat, *tag) for tag in tags)
    if bits == 64:
        struct.pack_into("<Q", data, 32, 64)
        struct.pack_into("<HH", data, 54, 56, 2)
        struct.pack_into("<IIQQQQQQ", data, 64, 1, 4, 0, 0x1000, 0, len(data), len(data), 4096)
        struct.pack_into("<IIQQQQQQ", data, 120, 2, 4, 256, 0x1100, 0, len(dynamic), len(dynamic), 8)
    else:
        struct.pack_into("<I", data, 28, 64)
        struct.pack_into("<HH", data, 42, 32, 2)
        struct.pack_into("<IIIIIIII", data, 64, 1, 0, 0x1000, 0, len(data), len(data), 4, 4096)
        struct.pack_into("<IIIIIIII", data, 96, 2, 256, 0x1100, 0, len(dynamic), len(dynamic), 4, 8)
    data[256:256 + len(dynamic)] = dynamic
    data[512:512 + len(strings)] = strings
    return bytes(data)


class ElfAuditTest(unittest.TestCase):
    def test_dependencies_survive_absent_section_headers_in_both_android_elf_classes(self):
        for bits in (32, 64):
            with self.subTest(bits=bits):
                self.assertEqual({"libc.so", "libc++_shared.so"},
                                 elf_needed(elf_fixture(bits, ("libc.so", "libc++_shared.so"))))

    def test_no_stl_dependency_is_valid(self):
        self.assertEqual({"liblog.so"}, elf_needed(elf_fixture(dependencies=("liblog.so",))))

    def test_invalid_or_truncated_elf_is_rejected(self):
        for data in (b"not ELF", elf_fixture()[:100]):
            with self.subTest(size=len(data)), self.assertRaises(ValueError):
                elf_needed(data)

    def test_unterminated_needed_name_is_rejected(self):
        data = bytearray(elf_fixture(dependencies=("libc.so",)))
        data[520] = 65
        with self.assertRaisesRegex(ValueError, "unterminated"):
            elf_needed(data)

    def test_segment_outside_file_is_rejected(self):
        data = bytearray(elf_fixture())
        struct.pack_into("<Q", data, 96, 4096)
        with self.assertRaisesRegex(ValueError, "segment exceeds"):
            elf_needed(data)

    def audit(self, entries, static=False, arsc_compression=zipfile.ZIP_STORED):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "app.apk"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("resources.arsc", b"resources", compress_type=arsc_compression)
                for name, data in entries.items():
                    archive.writestr(name, data, compress_type=zipfile.ZIP_DEFLATED)
            return audit_apk(path, static)

    def test_static_package_accepts_system_dependencies(self):
        self.assertEqual(1, self.audit({"lib/arm64-v8a/libanalysis.so":
                                       elf_fixture(dependencies=("liblog.so", "libc.so"))}, static=True))

    def test_missing_shared_stl_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "unsatisfied shared STL"):
            self.audit({"lib/arm64-v8a/libanalysis.so": elf_fixture(dependencies=("libc++_shared.so",))})

    def test_shared_stl_from_another_abi_cannot_satisfy_dependency(self):
        with self.assertRaisesRegex(ValueError, "unsatisfied shared STL"):
            self.audit({"lib/arm64-v8a/libanalysis.so": elf_fixture(dependencies=("libc++_shared.so",)),
                        "lib/x86_64/libc++_shared.so": elf_fixture()})

    def test_optional_shared_package_is_valid(self):
        self.assertEqual(2, self.audit({"lib/arm64-v8a/libanalysis.so":
                                       elf_fixture(dependencies=("libc++_shared.so",)),
                                       "lib/arm64-v8a/libc++_shared.so": elf_fixture()}))

    def test_standard_package_rejects_reintroduced_shared_runtime(self):
        with self.assertRaisesRegex(ValueError, "unexpectedly packages"):
            self.audit({"lib/arm64-v8a/libc++_shared.so": elf_fixture()}, static=True)

    def test_compressing_arsc_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "arsc must stay stored"):
            self.audit({"lib/arm64-v8a/libanalysis.so": elf_fixture()},
                       arsc_compression=zipfile.ZIP_DEFLATED)

    def test_empty_native_package_is_not_a_successful_audit(self):
        with self.assertRaisesRegex(ValueError, "no native libraries"):
            self.audit({})


class RecompressionTest(unittest.TestCase):
    def fixtures(self, directory, *, arsc=zipfile.ZIP_STORED):
        source, output = Path(directory) / "original.apk", Path(directory) / "unsigned.apk"
        payloads = {
            "classes.dex": (b"method metadata " * 8192 + bytes(range(256)) * 512, zipfile.ZIP_DEFLATED),
            "resources.arsc": (b"resource names" * 100, arsc),
            "res/drawable/icon.png": (b"stored raster" * 100, zipfile.ZIP_STORED),
            "assets/notices/GPL-3.0.txt": (b"complete license fixture" * 100, zipfile.ZIP_DEFLATED),
            "lib/arm64-v8a/libanalysis.so": (elf_fixture(), zipfile.ZIP_DEFLATED),
        }
        with zipfile.ZipFile(source, "w") as archive:
            archive.comment = b"archive comment"
            for name, (payload, method) in payloads.items():
                entry = zipfile.ZipInfo(name, (2026, 11, 9, 12, 0, 0))
                entry.compress_type = method
                entry.comment = b"entry comment"
                entry.external_attr = 0o100644 << 16
                archive.writestr(entry, payload, compresslevel=1)
        return source, output, payloads

    def test_payload_methods_and_metadata_are_preserved_while_deflate_shrinks(self):
        with tempfile.TemporaryDirectory() as directory:
            source, output, payloads = self.fixtures(directory)
            self.assertGreater(recompress_apk(source, output), 0)
            with zipfile.ZipFile(output) as archive:
                self.assertEqual(set(payloads), set(archive.namelist()))
                self.assertEqual(b"archive comment", archive.comment)
                for name, (payload, method) in payloads.items():
                    with self.subTest(name=name):
                        self.assertEqual(payload, archive.read(name))
                        entry = archive.getinfo(name)
                        self.assertEqual(method, entry.compress_type)
                        self.assertEqual(b"entry comment", entry.comment)
                        self.assertEqual((2026, 11, 9, 12, 0, 0), entry.date_time)
                        self.assertEqual(0o100644 << 16, entry.external_attr)

    def test_recompressing_the_input_in_place_is_rejected_without_touching_it(self):
        with tempfile.TemporaryDirectory() as directory:
            source, _, _ = self.fixtures(directory)
            before = source.read_bytes()
            with self.assertRaisesRegex(ValueError, "different paths"):
                recompress_apk(source, source)
            self.assertEqual(before, source.read_bytes())

    def test_compressed_arsc_is_rejected_and_no_output_survives(self):
        with tempfile.TemporaryDirectory() as directory:
            source, output, _ = self.fixtures(directory, arsc=zipfile.ZIP_DEFLATED)
            with self.assertRaisesRegex(ValueError, "arsc must stay stored"):
                recompress_apk(source, output)
            self.assertFalse(output.exists())

    def test_existing_output_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            source, output, _ = self.fixtures(directory)
            output.write_bytes(b"existing output")
            with self.assertRaisesRegex(ValueError, "already contains data"):
                recompress_apk(source, output)
            self.assertEqual(b"existing output", output.read_bytes())

    def test_duplicate_names_are_rejected(self):
        import warnings
        with tempfile.TemporaryDirectory() as directory:
            source, output, _ = self.fixtures(directory)
            with warnings.catch_warnings(), zipfile.ZipFile(source, "a") as archive:
                warnings.simplefilter("ignore")
                archive.writestr("classes.dex", b"duplicate")
            with self.assertRaisesRegex(ValueError, "duplicate"):
                recompress_apk(source, output)
            self.assertFalse(output.exists())

    def test_no_size_gain_leaves_the_original_zip_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            source, first, _ = self.fixtures(directory)
            recompress_apk(source, first)
            second = Path(directory) / "second.apk"
            self.assertEqual(0, recompress_apk(first, second))
            self.assertEqual(first.read_bytes(), second.read_bytes())


class ShrinkSourceContractsTest(unittest.TestCase):
    def test_default_stl_is_static_and_prebuilt_variants_remain_shared(self):
        gradle = (ROOT / "app/build.gradle.kts").read_text()
        self.assertIn('if (discordSocialSdkEnabled || automix) "c++_shared" else "c++_static"', gradle)
        self.assertNotIn('implementation("com.google.oboe:oboe:', gradle)
        cmake = (ROOT / "app/src/main/cpp/CMakeLists.txt").read_text()
        self.assertIn('if(ANDROID_STL STREQUAL "c++_static")', cmake)
        self.assertIn('--exclude-libs,ALL', cmake)
        self.assertIn('NATUNE_DISCORD_SOCIAL_SDK', cmake)

    def test_librespot_keeps_unwind_and_single_codegen_unit(self):
        profile = tomllib.loads((ROOT / "native/librespot/Cargo.toml").read_text())["profile"]["release"]
        self.assertEqual({"opt-level": "z", "lto": "fat", "codegen-units": 1,
                          "strip": "symbols", "panic": "unwind"}, profile)
        self.assertIn("catch_unwind", (ROOT / "native/librespot/src/lib.rs").read_text())

    def test_oboe_notice_stays_bundled_and_visible_after_dependency_removal(self):
        notice = ROOT / "shared-ui/src/commonMain/composeResources/files/notices/Google-Oboe-Apache-2.0.txt"
        self.assertIn("APPENDIX", notice.read_text())
        about = (ROOT / "shared-ui/src/commonMain/kotlin/moe/rukamori/archivetune/shared/ui/settings/AboutBundledNotices.kt").read_text()
        self.assertIn('noticeResource = "files/notices/Google-Oboe-Apache-2.0.txt"', about)

    def test_android_and_shared_ui_use_one_identical_regular_font(self):
        resource = ROOT / "shared-ui/src/commonMain/composeResources/font/m4_poppins.ttf"
        self.assertEqual("707fdc5c8bab57a90061c6a8ed7b70d5ffb82fc810e994e79f90bace890c255a",
                         hashlib.sha256(resource.read_bytes()).hexdigest())
        self.assertFalse((ROOT / "app/src/main/res/font/poppins.ttf").exists())
        for name in ("ui/theme/Type.kt", "ui/component/MarkdownText.kt"):
            source = (ROOT / "app/src/main/kotlin/moe/rukamori/archivetune" / name).read_text()
            self.assertNotIn("R.font.poppins", source)
        theme = (ROOT / "app/src/main/kotlin/moe/rukamori/archivetune/ui/theme/Type.kt").read_text()
        self.assertIn("FontFamily(SharedPoppinsFont)", theme)
        helper = (ROOT / "app/src/main/kotlin/moe/rukamori/archivetune/ui/theme/SharedPoppinsAsset.kt").read_text()
        self.assertIn('Res.getUri("font/m4_poppins.ttf")', helper)
        self.assertTrue((ROOT / "app/src/main/res/font/simpmusic_poppins.ttf").exists())

    def test_palette_preserves_size_transparency_and_adaptive_background_analysis(self):
        task = (ROOT / "buildSrc/src/main/kotlin/GenerateIconPackTask.kt").read_text()
        encoder = (ROOT / "buildSrc/src/main/kotlin/OptimizeIconPng.kt").read_text()
        self.assertIn("IconRasterSize = 384", task)
        self.assertIn('AdaptiveIconForegroundInset = "18dp"', task)
        self.assertLess(task.index("targetFile.readOpaqueCornerColor()"),
                        task.index("optimizeOpaqueIconPng(targetFile)"))
        self.assertIn("if (pixels.any { it ushr 24 != 255 }) return", encoder)
        self.assertIn("paletteColors[oldPalette.mapSize + index] = pixels[offset]", encoder)
        self.assertIn("if (squaredError > pixels.size.toLong() * 3 * 16) return", encoder)
        self.assertIn("if (bytes.size < file.length())", encoder)

    def test_final_signed_gate_checks_stl_and_keeps_the_size_limit(self):
        workflow = (ROOT / ".github/workflows/build_pull_request.yml").read_text()
        audit = workflow.split("libdiscord_partner_sdk.so", 1)[1].split("          fi\n", 1)[0]
        shared, static = audit.split("          else\n", 1)
        self.assertIn('python3 scripts/check_android_native_stl.py "$APK"\n', shared)
        self.assertNotIn("--expect-static", shared)
        self.assertIn('python3 scripts/check_android_native_stl.py "$APK" --expect-static', static)
        # test_candidate_workflow.py owns the exact boundary; only require that the gate exists.
        self.assertRegex(workflow, r"MAX_APK_BYTES=\d+\n")
        recompress = workflow.index('python3 scripts/recompress_apk.py "$APK" "$COMPRESSED_APK"')
        align = workflow.index('"$BUILD_TOOLS/zipalign" -p -f 4 "$COMPRESSED_APK" "$ALIGNED_APK"')
        sign = workflow.index('"$BUILD_TOOLS/apksigner" sign', align)
        self.assertLess(recompress, align)
        self.assertLess(align, sign)


if __name__ == "__main__":
    unittest.main()
