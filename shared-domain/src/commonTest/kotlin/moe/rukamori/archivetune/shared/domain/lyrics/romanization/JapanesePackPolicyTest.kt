/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.lyrics.romanization

import kotlin.test.*

/** Characterization of the baseline manager's predicates, including manual-install enable semantics. */
class JapanesePackPolicyTest {
    @Test fun exactPrimaryRegionAndPersistedGuardsMatchBaseline() {
        for (region in listOf(null, "", "US", "JP", "jp", "Jp", " JP", "JPN")) {
            for (installed in listOf(false, true)) for (choice in listOf(null, false, true)) {
                for (removed in listOf(false, true)) for (attempted in listOf(false, true)) {
                    val baseline = region.equals("JP", ignoreCase = true) && !installed &&
                        choice != false && !removed && !attempted
                    assertEquals(baseline, japanesePackAutomaticEligible(region, installed, choice, removed, attempted))
                }
            }
        }
    }

    @Test fun installationEnableRulesKeepExistingManualQuirkAndProtectChangedChoice() {
        for (automatic in listOf(false, true)) for (starting in listOf(null, false, true)) {
            for (current in listOf(null, false, true)) for (removed in listOf(false, true)) {
                val baseline = if (automatic) current != false && !removed else current == starting
                assertEquals(baseline, japanesePackShouldEnable(automatic, starting, current, removed))
            }
        }
        // Explicit manual install with an unchanged false choice enables, exactly as baseline.
        assertTrue(japanesePackShouldEnable(false, false, false, false))
        assertFalse(japanesePackShouldEnable(false, null, false, false))
    }

    @Test fun progressIsIndeterminateOrBelowOneHundredUntilReadiness() {
        assertEquals(-1, japanesePackProgress(100, -1))
        assertEquals(-1, japanesePackProgress(100, 0))
        assertEquals(0, japanesePackProgress(0, 1000))
        assertEquals(50, japanesePackProgress(500, 1000))
        assertEquals(99, japanesePackProgress(1000, 1000))
        assertEquals(99, japanesePackProgress(1001, 1000))
        assertEquals(-1, JapaneseLanguagePackState.Downloading().progressPercent)
        assertTrue(JapaneseLanguagePackState.Failed(JapanesePackFailure.REMOVE, true).removalFailed)
    }
}
