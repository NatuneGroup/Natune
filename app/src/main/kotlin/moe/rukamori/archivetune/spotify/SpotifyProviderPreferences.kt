package moe.rukamori.archivetune.spotify

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import moe.rukamori.archivetune.constants.SpotifyAccessTokenKey
import moe.rukamori.archivetune.constants.SpotifySpDcKey
import moe.rukamori.archivetune.constants.SpotifyStreamEnabledKey
import moe.rukamori.archivetune.constants.SpotifyStreamSpDcKey
import moe.rukamori.archivetune.shared.domain.source.SpotifyProduct
import moe.rukamori.archivetune.shared.domain.source.SpotifyProvider
import moe.rukamori.archivetune.shared.domain.source.SpotifyProviderPolicy
import moe.rukamori.archivetune.shared.domain.source.SpotifyProviderSnapshot
import moe.rukamori.archivetune.shared.domain.source.SpotifyStreamEngine

// Serialized names remain compatible with existing settings and backups.
val SpotifyCatalogEnabledKey = booleanPreferencesKey("extension.spotify.metadata.enabled")
val SpotifyAlternateEnabledKey = booleanPreferencesKey("extension.spotify.alternate.enabled")
val SpotifyLibrespotEnabledKey = booleanPreferencesKey("extension.spotify.librespot.enabled")

/** Android preference adapter for the plain Spotify policy. It owns no module or account lifecycle. */
object SpotifyProviderPreferences {
    val keys = mapOf(
        SpotifyProvider.CATALOG.id to SpotifyCatalogEnabledKey,
        SpotifyProvider.ALTERNATE.id to SpotifyAlternateEnabledKey,
        SpotifyProvider.LIBRESPOT.id to SpotifyLibrespotEnabledKey,
    )

    fun snapshot(prefs: Preferences): SpotifyProviderSnapshot = SpotifyProviderSnapshot(
        catalogEnabled = prefs[SpotifyCatalogEnabledKey],
        alternateEnabled = prefs[SpotifyAlternateEnabledKey],
        librespotEnabled = prefs[SpotifyLibrespotEnabledKey],
        catalogCookiePresent = !prefs[SpotifySpDcKey].isNullOrBlank(),
        catalogTokenPresent = !prefs[SpotifyAccessTokenKey].isNullOrBlank(),
        alternateSessionPresent = !prefs[SpotifyStreamSpDcKey].isNullOrBlank(),
        librespotSessionPresent = !prefs[SpotifyLibrespotRefreshTokenKey].isNullOrBlank(),
        playbackSourceEnabled = prefs[SpotifyStreamEnabledKey] == true,
        storedEngine = SpotifyStreamEngine.fromPreference(prefs[SpotifyStreamEngineKey]),
        catalogProduct = SpotifyProduct.of(prefs[SpotifyProductKey]),
        alternateProduct = SpotifyProduct.of(prefs[SpotifyStreamProductKey]),
    )

    fun enabled(id: String, prefs: Preferences): Boolean {
        keys.getValue(id) // Preserve the legacy API's refusal of unknown native IDs.
        return SpotifyProviderPolicy.enabled(requireNotNull(SpotifyProvider.fromId(id)), snapshot(prefs))
    }

    fun legacyEnabled(id: String, prefs: Preferences): Boolean =
        SpotifyProvider.fromId(id)?.let { SpotifyProviderPolicy.legacyEnabled(it, snapshot(prefs)) } ?: false

    fun migrate(prefs: MutablePreferences) {
        val migrated = SpotifyProviderPolicy.migrate(snapshot(prefs))
        if (prefs[SpotifyCatalogEnabledKey] == null) prefs[SpotifyCatalogEnabledKey] = requireNotNull(migrated.catalogEnabled)
        if (prefs[SpotifyAlternateEnabledKey] == null) prefs[SpotifyAlternateEnabledKey] = requireNotNull(migrated.alternateEnabled)
        if (prefs[SpotifyLibrespotEnabledKey] == null) prefs[SpotifyLibrespotEnabledKey] = requireNotNull(migrated.librespotEnabled)
    }

    fun priority(id: String, prefs: Preferences): Int = SpotifyProvider.fromId(id)?.let {
        SpotifyProviderPolicy.priority(it, snapshot(prefs))
    } ?: if (SpotifyStreamEngine.fromPreference(prefs[SpotifyStreamEngineKey]) == SpotifyStreamEngine.LIBRESPOT) 2 else 1

    fun move(id: String, delta: Int, prefs: MutablePreferences) {
        val provider = SpotifyProvider.fromId(id) ?: return
        SpotifyProviderPolicy.movedEngine(provider, delta, snapshot(prefs))?.let { prefs[SpotifyStreamEngineKey] = it.name }
    }

    fun playbackAvailable(prefs: Preferences): Boolean = SpotifyProviderPolicy.playbackAvailable(snapshot(prefs))
}
