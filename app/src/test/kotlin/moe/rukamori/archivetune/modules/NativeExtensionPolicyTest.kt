package moe.rukamori.archivetune.modules

import androidx.datastore.preferences.core.mutablePreferencesOf
import moe.rukamori.archivetune.constants.SpotifySpDcKey
import moe.rukamori.archivetune.constants.SpotifyStreamEnabledKey
import moe.rukamori.archivetune.constants.SpotifyStreamSpDcKey
import moe.rukamori.archivetune.spotify.SpotifyLibrespotRefreshTokenKey
import moe.rukamori.archivetune.spotify.SpotifyStreamEngineKey
import org.junit.Assert.*
import org.junit.Test

class NativeExtensionPolicyTest {
    @Test fun freshInstallationDoesNotEnableNativeExtensions() {
        val prefs = mutablePreferencesOf()
        NativeExtensionPolicy.migrate(prefs)
        NativeExtensionPolicy.keys.keys.forEach { assertFalse(NativeExtensionPolicy.enabled(it, prefs)) }
    }

    @Test fun existingSessionsMigrateIndependentlyWithoutTurningPlaybackSourceOn() {
        val prefs = mutablePreferencesOf(SpotifySpDcKey to "catalog", SpotifyStreamSpDcKey to "alternate", SpotifyLibrespotRefreshTokenKey to "official")
        NativeExtensionPolicy.migrate(prefs)
        NativeExtensionPolicy.keys.keys.forEach { assertTrue(NativeExtensionPolicy.enabled(it, prefs)) }
        assertNull(prefs[SpotifyStreamEnabledKey])
        assertEquals("catalog", prefs[SpotifySpDcKey])
        assertEquals("alternate", prefs[SpotifyStreamSpDcKey])
    }

    @Test fun oldEnabledSourceMigratesOnlyItsSelectedPlaybackExtension() {
        val prefs = mutablePreferencesOf(SpotifyStreamEnabledKey to true, SpotifyStreamEngineKey to "LIBRESPOT")
        NativeExtensionPolicy.migrate(prefs)
        assertTrue(NativeExtensionPolicy.enabled(NativeExtensionIds.SPOTIFY_OFFICIAL, prefs))
        assertFalse(NativeExtensionPolicy.enabled(NativeExtensionIds.SPOTIFY_ALTERNATE, prefs))
    }

    @Test fun explicitChoicesSurviveMigrationAndAccountChanges() {
        val prefs = mutablePreferencesOf(SpotifySpDcKey to "catalog", SpotifyMetadataExtensionEnabledKey to false,
            SpotifyAlternateExtensionEnabledKey to true)
        NativeExtensionPolicy.migrate(prefs)
        prefs.remove(SpotifySpDcKey)
        prefs[SpotifyLibrespotRefreshTokenKey] = "new-session"
        NativeExtensionPolicy.migrate(prefs)
        assertFalse(NativeExtensionPolicy.enabled(NativeExtensionIds.SPOTIFY_METADATA, prefs))
        assertTrue(NativeExtensionPolicy.enabled(NativeExtensionIds.SPOTIFY_ALTERNATE, prefs))
        assertFalse(NativeExtensionPolicy.enabled(NativeExtensionIds.SPOTIFY_OFFICIAL, prefs))
    }

    @Test fun nativePlaybackPriorityUsesExistingEnginePreference() {
        val prefs = mutablePreferencesOf(SpotifyStreamEngineKey to "LIBRESPOT")
        assertEquals(1, NativeExtensionPolicy.priority(NativeExtensionIds.SPOTIFY_OFFICIAL, prefs))
        assertEquals(2, NativeExtensionPolicy.priority(NativeExtensionIds.SPOTIFY_ALTERNATE, prefs))
        prefs[SpotifyStreamEngineKey] = "UNOFFICIAL"
        assertEquals(1, NativeExtensionPolicy.priority(NativeExtensionIds.SPOTIFY_ALTERNATE, prefs))
    }
}
