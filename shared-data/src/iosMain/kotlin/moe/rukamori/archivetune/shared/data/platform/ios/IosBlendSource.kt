package moe.rukamori.archivetune.shared.data.platform.ios

import cnames.structs.at_audio_output
import kotlin.time.Clock
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import moe.rukamori.archivetune.nativeaudio.*
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceAccount
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceLease
import moe.rukamori.archivetune.shared.data.bytesource.StreamOrigin
import moe.rukamori.archivetune.shared.data.stream.NativePlaintext
import moe.rukamori.archivetune.shared.data.stream.hls.HlsSegmentedSource
import moe.rukamori.archivetune.shared.data.stream.hls.isHlsPlaylistUrl
import moe.rukamori.archivetune.shared.data.stream.nativePlaintext
import moe.rukamori.archivetune.shared.domain.platform.ANONYMOUS_ACCOUNT_SCOPE
import moe.rukamori.archivetune.shared.domain.playback.PlaybackLocator
import moe.rukamori.archivetune.shared.domain.playback.PlaybackRequest

/** A second decoder, with exactly the same admitted leases as an ordinary load. It never owns another audio device. */
@OptIn(ExperimentalForeignApi::class)
internal class IosBlendSource(
    val output: CPointer<at_audio_output>,
    val request: PlaybackRequest,
    val generation: Long,
    val transport: IosCiphertextOpener?,
    /** Whether [request] can be re-issued through the port's load path on a stall: false once an admitted lease fed it. */
    val repreparable: Boolean,
) {
    fun release() {
        at_audio_stop(output)
        at_audio_destroy(output)
        transport?.close()
    }

    companion object {
        /** Consumes the prepared transport or Telegram source on both success and refusal. */
        fun open(
            request: PlaybackRequest,
            outcome: IosNativeAudioRoute.Outcome?,
            telegramSource: IosLeaseSource?,
            speed: Float,
        ): IosBlendSource? {
            var transport = (outcome as? IosNativeAudioRoute.Outcome.Ready)?.transport
            val native = memScoped {
                at_audio_create(allocArray<ByteVar>(256), 256uL)
            }
            if (native == null) {
                transport?.close()
                telegramSource?.close()
                return null
            }
            var accepted = false
            try {
                // A paused decoder prebuffers without opening its source-node graph or taking playback intent away
                // from the outgoing song. The blend graph starts its manual consumer only after this handoff.
                if (at_audio_begin_manual_output(native) == 0) return null
                at_audio_speed(native, speed)
                val generation = when {
                    outcome is IosNativeAudioRoute.Outcome.Ready -> {
                        val handle = outcome.handle
                        val plaintext = handle.nativePlaintext()
                        if (request.locator != PlaybackLocator.PreparedPlaintext(handle) || plaintext == null ||
                            handle.expiresAtEpochMs?.let { it <= Clock.System.now().toEpochMilliseconds() } == true
                        ) return null
                        IosStreamLease.loadPlaintext(native, IosPlaintextSource(outcome.transport, plaintext), 0uL, 0)
                    }
                    outcome is IosNativeAudioRoute.Outcome.Streamed -> {
                        if (request.locator != outcome.remote.locator) return null
                        IosStreamLease.loadAdmitted(native, outcome.remote, 0uL, 0)
                    }
                    outcome != null -> return null
                    request.locator is PlaybackLocator.TelegramFile ->
                        telegramSource?.let { IosStreamLease.loadPlaintext(native, it, 0uL, 0) } ?: return null
                    else -> when (val locator = request.locator) {
                        is PlaybackLocator.LocalFile -> {
                            if (!locator.path.startsWith("/") || '\u0000' in locator.path) return null
                            at_audio_load(native, locator.path, 0uL, 0)
                        }
                        is PlaybackLocator.Remote -> {
                            if (!isWebUrl(locator.url)) return null
                            when {
                                // A lease-bound playlist cannot expand through the engine's empty whitelist,
                                // so the host reads its segments itself, under the same lease policy as Load.
                                isHlsPlaylistUrl(locator.url) &&
                                    (!locator.headers.isEmpty() || locator.expiresAtEpochMs != null) -> {
                                    val segmented = segmentedPlaintext(locator) ?: return null
                                    transport = segmented.first
                                    IosStreamLease.loadPlaintext(
                                        native, IosPlaintextSource(segmented.first, segmented.second), 0uL, 0)
                                }
                                locator.headers.isEmpty() -> at_audio_load(native, locator.url, 0uL, 0)
                                else -> IosStreamLease.loadRemote(native, locator, 0uL, 0)
                            }
                        }
                        is PlaybackLocator.DeezerEncrypted -> {
                            if (!isWebUrl(locator.url)) return null
                            IosStreamLease.loadDeezer(native, locator, 0uL, 0)
                        }
                        is PlaybackLocator.PreparedPlaintext, is PlaybackLocator.TelegramFile -> return null
                    }
                }
                if (generation == 0uL) return null
                accepted = true
                // Only a locator that went straight to the engine can be re-prepared: an admitted outcome's
                // lease was consumed by this open, and its locator alone skips the account admission. A
                // Telegram source stays repreparable — a stall recovery re-runs the whole resolver path.
                val repreparable = outcome == null
                return IosBlendSource(native, request, generation.toLong(), transport, repreparable)
            } finally {
                if (!accepted) {
                    at_audio_destroy(native)
                    transport?.close()
                    telegramSource?.close()
                }
            }
        }

        /**
         * The anonymous segmented view of one lease-bound HLS remote, paired with the transport the caller owns. The
         * playlist document, each segment and each key are read under credentials anchored at the playlist origin, so
         * the locator's headers never reach a segment host that lives elsewhere. Null when the URL is not HTTP(S).
         */
        private fun segmentedPlaintext(
            locator: PlaybackLocator.Remote,
        ): Pair<IosCiphertextOpener, NativePlaintext>? {
            val origin = StreamOrigin.parse(locator.url) ?: return null
            val account = ByteSourceAccount(provider = "playback", accountId = null)
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
                            // The query string carries a per-request signature, so it never becomes a cache key part.
                            cacheKey = locator.url.substringBefore('?'),
                            expiresAtEpochMs = locator.expiresAtEpochMs,
                            lengthBytes = null,
                        ),
                    playlistUrl = locator.url,
                    playlistText = null,
                    accountScope = ANONYMOUS_ACCOUNT_SCOPE,
                    opener = transport,
                )
            return transport to
                NativePlaintext(
                    source = view,
                    mimeType = "application/octet-stream",
                    codec = "",
                    length = null,
                    expiresAtEpochMs = locator.expiresAtEpochMs,
                )
        }
    }
}
