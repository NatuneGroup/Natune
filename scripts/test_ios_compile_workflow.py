"""Lightweight contract for the ios_compile test-source compilation gate; no Gradle or Xcode."""

from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/ios_compile.yml"


class IosCompileWorkflowTest(unittest.TestCase):
    def test_default_path_compiles_shared_data_ios_test_sources(self) -> None:
        text = WORKFLOW.read_text()
        self.assertIn(":shared-data:compileTestKotlinIosSimulatorArm64", text)
        self.assertIn(":shared-ui:compileKotlinIosSimulatorArm64", text)
        self.assertIn("--continue --stacktrace", text)
        self.assertIn("- 'shared-data/src/iosTest/**'", text)
        self.assertNotIn("pull_request_target", text)

    def test_compile_gate_does_not_claim_test_execution_or_a_test_binary(self) -> None:
        text = WORKFLOW.read_text()
        compile_step = text.split("name: Compile shared modules and iosTest sources", 1)[1].split("- name:", 1)[0]
        self.assertIn("compileTestKotlinIosSimulatorArm64", compile_step)
        self.assertNotIn("iosSimulatorArm64Test", compile_step)
        self.assertNotIn("buildForTesting", compile_step)
        self.assertNotIn("xcodebuild test", compile_step)

    def test_macos_device_link_gate_compiles_the_real_wrapper_with_core_audio(self) -> None:
        text = WORKFLOW.read_text()
        self.assertLess(text.index("name: Select Xcode 26.4"), text.index("name: Device-link the macOS Core Audio wrapper"))
        self.assertLess(text.index("name: Device-link the macOS Core Audio wrapper"),
                        text.index("name: Check iOS audio source contracts and PCM conversion"))
        step = text.split("name: Device-link the macOS Core Audio wrapper", 1)[1].split("- name:", 1)[0]
        self.assertIn("timeout-minutes: 3", step)
        self.assertIn("ARCHIVETUNE_NATIVE_BUILD", step)
        self.assertIn("source desktopApp/native/scripts/build-native-deps.sh", step)
        self.assertIn("$MINIAUDIO_SHA256", step)
        self.assertIn("$MINIAUDIO_LICENSE_SHA256", step)
        self.assertIn("stage_miniaudio", step)
        self.assertIn('fetch "https://raw.githubusercontent.com/mackron/miniaudio/', step)
        self.assertIn('wrapper="$BUILD/macos-device-link/archivetune_device_wrapper.c"', step)
        include_at = step.index('#include "archivetune_device.c"')
        guard_at = step.index("#if !defined(__APPLE__) || !defined(TARGET_OS_IPHONE) || TARGET_OS_IPHONE")
        core_audio_at = step.index("#ifdef MA_NO_COREAUDIO")
        self.assertLess(include_at, guard_at)
        self.assertLess(guard_at, core_audio_at)
        self.assertIn("xcrun --sdk macosx clang -std=gnu11 -Wall -Wextra", step)
        self.assertIn("-dynamiclib -Wl,-undefined,error", step)
        self.assertIn("-I desktopApp/native/src", step)
        self.assertIn("-framework CoreAudio -framework AudioToolbox -framework CoreFoundation", step)
        self.assertIn('compile_device "$RUNNER_TEMP/libarchivetune_device_macos.dylib"', step)
        self.assertIn('compile_device "$RUNNER_TEMP/natune-device-negative.dylib" -DMA_NO_COREAUDIO', step)
        self.assertIn("NATUNE_COREAUDIO_REQUIRED", step)
        self.assertIn("NATUNE_DESKTOP_MACOS_REQUIRED", step)
        self.assertNotIn("build_ios_native_core.sh", step)
        self.assertNotIn("build_ffmpeg", step)
        self.assertNotIn("xcodebuild", step)
        self.assertNotIn("at_audio_create", step)


if __name__ == "__main__":
    unittest.main()
