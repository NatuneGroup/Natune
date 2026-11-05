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


if __name__ == "__main__":
    unittest.main()
