"""Synthetic profile metadata exercises capability matching, not Apple approval or signing."""
import copy
import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("capabilities", ROOT / "iosApp/scripts/check_signing_capabilities.py")
capabilities = importlib.util.module_from_spec(spec)
spec.loader.exec_module(capabilities)


class SigningCapabilitiesTest(unittest.TestCase):
    def profiles(self):
        def profile(bundle):
            return {"Entitlements": {"application-identifier": "TESTTEAM." + bundle,
                "com.apple.developer.team-identifier": "TESTTEAM",
                "com.apple.security.application-groups": ["group.test.natune"]}}
        app = profile(capabilities.APP_BUNDLE)
        app["Entitlements"]["com.apple.developer.carplay-audio"] = True
        return app, profile(capabilities.WIDGET_BUNDLE)

    def test_matching_profiles(self):
        capabilities.validate_profiles(*self.profiles(), "group.test.natune", "TESTTEAM")

    def test_missing_grants_or_foreign_identity_are_refused(self):
        changes = [(0, "com.apple.developer.carplay-audio", False),
                   (1, "com.apple.security.application-groups", []),
                   (1, "com.apple.security.application-groups", "group.test.natune"),
                   (1, "com.apple.developer.team-identifier", "OTHERTM"),
                   (0, "application-identifier", "TESTTEAM.*")]
        for index, key, value in changes:
            with self.subTest(key=key):
                pair = copy.deepcopy(self.profiles())
                pair[index]["Entitlements"][key] = value
                with self.assertRaises(ValueError):
                    capabilities.validate_profiles(*pair, "group.test.natune")

    def test_app_and_widget_use_one_override_file(self):
        shared = (ROOT / "iosApp/Configuration/SharedSigning.xcconfig").read_text()
        self.assertIn('#include? "Signing.xcconfig"', shared)
        native = (ROOT / "iosApp/Configuration/Native.xcconfig").read_text()
        self.assertIn('#include "SharedSigning.xcconfig"', native)
        project = (ROOT / "iosApp/ArchiveTune.xcodeproj/project.pbxproj").read_text()
        self.assertEqual(2, project.count('baseConfigurationReference = B00000000000000000000018'))
        self.assertNotIn('ARCHIVETUNE_APP_GROUP = group.moe.rukamori.archivetune;', project)
