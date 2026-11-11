package moe.rukamori.archivetune.shared.domain.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpotifyProviderPolicyTest {
    @Test fun absentAndBlankSessionsStayDisabledAfterRepeatedMigration() {
        val old = SpotifyProviderSnapshot()
        val migrated = SpotifyProviderPolicy.migrate(old)
        SpotifyProvider.entries.forEach { assertFalse(SpotifyProviderPolicy.enabled(it, migrated)) }
        assertEquals(migrated, SpotifyProviderPolicy.migrate(migrated))
        assertFalse(SpotifyProviderPolicy.playbackAvailable(migrated))
    }

    @Test fun selectedEngineControlsOnlyAbsentLegacyDefaults() {
        SpotifyStreamEngine.entries.forEach { engine ->
            val old = SpotifyProviderSnapshot(playbackSourceEnabled = true, storedEngine = engine)
            val migrated = SpotifyProviderPolicy.migrate(old)
            assertEquals(engine == SpotifyStreamEngine.UNOFFICIAL, migrated.alternateEnabled)
            assertEquals(engine == SpotifyStreamEngine.LIBRESPOT, migrated.librespotEnabled)
            assertFalse(SpotifyProviderPolicy.playbackAvailable(migrated))
            assertEquals(engine, migrated.storedEngine)
        }
    }

    @Test fun explicitFalseAndTrueSurviveLaterSessionsAndEngineChanges() {
        val old = SpotifyProviderSnapshot(catalogEnabled = false, alternateEnabled = true, librespotEnabled = false)
        val changed = SpotifyProviderPolicy.migrate(old).copy(catalogCookiePresent = true,
            alternateSessionPresent = true, librespotSessionPresent = true, storedEngine = SpotifyStreamEngine.LIBRESPOT)
        val migrated = SpotifyProviderPolicy.migrate(changed)
        assertFalse(SpotifyProviderPolicy.enabled(SpotifyProvider.CATALOG, migrated))
        assertTrue(SpotifyProviderPolicy.enabled(SpotifyProvider.ALTERNATE, migrated))
        assertFalse(SpotifyProviderPolicy.enabled(SpotifyProvider.LIBRESPOT, migrated))
    }

    @Test fun premiumAndSessionGateFollowTheProductOfTheStoredEnginesAccount() {
        val old = SpotifyProviderSnapshot(storedEngine = SpotifyStreamEngine.LIBRESPOT,
            alternateSessionPresent = true, librespotSessionPresent = true, alternateProduct = SpotifyProduct.PREMIUM)
        SpotifyProduct.entries.forEach { catalogProduct ->
            val snapshot = old.copy(catalogProduct = catalogProduct)
            val expected = if (catalogProduct == SpotifyProduct.PREMIUM) SpotifyStreamEngine.LIBRESPOT else SpotifyStreamEngine.UNOFFICIAL
            assertEquals(expected, SpotifyProviderPolicy.effectiveEngine(snapshot))
            assertTrue(SpotifyProviderPolicy.playbackAvailable(snapshot))
            assertFalse(SpotifyProviderPolicy.playbackAvailable(snapshot.copy(
                alternateEnabled = false, librespotEnabled = false)))
            assertFalse(SpotifyProviderPolicy.playbackAvailable(snapshot.copy(
                alternateSessionPresent = expected == SpotifyStreamEngine.LIBRESPOT,
                librespotSessionPresent = expected == SpotifyStreamEngine.UNOFFICIAL)))
            assertEquals(SpotifyStreamEngine.LIBRESPOT, snapshot.storedEngine)
        }
        assertEquals(SpotifyStreamEngine.UNOFFICIAL, SpotifyProviderPolicy.effectiveEngine(old.copy(
            storedEngine = SpotifyStreamEngine.UNOFFICIAL, catalogProduct = SpotifyProduct.PREMIUM)))
    }

    @Test fun providerEnablementDoesNotReplaceSameAccountTokenAdmission() {
        val enabled = SpotifyProviderSnapshot(alternateSessionPresent = true)
        assertTrue(SpotifyProviderPolicy.playbackAvailable(enabled))
        val standsDown = SpotifyProviderPolicy.effectiveEngine(enabled).streamingStandsDown(
            "catalog-cookie", "alternate-cookie", "same-user", { "same-user" })
        assertTrue(standsDown)
        assertFalse(SpotifyStreamingPolicy.accessTokenAllowed("alternate-cookie", standsDown))
        assertEquals(SpotifySignInDecision.Refuse(SpotifySignInRefusal.SAME_AS_CATALOG),
            SpotifyStreamingPolicy.decideSignIn("catalog-cookie", "alternate-cookie", true, "same-user", { "same-user" }))
        assertFalse(SpotifyStreamEngine.LIBRESPOT.streamingStandsDown("same-cookie", "same-cookie", "same-user", { "same-user" }))
    }

    @Test fun priorityMovesChangeOnlyTheEngineAndRespectTheTwoRowBounds() {
        val old = SpotifyProviderSnapshot(storedEngine = SpotifyStreamEngine.LIBRESPOT)
        assertEquals(1, SpotifyProviderPolicy.priority(SpotifyProvider.CATALOG, old))
        assertEquals(1, SpotifyProviderPolicy.priority(SpotifyProvider.LIBRESPOT, old))
        assertEquals(2, SpotifyProviderPolicy.priority(SpotifyProvider.ALTERNATE, old))
        assertNull(SpotifyProviderPolicy.movedEngine(SpotifyProvider.LIBRESPOT, -1, old))
        assertNull(SpotifyProviderPolicy.movedEngine(SpotifyProvider.CATALOG, 1, old))
        assertNull(SpotifyProviderPolicy.movedEngine(SpotifyProvider.ALTERNATE, 2, old))
        assertEquals(SpotifyStreamEngine.UNOFFICIAL, SpotifyProviderPolicy.movedEngine(SpotifyProvider.ALTERNATE, -1, old))
        assertEquals(SpotifyStreamEngine.UNOFFICIAL, SpotifyProviderPolicy.movedEngine(SpotifyProvider.LIBRESPOT, 1, old))
        val unofficial = old.copy(storedEngine = SpotifyStreamEngine.UNOFFICIAL)
        assertEquals(SpotifyStreamEngine.LIBRESPOT, SpotifyProviderPolicy.movedEngine(SpotifyProvider.LIBRESPOT, -1, unofficial))
        assertEquals(SpotifyStreamEngine.LIBRESPOT, SpotifyProviderPolicy.movedEngine(SpotifyProvider.ALTERNATE, 1, unofficial))
    }
}
