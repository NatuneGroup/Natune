/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared.ui.home

import kotlin.test.*

class HomeSourcePolicyTest {
    @Test fun unsetUsesSessionAvailabilityAndHasYouTubeWithoutLogin() {
        assertEquals(listOf(HomeSource.YOUTUBE), resolvedHomeSources("", false, false))
        assertEquals(HomeSource.entries, resolvedHomeSources("", true, true))
    }
    @Test fun signedOutSourceIsFilteredNotErasedAndRestoresOnLogin() {
        val stored = "SPOTIFY,QQ,YOUTUBE"
        assertEquals(listOf(HomeSource.YOUTUBE), resolvedHomeSources(stored, false, false))
        assertEquals(listOf(HomeSource.SPOTIFY, HomeSource.QQ, HomeSource.YOUTUBE), resolvedHomeSources(stored, true, true))
        assertEquals(HomeSource.YOUTUBE, resolvedHomeSource("SPOTIFY", resolvedHomeSources(stored, false, false)))
    }
    @Test fun parserDropsUnknownAndDuplicateNamesButKeepsStoredOrder() {
        assertEquals(listOf(HomeSource.QQ, HomeSource.YOUTUBE),
            resolvedHomeSources(" qq,UNKNOWN,YOUTUBE,QQ ", true, true))
    }
    @Test fun explicitlySpotifyOnlyDoesNotInventYouTubeUntilTheSessionIsGone() {
        // Keep the baseline implementation, even though its prose says YouTube is always part of the set.
        assertEquals(listOf(HomeSource.SPOTIFY), resolvedHomeSources("SPOTIFY", true, false))
        assertEquals(listOf(HomeSource.YOUTUBE), resolvedHomeSources("SPOTIFY", false, false))
    }
}
