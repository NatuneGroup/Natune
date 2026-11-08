package moe.rukamori.archivetune.discord

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DiscordPresenceBackendTest {
    @Test fun absentSdkFallbackNeverReportsAStartedPresence() = runTest {
        assertFalse(NoDiscordPresenceBackend.available)
        assertFalse(NoDiscordPresenceBackend.started)
        val activity = DiscordPresenceActivity(1569030685010821170L, "Natune", DiscordActivityType.Listening, "Song", "Artist")
        assertTrue(NoDiscordPresenceBackend.update("synthetic-oauth", activity).exceptionOrNull() is DiscordSdkUnavailableException)
        assertTrue(NoDiscordPresenceBackend.clear().isSuccess)
        assertTrue(NoDiscordPresenceBackend.close().isSuccess)
    }
}
