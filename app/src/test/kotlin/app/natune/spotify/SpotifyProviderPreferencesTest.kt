package app.natune.spotify

import androidx.datastore.preferences.core.mutablePreferencesOf
import app.natune.constants.SpotifySpDcKey
import app.natune.constants.SpotifyStreamEnabledKey
import app.natune.constants.SpotifyStreamSpDcKey
import app.natune.constants.SpotifyAccessTokenKey
import app.natune.shared.domain.source.SpotifyProviderIds
import org.junit.Assert.*
import org.junit.Test

class SpotifyProviderPreferencesTest {
    @Test fun freshInstallationDoesNotEnableNativeExtensions() {
        val prefs = mutablePreferencesOf()
        SpotifyProviderPreferences.migrate(prefs)
        SpotifyProviderPreferences.keys.keys.forEach { assertFalse(SpotifyProviderPreferences.enabled(it, prefs)) }
    }

    @Test fun existingSessionsMigrateIndependentlyWithoutTurningPlaybackSourceOn() {
        val prefs = mutablePreferencesOf(SpotifySpDcKey to "catalog", SpotifyStreamSpDcKey to "alternate", SpotifyLibrespotRefreshTokenKey to "official")
        SpotifyProviderPreferences.migrate(prefs)
        SpotifyProviderPreferences.keys.keys.forEach { assertTrue(SpotifyProviderPreferences.enabled(it, prefs)) }
        assertNull(prefs[SpotifyStreamEnabledKey])
        assertEquals("catalog", prefs[SpotifySpDcKey])
        assertEquals("alternate", prefs[SpotifyStreamSpDcKey])
    }

    @Test fun oldEnabledSourceMigratesOnlyItsSelectedPlaybackExtension() {
        val prefs = mutablePreferencesOf(SpotifyStreamEnabledKey to true, SpotifyStreamEngineKey to "LIBRESPOT")
        SpotifyProviderPreferences.migrate(prefs)
        assertTrue(SpotifyProviderPreferences.enabled(SpotifyProviderIds.LIBRESPOT, prefs))
        assertFalse(SpotifyProviderPreferences.enabled(SpotifyProviderIds.ALTERNATE, prefs))
    }

    @Test fun explicitChoicesSurviveMigrationAndAccountChanges() {
        val prefs = mutablePreferencesOf(SpotifySpDcKey to "catalog", SpotifyCatalogEnabledKey to false,
            SpotifyAlternateEnabledKey to true)
        SpotifyProviderPreferences.migrate(prefs)
        prefs.remove(SpotifySpDcKey)
        prefs[SpotifyLibrespotRefreshTokenKey] = "new-session"
        SpotifyProviderPreferences.migrate(prefs)
        assertFalse(SpotifyProviderPreferences.enabled(SpotifyProviderIds.CATALOG, prefs))
        assertTrue(SpotifyProviderPreferences.enabled(SpotifyProviderIds.ALTERNATE, prefs))
        assertFalse(SpotifyProviderPreferences.enabled(SpotifyProviderIds.LIBRESPOT, prefs))
    }

    @Test fun nativePlaybackPriorityUsesExistingEnginePreference() {
        val prefs = mutablePreferencesOf(SpotifyStreamEngineKey to "LIBRESPOT")
        assertEquals(1, SpotifyProviderPreferences.priority(SpotifyProviderIds.LIBRESPOT, prefs))
        assertEquals(2, SpotifyProviderPreferences.priority(SpotifyProviderIds.ALTERNATE, prefs))
        prefs[SpotifyStreamEngineKey] = "UNOFFICIAL"
        assertEquals(1, SpotifyProviderPreferences.priority(SpotifyProviderIds.ALTERNATE, prefs))
    }

    @Test fun explicitFalseRefusesEveryProviderWithSessionsPresent() {
        val prefs = mutablePreferencesOf(SpotifySpDcKey to "catalog", SpotifyStreamSpDcKey to "alternate",
            SpotifyLibrespotRefreshTokenKey to "official", SpotifyCatalogEnabledKey to false,
            SpotifyAlternateEnabledKey to false, SpotifyLibrespotEnabledKey to false)
        SpotifyProviderPreferences.migrate(prefs)
        SpotifyProviderPreferences.keys.keys.forEach { assertFalse(SpotifyProviderPreferences.enabled(it, prefs)) }
        assertFalse(SpotifyProviderPreferences.playbackAvailable(prefs))
    }

    @Test fun accessTokenOnlyCatalogSessionAndDefaultUnofficialSourceStillMigrate() {
        val prefs = mutablePreferencesOf(SpotifyAccessTokenKey to "catalog-token", SpotifyStreamEnabledKey to true)
        SpotifyProviderPreferences.migrate(prefs)
        assertTrue(SpotifyProviderPreferences.enabled(SpotifyProviderIds.CATALOG, prefs))
        assertTrue(SpotifyProviderPreferences.enabled(SpotifyProviderIds.ALTERNATE, prefs))
        assertFalse(SpotifyProviderPreferences.enabled(SpotifyProviderIds.LIBRESPOT, prefs))
        assertFalse(SpotifyProviderPreferences.playbackAvailable(prefs))
        assertEquals("catalog-token", prefs[SpotifyAccessTokenKey])
        assertTrue(prefs[SpotifyStreamEnabledKey] == true)
        assertNull(prefs[SpotifyStreamEngineKey])
    }

    @Test fun compatibilityAliasesKeepKeysIdsAndMigration() {
        assertEquals("extension.spotify.metadata.enabled", SpotifyCatalogEnabledKey.name)
        assertEquals("extension.spotify.alternate.enabled", SpotifyAlternateEnabledKey.name)
        assertEquals("extension.spotify.librespot.enabled", SpotifyLibrespotEnabledKey.name)
        assertEquals(SpotifyCatalogEnabledKey, app.natune.modules.SpotifyMetadataExtensionEnabledKey)
        assertEquals(SpotifyAlternateEnabledKey, app.natune.modules.SpotifyAlternateExtensionEnabledKey)
        assertEquals(SpotifyLibrespotEnabledKey, app.natune.modules.SpotifyOfficialExtensionEnabledKey)
        assertEquals(SpotifyProviderIds.CATALOG, app.natune.modules.NativeExtensionIds.SPOTIFY_METADATA)
        val prefs = mutablePreferencesOf(SpotifySpDcKey to "catalog")
        app.natune.modules.NativeExtensionPolicy.migrate(prefs)
        assertTrue(SpotifyProviderPreferences.enabled(SpotifyProviderIds.CATALOG, prefs))
    }

    @Test fun preferenceAdapterReproducesLegacyAvailabilityForEveryEngineSessionAndChoice() {
        for (engine in listOf("LIBRESPOT", "UNOFFICIAL", "unknown")) {
            for (product in listOf("premium", "free", "")) {
                for (alternate in listOf("", "alternate")) for (official in listOf("", "official")) {
                    for (alternateChoice in listOf(null, false, true)) for (officialChoice in listOf(null, false, true)) {
                        val prefs = mutablePreferencesOf(SpotifyStreamEngineKey to engine, SpotifyProductKey to product,
                            SpotifyStreamProductKey to "premium", SpotifyStreamSpDcKey to alternate,
                            SpotifyLibrespotRefreshTokenKey to official, SpotifyStreamEnabledKey to false,
                            SpotifyAccessTokenKey to "unrelated-catalog-token", SpotifyCatalogEnabledKey to false)
                        alternateChoice?.let { prefs[SpotifyAlternateEnabledKey] = it }
                        officialChoice?.let { prefs[SpotifyLibrespotEnabledKey] = it }
                        val effectiveOfficial = engine == "LIBRESPOT" && product == "premium"
                        val expected = if (effectiveOfficial) official.isNotBlank() && (officialChoice ?: official.isNotBlank())
                            else alternate.isNotBlank() && (alternateChoice ?: alternate.isNotBlank())
                        assertEquals("$engine/$product/$alternateChoice/$officialChoice", expected,
                            SpotifyProviderPreferences.playbackAvailable(prefs))
                    }
                }
            }
        }
    }
}
