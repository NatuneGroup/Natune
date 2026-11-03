@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.platform.ios

import cnames.structs.at_audio_output
import kotlinx.cinterop.CPointer
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceAccount
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceLease
import moe.rukamori.archivetune.shared.data.bytesource.StreamOrigin
import moe.rukamori.archivetune.shared.data.stream.NativePlaintext
import moe.rukamori.archivetune.shared.data.stream.hls.HlsSegmentedSource
import moe.rukamori.archivetune.shared.domain.platform.ANONYMOUS_ACCOUNT_SCOPE
import moe.rukamori.archivetune.shared.domain.playback.PlaybackLocator

/** The lease account of an anonymous segmented stream, matching the anonymous header-bearing lease. */
private const val SEGMENTED_PROVIDER = "playback"

/** The MIME a segmented view reports before its first payload bytes are classified. */
private const val SEGMENTED_UNKNOWN_MIME = "application/octet-stream"

/**
 * Loads an anonymous segmented lease for a header-bearing or expiry-carrying HLS remote. The engine's lease cannot
 * fetch child resources, so the host expands the playlist into a segmented plaintext view itself: the playlist
 * document, each segment and each AES-128 key are read through [IosCiphertextOpener], which anchors credentials at
 * the playlist origin — the locator's headers never reach a segment host that lives elsewhere. The playlist is
 * fetched lazily on the producer thread, so this runs on the command loop without blocking it.
 *
 * Returns the engine generation, 0 when the engine refused the lease. [onAccepted] receives the transport the
 * engine now reads through, so the port can release it after the engine stops.
 */
internal fun loadIosSegmentedHls(
    native: CPointer<at_audio_output>,
    locator: PlaybackLocator.Remote,
    startUs: ULong,
    play: Int,
    onAccepted: (IosCiphertextOpener) -> Unit,
): ULong {
    val origin = StreamOrigin.parse(locator.url) ?: return 0uL
    val account = ByteSourceAccount(provider = SEGMENTED_PROVIDER, accountId = null)
    val transport =
        IosCiphertextOpener(
            boundScope = ANONYMOUS_ACCOUNT_SCOPE,
            account = account,
            recheckFor = null,
            headers = locator.headers,
            credentialAnchor = origin,
        )
    val view =
        HlsSegmentedSource(
            lease =
                ByteSourceLease(
                    account = account,
                    primaryOrigin = origin,
                    permittedOrigins = setOf(origin),
                    // The query string carries a per-request signature, so it never becomes part of the cache key.
                    cacheKey = locator.url.substringBefore('?'),
                    expiresAtEpochMs = locator.expiresAtEpochMs,
                    lengthBytes = null,
                ),
            playlistUrl = locator.url,
            playlistText = null,
            accountScope = ANONYMOUS_ACCOUNT_SCOPE,
            opener = transport,
        )
    val plaintext =
        NativePlaintext(
            source = view,
            mimeType = SEGMENTED_UNKNOWN_MIME,
            codec = "",
            length = null,
            expiresAtEpochMs = locator.expiresAtEpochMs,
        )
    val generation =
        IosStreamLease.loadPlaintext(native, IosPlaintextSource(transport, plaintext), startUs, play)
    if (generation == 0uL) {
        // An accepted lease is closed by the engine itself, and closing again is harmless.
        transport.close()
    } else {
        onAccepted(transport)
    }
    return generation
}
