/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.settings

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
    this[SpotifyStreamEnabledKey, false] && spotifyStreamingSignedIn()

fun PreferenceSnapshot.spotifyStreamResolveMethod(): SpotifyStreamResolveMethod =
    SpotifyStreamResolveMethod.fromPreference(this[SpotifyStreamResolveMethodKey])

/** Turning streaming on needs a stored session. Turning it off is always allowed, as in the baseline toggle gate. */
fun PreferenceEditor.setSpotifyStreamingEnabled(enabled: Boolean) {
    if (enabled && this[SpotifyStreamSpDcKey].isNullOrBlank()) return
    this[SpotifyStreamEnabledKey] = enabled
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
    this[SpotifyStreamEnabledKey] = false
}
