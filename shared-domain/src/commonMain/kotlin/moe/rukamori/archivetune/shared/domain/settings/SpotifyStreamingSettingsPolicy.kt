package moe.rukamori.archivetune.shared.domain.settings

import moe.rukamori.archivetune.shared.domain.source.SpotifyAccountRole
import moe.rukamori.archivetune.shared.domain.source.SpotifyPremiumPolicy
import moe.rukamori.archivetune.shared.domain.source.SpotifyProduct
import moe.rukamori.archivetune.shared.domain.source.SpotifyStreamAccountBinding
import moe.rukamori.archivetune.shared.domain.source.SpotifyStreamEngine
import moe.rukamori.archivetune.shared.domain.source.streamAccountBinding

/** The two streaming resolve pipelines. Stored by enum name; an unknown stored value reads as [Default]. */
enum class SpotifyStreamResolveMethod {
    /** Web-player pipeline: Connect device, manifest and Widevine-protected AAC 128. */
    STOPIFY,

    /** Desktop pipeline: on-device decryption of Ogg Vorbis or FLAC, falling back to [STOPIFY] when it cannot resolve. */
    ECHO,
    ;

    companion object {
        val Default: SpotifyStreamResolveMethod = STOPIFY

        fun fromPreference(value: String?): SpotifyStreamResolveMethod =
            entries.firstOrNull { it.name == value } ?: Default
    }
}

/**
 * The streaming account counts as signed in while a session cookie is stored. The cookie is checked for presence
 * only and is never returned to the UI.
 */
fun PreferenceSnapshot.spotifyStreamingSignedIn(): Boolean = !this[SpotifyStreamSpDcKey].isNullOrBlank()

/** The switch reads on only while a streaming session exists, so a stale stored flag cannot enable a signed-out account. */
fun PreferenceSnapshot.spotifyStreamingEnabledForSettings(): Boolean =
    this[SpotifyStreamEnabledKey, false] && spotifyStreamSessionPresent(spotifyStreamEngine())

fun PreferenceSnapshot.spotifyStreamResolveMethod(): SpotifyStreamResolveMethod =
    SpotifyStreamResolveMethod.fromPreference(this[SpotifyStreamResolveMethodKey])

/** The library (catalog) account counts as signed in while its session cookie is stored. */
fun PreferenceSnapshot.spotifyLibrarySignedIn(): Boolean = !this[SpotifySpDcKey].isNullOrBlank()

/** The stored streaming engine choice; an absent or unknown value reads as [SpotifyStreamEngine.Default]. */
fun PreferenceSnapshot.spotifyStreamEngine(): SpotifyStreamEngine =
    SpotifyStreamEngine.fromPreference(this[SpotifyStreamEngineKey])

/** The product last observed for the library (catalog) account's sign-in. */
fun PreferenceSnapshot.spotifyLibraryProduct(): SpotifyProduct = SpotifyProduct.of(this[SpotifyProductKey])

/** The product last observed for the dedicated streaming account's sign-in. */
fun PreferenceSnapshot.spotifyStreamProduct(): SpotifyProduct = SpotifyProduct.of(this[SpotifyStreamProductKey])

/** The product of whichever account the [SpotifyAccountRole.STREAM] role binds to under [engine]. */
fun PreferenceSnapshot.spotifyStreamAccountProduct(engine: SpotifyStreamEngine): SpotifyProduct =
    when (engine.streamAccountBinding()) {
        SpotifyStreamAccountBinding.DEDICATED -> spotifyStreamProduct()
        SpotifyStreamAccountBinding.LIBRARY -> spotifyLibraryProduct()
    }

/**
 * The engine that may actually run. A stored Librespot choice degrades to Unofficial while the library
 * account is not observed Premium, so the source always has a defined engine without blocking playback.
 */
fun PreferenceSnapshot.spotifyStreamEngineEffective(): SpotifyStreamEngine {
    val stored = spotifyStreamEngine()
    return SpotifyPremiumPolicy.effectiveEngine(stored, spotifyStreamAccountProduct(stored))
}

/**
 * A session exists for [engine]'s account binding: the dedicated streaming sign-in for Unofficial, the
 * library sign-in for Librespot. The stream role can only ever play as an account that signed in.
 */
fun PreferenceSnapshot.spotifyStreamSessionPresent(engine: SpotifyStreamEngine): Boolean =
    streamSessionPresent({ this[it] }, engine)

private fun streamSessionPresent(
    read: (StringPreferenceKey) -> String?,
    engine: SpotifyStreamEngine,
): Boolean =
    when (engine.streamAccountBinding()) {
        SpotifyStreamAccountBinding.DEDICATED -> !read(SpotifyStreamSpDcKey).isNullOrBlank()
        SpotifyStreamAccountBinding.LIBRARY -> !read(SpotifySpDcKey).isNullOrBlank()
    }

/**
 * Turning streaming on needs a session for the stored engine's account binding, so Librespot cannot be
 * switched on for a library account that is signed out. Turning it off is always allowed.
 */
fun PreferenceEditor.setSpotifyStreamingEnabled(enabled: Boolean) {
    val engine = SpotifyStreamEngine.fromPreference(this[SpotifyStreamEngineKey])
    if (enabled && !streamSessionPresent({ this[it] }, engine)) return
    this[SpotifyStreamEnabledKey] = enabled
}

fun PreferenceEditor.setSpotifyStreamEngine(engine: SpotifyStreamEngine) {
    this[SpotifyStreamEngineKey] = engine.name
}

/**
 * Records a fresh "/v1/me product" read for the library account together with when it was checked, so
 * [SpotifyPremiumPolicy.recheckDue] can decide the next read on the refresh path. A null field clears the
 * stored product but still marks the check time.
 */
fun PreferenceEditor.recordSpotifyLibraryProduct(productField: String?, checkedAtMs: Long) {
    this[SpotifyProductKey] = productField?.takeIf { it.isNotBlank() }
    this[SpotifyProductCheckedAtKey] = checkedAtMs
}

/** The [recordSpotifyLibraryProduct] twin for the dedicated streaming account. */
fun PreferenceEditor.recordSpotifyStreamProduct(productField: String?, checkedAtMs: Long) {
    this[SpotifyStreamProductKey] = productField?.takeIf { it.isNotBlank() }
    this[SpotifyStreamProductCheckedAtKey] = checkedAtMs
}

fun PreferenceEditor.setSpotifyStreamResolveMethod(method: SpotifyStreamResolveMethod) {
    this[SpotifyStreamResolveMethodKey] = method.name
}

/**
 * Forgets the streaming session in one edit and turns streaming off, as the baseline sign-out does. The device id is
 * kept so the account does not register a new device on every play.
 */
fun PreferenceEditor.clearSpotifyStreamingSession() {
    this[SpotifyStreamSpDcKey] = null
    this[SpotifyStreamSpKeyKey] = null
    this[SpotifyStreamAccessTokenKey] = null
    this[SpotifyStreamAccessTokenExpiresAtKey] = null
    this[SpotifyStreamUserIdKey] = null
    this[SpotifyStreamAccountNameKey] = null
    this[SpotifyStreamProductKey] = null
    this[SpotifyStreamProductCheckedAtKey] = null
    this[SpotifyStreamEnabledKey] = false
}
