package moe.rukamori.archivetune.shared.data.platform.ios

import cnames.structs.at_audio_output
import kotlin.time.Clock
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import moe.rukamori.archivetune.nativeaudio.*
import moe.rukamori.archivetune.shared.data.stream.nativePlaintext
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
            val transport = (outcome as? IosNativeAudioRoute.Outcome.Ready)?.transport
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
                            if (locator.headers.isEmpty()) at_audio_load(native, locator.url, 0uL, 0)
                            else IosStreamLease.loadRemote(native, locator, 0uL, 0)
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
    }
}
