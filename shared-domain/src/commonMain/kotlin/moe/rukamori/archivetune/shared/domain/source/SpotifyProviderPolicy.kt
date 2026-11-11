package moe.rukamori.archivetune.shared.domain.source

/** Stable compatibility IDs for the three compiled Spotify choices shown by the old registry. */
object SpotifyProviderIds {
    const val CATALOG = "app.natune.native.spotify.metadata"
    const val ALTERNATE = "app.natune.native.spotify.alternate"
    const val LIBRESPOT = "app.natune.native.spotify.librespot"
}

enum class SpotifyProvider(val id: String) {
    CATALOG(SpotifyProviderIds.CATALOG),
    ALTERNATE(SpotifyProviderIds.ALTERNATE),
    LIBRESPOT(SpotifyProviderIds.LIBRESPOT),
    ;

    companion object {
        fun fromId(id: String): SpotifyProvider? = entries.firstOrNull { it.id == id }
    }
}

/** Presence and observed products only; credentials stay with their existing account owners. */
data class SpotifyProviderSnapshot(
    val catalogEnabled: Boolean? = null,
    val alternateEnabled: Boolean? = null,
    val librespotEnabled: Boolean? = null,
    val catalogCookiePresent: Boolean = false,
    val catalogTokenPresent: Boolean = false,
    val alternateSessionPresent: Boolean = false,
    val librespotSessionPresent: Boolean = false,
    val playbackSourceEnabled: Boolean = false,
    val storedEngine: SpotifyStreamEngine = SpotifyStreamEngine.Default,
    val catalogProduct: SpotifyProduct = SpotifyProduct.UNKNOWN,
    val alternateProduct: SpotifyProduct = SpotifyProduct.UNKNOWN,
)

/** Native settings policy, independent of installation, manifests and platform preference types. */
object SpotifyProviderPolicy {
    fun legacyEnabled(provider: SpotifyProvider, snapshot: SpotifyProviderSnapshot): Boolean = when (provider) {
        SpotifyProvider.CATALOG -> snapshot.catalogCookiePresent || snapshot.catalogTokenPresent
        SpotifyProvider.ALTERNATE -> snapshot.alternateSessionPresent ||
            (snapshot.playbackSourceEnabled && snapshot.storedEngine == SpotifyStreamEngine.UNOFFICIAL)
        SpotifyProvider.LIBRESPOT -> snapshot.librespotSessionPresent ||
            (snapshot.playbackSourceEnabled && snapshot.storedEngine == SpotifyStreamEngine.LIBRESPOT)
    }

    fun enabled(provider: SpotifyProvider, snapshot: SpotifyProviderSnapshot): Boolean = when (provider) {
        SpotifyProvider.CATALOG -> snapshot.catalogEnabled
        SpotifyProvider.ALTERNATE -> snapshot.alternateEnabled
        SpotifyProvider.LIBRESPOT -> snapshot.librespotEnabled
    } ?: legacyEnabled(provider, snapshot)

    /** Infer all absent choices from the same old snapshot; later account changes never reset them. */
    fun migrate(snapshot: SpotifyProviderSnapshot): SpotifyProviderSnapshot = snapshot.copy(
        catalogEnabled = enabled(SpotifyProvider.CATALOG, snapshot),
        alternateEnabled = enabled(SpotifyProvider.ALTERNATE, snapshot),
        librespotEnabled = enabled(SpotifyProvider.LIBRESPOT, snapshot),
    )

    fun priority(provider: SpotifyProvider, snapshot: SpotifyProviderSnapshot): Int {
        if (provider == SpotifyProvider.CATALOG) return 1
        val officialFirst = snapshot.storedEngine == SpotifyStreamEngine.LIBRESPOT
        return if ((provider == SpotifyProvider.LIBRESPOT) == officialFirst) 1 else 2
    }

    /** The old two-row move changes only the stored engine, never any enablement or credential. */
    fun movedEngine(provider: SpotifyProvider, delta: Int, snapshot: SpotifyProviderSnapshot): SpotifyStreamEngine? {
        if (provider == SpotifyProvider.CATALOG || delta !in listOf(-1, 1)) return null
        if (priority(provider, snapshot) + delta !in 1..2) return null
        val officialFirst = (provider == SpotifyProvider.LIBRESPOT) == (delta < 0)
        return if (officialFirst) SpotifyStreamEngine.LIBRESPOT else SpotifyStreamEngine.UNOFFICIAL
    }

    fun effectiveEngine(snapshot: SpotifyProviderSnapshot): SpotifyStreamEngine {
        val product = when (snapshot.storedEngine.streamAccountBinding()) {
            SpotifyStreamAccountBinding.DEDICATED -> snapshot.alternateProduct
            SpotifyStreamAccountBinding.LIBRARY -> snapshot.catalogProduct
        }
        return SpotifyPremiumPolicy.effectiveEngine(snapshot.storedEngine, product)
    }

    /** A settings/session gate only; native bindings and same-account checks still guard token admission. */
    fun playbackAvailable(snapshot: SpotifyProviderSnapshot): Boolean = when (effectiveEngine(snapshot)) {
        SpotifyStreamEngine.UNOFFICIAL -> snapshot.alternateSessionPresent && enabled(SpotifyProvider.ALTERNATE, snapshot)
        SpotifyStreamEngine.LIBRESPOT -> snapshot.librespotSessionPresent && enabled(SpotifyProvider.LIBRESPOT, snapshot)
    }
}
