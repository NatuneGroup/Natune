package moe.rukamori.archivetune.modules

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import moe.rukamori.archivetune.constants.SpotifyAccessTokenKey
import moe.rukamori.archivetune.constants.SpotifySpDcKey
import moe.rukamori.archivetune.constants.SpotifyStreamEnabledKey
import moe.rukamori.archivetune.constants.SpotifyStreamSpDcKey
import moe.rukamori.archivetune.spotify.SpotifyLibrespotRefreshTokenKey
import moe.rukamori.archivetune.spotify.SpotifyStreamEngineKey
import moe.rukamori.archivetune.shared.domain.source.SpotifyStreamEngine

val SpotifyMetadataExtensionEnabledKey = booleanPreferencesKey("extension.spotify.metadata.enabled")
val SpotifyAlternateExtensionEnabledKey = booleanPreferencesKey("extension.spotify.alternate.enabled")
val SpotifyOfficialExtensionEnabledKey = booleanPreferencesKey("extension.spotify.librespot.enabled")

object NativeExtensionIds {
    const val SPOTIFY_METADATA = "app.natune.native.spotify.metadata"
    const val SPOTIFY_ALTERNATE = "app.natune.native.spotify.alternate"
    const val SPOTIFY_OFFICIAL = "app.natune.native.spotify.librespot"
}

/** Account credentials and the existing source switch stay separate from extension enablement. */
object NativeExtensionPolicy {
    val keys = mapOf(
        NativeExtensionIds.SPOTIFY_METADATA to SpotifyMetadataExtensionEnabledKey,
        NativeExtensionIds.SPOTIFY_ALTERNATE to SpotifyAlternateExtensionEnabledKey,
        NativeExtensionIds.SPOTIFY_OFFICIAL to SpotifyOfficialExtensionEnabledKey,
    )

    fun enabled(id: String, prefs: Preferences): Boolean = prefs[keys.getValue(id)] ?: legacyEnabled(id, prefs)

    fun legacyEnabled(id: String, prefs: Preferences): Boolean = when (id) {
        NativeExtensionIds.SPOTIFY_METADATA -> !prefs[SpotifySpDcKey].isNullOrBlank() || !prefs[SpotifyAccessTokenKey].isNullOrBlank()
        NativeExtensionIds.SPOTIFY_ALTERNATE -> !prefs[SpotifyStreamSpDcKey].isNullOrBlank() ||
            (prefs[SpotifyStreamEnabledKey] == true && SpotifyStreamEngine.fromPreference(prefs[SpotifyStreamEngineKey]) == SpotifyStreamEngine.UNOFFICIAL)
        NativeExtensionIds.SPOTIFY_OFFICIAL -> !prefs[SpotifyLibrespotRefreshTokenKey].isNullOrBlank() ||
            (prefs[SpotifyStreamEnabledKey] == true && SpotifyStreamEngine.fromPreference(prefs[SpotifyStreamEngineKey]) == SpotifyStreamEngine.LIBRESPOT)
        else -> false
    }

    /** Write only absent keys, once; later sign-out or a second migration never resets a choice. */
    fun migrate(prefs: MutablePreferences) {
        val defaults = keys.keys.associateWith { legacyEnabled(it, prefs) }
        keys.forEach { (id, key) -> if (prefs[key] == null) prefs[key] = defaults.getValue(id) }
    }

    fun priority(id: String, prefs: Preferences): Int {
        if (id == NativeExtensionIds.SPOTIFY_METADATA) return 1
        val officialFirst = SpotifyStreamEngine.fromPreference(prefs[SpotifyStreamEngineKey]) == SpotifyStreamEngine.LIBRESPOT
        return if ((id == NativeExtensionIds.SPOTIFY_OFFICIAL) == officialFirst) 1 else 2
    }
}
