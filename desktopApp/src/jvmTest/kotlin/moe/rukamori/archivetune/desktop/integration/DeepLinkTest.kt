/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.desktop.integration

import kotlin.test.*

class DeepLinkTest {
    @Test fun callbackRequiresExactSchemeHostPathAndSingleMatchingState() {
        val intake = DesktopDeepLinks(setOf("archivetune"), setOf("open"))
        val received = mutableListOf<String>()
        intake.expectCallback("archivetune://oauth/discord", "expected", received::add)
        for (uri in listOf(
            "other://oauth/discord?state=expected",
            "archivetune://evil/discord?state=expected",
            "archivetune://oauth/other?state=expected",
            "archivetune://oauth/discord?state=wrong",
            "archivetune://oauth/discord?state=expected&state=expected",
            "archivetune://user@oauth/discord?state=expected",
            "archivetune://oauth:443/discord?state=expected",
        )) assertFalse(intake.accept(uri), uri)
        assertTrue(intake.accept("archivetune://oauth/discord?state=expected&code=one"))
        assertFalse(intake.accept("archivetune://oauth/discord?state=expected&code=replay"))
        assertEquals(1, received.size)
    }

    @Test fun appLinksQueueUntilConsumerExistsButUnknownHostsNeverEnter() {
        val intake = DesktopDeepLinks(setOf("archivetune"), setOf("open"))
        assertTrue(intake.accept("archivetune://open/track/123"))
        assertFalse(intake.accept("archivetune://arbitrary/track/123"))
        assertFalse(intake.accept("https://open/track/123"))
        assertFalse(intake.accept("archivetune://open/track/%ZZ"))
        val received = mutableListOf<String>()
        intake.attach(received::add)
        assertEquals(listOf("archivetune://open/track/123"), received)
    }

    @Test fun cancellingOldAuthCannotCancelNewAccountAttempt() {
        val intake = DesktopDeepLinks(setOf("archivetune"), setOf("open"))
        val old = intake.expectCallback("archivetune://oauth/spotify", "old") {}
        val received = mutableListOf<String>()
        intake.expectCallback("archivetune://oauth/spotify", "new", received::add)
        old.close()
        assertTrue(intake.accept("archivetune://oauth/spotify?state=new"))
        assertEquals(1, received.size)
    }
}
