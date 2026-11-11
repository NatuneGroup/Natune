package moe.rukamori.archivetune.spotify

import androidx.datastore.core.DataStore
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
import moe.rukamori.archivetune.utils.get

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

    /** Enable gates read only their role's session; streaming never inspects the catalog bearer token. */
    private fun roleSnapshot(provider: SpotifyProvider, prefs: Preferences): SpotifyProviderSnapshot = when (provider) {
        SpotifyProvider.CATALOG -> SpotifyProviderSnapshot(
            catalogEnabled = prefs[SpotifyCatalogEnabledKey],
            catalogCookiePresent = !prefs[SpotifySpDcKey].isNullOrBlank(),
            catalogTokenPresent = !prefs[SpotifyAccessTokenKey].isNullOrBlank(),
        )
        SpotifyProvider.ALTERNATE -> SpotifyProviderSnapshot(
            alternateEnabled = prefs[SpotifyAlternateEnabledKey],
            alternateSessionPresent = !prefs[SpotifyStreamSpDcKey].isNullOrBlank(),
            playbackSourceEnabled = prefs[SpotifyStreamEnabledKey] == true,
            storedEngine = SpotifyStreamEngine.fromPreference(prefs[SpotifyStreamEngineKey]),
        )
        SpotifyProvider.LIBRESPOT -> SpotifyProviderSnapshot(
            librespotEnabled = prefs[SpotifyLibrespotEnabledKey],
            librespotSessionPresent = !prefs[SpotifyLibrespotRefreshTokenKey].isNullOrBlank(),
            playbackSourceEnabled = prefs[SpotifyStreamEnabledKey] == true,
            storedEngine = SpotifyStreamEngine.fromPreference(prefs[SpotifyStreamEngineKey]),
        )
    }

    fun enabled(id: String, prefs: Preferences): Boolean {
        val key = keys.getValue(id)
        return prefs[key] ?: legacyEnabled(id, prefs)
    }

    fun legacyEnabled(id: String, prefs: Preferences): Boolean = SpotifyProvider.fromId(id)?.let {
        SpotifyProviderPolicy.legacyEnabled(it, roleSnapshot(it, prefs))
    } ?: false

    fun migrate(prefs: MutablePreferences) {
        // Gather every absent-key inference before writing, as the legacy migration did.
        val migrated = SpotifyProviderPolicy.migrate(SpotifyProviderSnapshot(
            catalogEnabled = prefs[SpotifyCatalogEnabledKey],
            alternateEnabled = prefs[SpotifyAlternateEnabledKey],
            librespotEnabled = prefs[SpotifyLibrespotEnabledKey],
            catalogCookiePresent = !prefs[SpotifySpDcKey].isNullOrBlank(),
            catalogTokenPresent = !prefs[SpotifyAccessTokenKey].isNullOrBlank(),
            alternateSessionPresent = !prefs[SpotifyStreamSpDcKey].isNullOrBlank(),
            librespotSessionPresent = !prefs[SpotifyLibrespotRefreshTokenKey].isNullOrBlank(),
            playbackSourceEnabled = prefs[SpotifyStreamEnabledKey] == true,
            storedEngine = SpotifyStreamEngine.fromPreference(prefs[SpotifyStreamEngineKey]),
        ))
        if (prefs[SpotifyCatalogEnabledKey] == null) prefs[SpotifyCatalogEnabledKey] = requireNotNull(migrated.catalogEnabled)
        if (prefs[SpotifyAlternateEnabledKey] == null) prefs[SpotifyAlternateEnabledKey] = requireNotNull(migrated.alternateEnabled)
        if (prefs[SpotifyLibrespotEnabledKey] == null) prefs[SpotifyLibrespotEnabledKey] = requireNotNull(migrated.librespotEnabled)
    }

    fun priority(id: String, prefs: Preferences): Int = SpotifyProvider.fromId(id)?.let {
        SpotifyProviderPolicy.priority(it, engineSnapshot(prefs))
    } ?: if (SpotifyStreamEngine.fromPreference(prefs[SpotifyStreamEngineKey]) == SpotifyStreamEngine.LIBRESPOT) 2 else 1

    fun move(id: String, delta: Int, prefs: MutablePreferences) {
        val provider = SpotifyProvider.fromId(id) ?: return
        SpotifyProviderPolicy.movedEngine(provider, delta, engineSnapshot(prefs))?.let { prefs[SpotifyStreamEngineKey] = it.name }
    }

    private fun engineSnapshot(prefs: Preferences): SpotifyProviderSnapshot =
        SpotifyProviderSnapshot(storedEngine = SpotifyStreamEngine.fromPreference(prefs[SpotifyStreamEngineKey]))

    private fun productSnapshot(stored: SpotifyStreamEngine, boundProduct: String?): SpotifyProviderSnapshot =
        SpotifyProviderSnapshot(storedEngine = stored,
            catalogProduct = if (stored == SpotifyStreamEngine.LIBRESPOT) SpotifyProduct.of(boundProduct) else SpotifyProduct.UNKNOWN,
            alternateProduct = if (stored == SpotifyStreamEngine.UNOFFICIAL) SpotifyProduct.of(boundProduct) else SpotifyProduct.UNKNOWN)

    fun effectiveEngine(dataStore: DataStore<Preferences>): SpotifyStreamEngine {
        val stored = SpotifyStreamEngine.fromPreference(dataStore[SpotifyStreamEngineKey])
        val product = if (stored == SpotifyStreamEngine.LIBRESPOT) dataStore[SpotifyProductKey] else dataStore[SpotifyStreamProductKey]
        return SpotifyProviderPolicy.effectiveEngine(productSnapshot(stored, product))
    }

    fun playbackAvailable(prefs: Preferences): Boolean {
        val stored = SpotifyStreamEngine.fromPreference(prefs[SpotifyStreamEngineKey])
        val product = if (stored == SpotifyStreamEngine.LIBRESPOT) prefs[SpotifyProductKey] else prefs[SpotifyStreamProductKey]
        return SpotifyProviderPolicy.playbackAvailable(productSnapshot(stored, product).copy(
            alternateEnabled = prefs[SpotifyAlternateEnabledKey],
            librespotEnabled = prefs[SpotifyLibrespotEnabledKey],
            alternateSessionPresent = !prefs[SpotifyStreamSpDcKey].isNullOrBlank(),
            librespotSessionPresent = !prefs[SpotifyLibrespotRefreshTokenKey].isNullOrBlank(),
            playbackSourceEnabled = prefs[SpotifyStreamEnabledKey] == true,
        ))
    }

    /** Preserve the host's synchronous preference reads, without reading catalog cookies or bearer tokens. */
    fun playbackAvailable(dataStore: DataStore<Preferences>): Boolean {
        val stored = SpotifyStreamEngine.fromPreference(dataStore[SpotifyStreamEngineKey])
        val product = if (stored == SpotifyStreamEngine.LIBRESPOT) dataStore[SpotifyProductKey] else dataStore[SpotifyStreamProductKey]
        return SpotifyProviderPolicy.playbackAvailable(productSnapshot(stored, product).copy(
            alternateEnabled = dataStore[SpotifyAlternateEnabledKey],
            librespotEnabled = dataStore[SpotifyLibrespotEnabledKey],
            alternateSessionPresent = !dataStore[SpotifyStreamSpDcKey].isNullOrBlank(),
            librespotSessionPresent = !dataStore[SpotifyLibrespotRefreshTokenKey].isNullOrBlank(),
            playbackSourceEnabled = dataStore[SpotifyStreamEnabledKey] == true,
        ))
    }
}
