# SPDX-License-Identifier: GPL-3.0-only
# Copyright (c) 2026 vossgraves
"""Source contracts for the project's own support and About links.

The support sheet and the onboarding community list must open the public project
repository and must not offer a donation action whose target is a placeholder
personal repository page. These are text-level contracts because the links live
inside Compose callbacks; the checker runs without Gradle, Android or network.
"""

from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parent.parent

UPDATE_MODELS = (
    ROOT / "shared-domain/src/commonMain/kotlin/moe/rukamori/archivetune/shared/domain/update/UpdateModels.kt"
)
STAR_DIALOG = ROOT / "app/src/main/kotlin/moe/rukamori/archivetune/ui/component/StarDialog.kt"
ONBOARDING = ROOT / "app/src/main/kotlin/moe/rukamori/archivetune/onboarding/OnboardingUseCases.kt"
ABOUT_VIEW_MODEL = ROOT / "app/src/main/kotlin/moe/rukamori/archivetune/viewmodels/AboutViewModel.kt"

PLACEHOLDER_URLS = ("vossgraves/ArchiveTune", "rukamori/ArchiveTune")


class ProjectLinksTest(unittest.TestCase):
    def test_the_canonical_project_location_is_defined_once(self):
        text = UPDATE_MODELS.read_text(encoding="utf-8")
        self.assertIn('const val OWNER: String = "NatuneGroup/Natune"', text)
        self.assertIn('const val REPOSITORY_URL: String = "https://github.com/$OWNER"', text)

    def test_star_dialog_keeps_the_star_action_on_the_project_repository(self):
        text = STAR_DIALOG.read_text(encoding="utf-8")
        self.assertIn("import moe.rukamori.archivetune.shared.domain.update.UpdateRepository", text)
        self.assertIn("uriHandler.openUri(UpdateRepository.REPOSITORY_URL)", text)
        self.assertIn("R.string.support_development_star", text)

    def test_star_dialog_offers_no_placeholder_donation(self):
        text = STAR_DIALOG.read_text(encoding="utf-8")
        self.assertNotIn("support_development_donate", text)
        for url in PLACEHOLDER_URLS:
            self.assertNotIn(url, text)

    def test_onboarding_community_list_links_the_project(self):
        text = ONBOARDING.read_text(encoding="utf-8")
        self.assertIn("import moe.rukamori.archivetune.shared.domain.update.UpdateRepository", text)
        self.assertIn("url = UpdateRepository.REPOSITORY_URL", text)
        self.assertIn("R.string.support_development_star", text)

    def test_onboarding_community_list_offers_no_placeholder_donation(self):
        text = ONBOARDING.read_text(encoding="utf-8")
        self.assertNotIn('id = "donate"', text)
        self.assertNotIn("about_content_desc_donate", text)
        self.assertNotIn("onboarding_community_donate_desc", text)
        for url in PLACEHOLDER_URLS:
            self.assertNotIn(url, text)

    def test_about_page_uses_the_same_project_location(self):
        text = ABOUT_VIEW_MODEL.read_text(encoding="utf-8")
        self.assertIn("import moe.rukamori.archivetune.shared.domain.update.UpdateRepository", text)
        self.assertIn("url = UpdateRepository.REPOSITORY_URL", text)


if __name__ == "__main__":
    unittest.main()
