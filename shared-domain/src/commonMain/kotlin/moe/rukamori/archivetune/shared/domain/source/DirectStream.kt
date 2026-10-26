/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.domain.source

import moe.rukamori.archivetune.shared.domain.playback.PlaybackHeaders
import moe.rukamori.archivetune.shared.domain.playback.PlaybackLocator

/**
 * A resolved direct-playable stream from any audio source. Every provider normalizes its result into this shape, so the
 * playback layer treats them the same way.
 */
data class DirectStream(
    val uri: String,
    val mimeType: String,
    val codecs: String,
    val contentLength: Long?,
    /** Human-readable label for logging and UI, for example "Tidal (account) HI_RES". */
    val label: String,
    val source: AudioSourceType,
    /**
     * The title of the track the provider matched, used by the [TitleMatch] safety gate. A null value is rejected
     * unless [trustedDirectId] is true.
     */
    val matchedTitle: String? = null,
    val matchedArtist: String? = null,
    val matchedAlbum: String? = null,
    val matchedDurationMs: Long? = null,
    /** True only when the provider resolved a catalog id that is already authoritative. */
    val trustedDirectId: Boolean = false,
    /**
     * What the media-info Details tab shows. The label alone cannot say the rate: Qobuz serves 44.1 to 192 kHz under one
     * HI_RES label. Null means the provider did not report it, so the consumer falls back to a tier guess instead of
     * inventing precision.
     */
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    /**
     * Headers every fetch of [uri] must carry. Empty for the ordinary header-free progressive case. They travel into the
     * engine locator so an authenticated stream is never fetched bare.
     */
    val headers: PlaybackHeaders = PlaybackHeaders.NONE,
    /**
     * The account this stream was resolved under, named by the account scope that the store maps. Null is the
     * genuinely anonymous case. A host admits a personal scope before it reads that account's cookies, so a personal
     * stream always states its scope, and a missing scope is never inferred.
     */
    val accountScope: String? = null,
) {
    init {
        require(accountScope == null || accountScope.isNotBlank()) { "a stream account scope must not be blank" }
    }
}

/** The ordinary locator for this stream, with its headers attached. */
fun DirectStream.toPlaybackLocator(): PlaybackLocator.Remote = PlaybackLocator.Remote(uri, headers)

/**
 * Uncompressed PCM bitrate in bits per second, or null when either input is unknown or not positive.
 *
 * For FLAC this is the pre-compression ceiling, not the true average rate. It is the figure streaming services quote for
 * a tier, so it matches what users see elsewhere. The default assumes stereo, which holds for almost the whole catalog.
 */
fun DirectStream.pcmBitrateOrNull(channels: Int = 2): Int? {
    val rate = sampleRate?.takeIf { it > 0 } ?: return null
    val depth = bitDepth?.takeIf { it > 0 } ?: return null
    return rate * depth * channels
}
