package moe.rukamori.archivetune.desktop.integration

import kotlin.test.*
import moe.rukamori.archivetune.desktop.desktopListenTogetherCode

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

    @Test fun listenInvitesCarryTheRoomCodeLikeTheAndroidHost() {
        // The Android handler reads ?code=, then ?room=, then pathSegments[1]; join() uppercases.
        assertEquals("abcd12", desktopListenTogetherCode("archivetune://listen?code=abcd12"))
        assertEquals("abcd12", desktopListenTogetherCode("archivetune://listen?room=abcd12"))
        assertEquals("ABCD12", desktopListenTogetherCode("https://devilmi-vivi-music-listen-together.hf.space/listen/ABCD12"))
        assertEquals("ABCD12", desktopListenTogetherCode("https://vivimusic-listen-together.onrender.com/listen?code=ABCD12"))
        // Any /listen path counts on the Android handler, not only the two invite hosts.
        assertEquals("ABCD12", desktopListenTogetherCode("https://unrelated.example/listen?code=ABCD12"))
        // A codeless listen link and a non-listen link both open no room.
        assertNull(desktopListenTogetherCode("archivetune://listen"))
        assertNull(desktopListenTogetherCode("archivetune://open/track/123"))
        assertNull(desktopListenTogetherCode("not a uri"))
    }
}
