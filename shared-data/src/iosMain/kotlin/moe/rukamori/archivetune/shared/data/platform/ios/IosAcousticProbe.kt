package moe.rukamori.archivetune.shared.data.platform.ios

import cnames.structs.ae_probe
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.addressOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.nativeaudio.ae_probe_cancel
import moe.rukamori.archivetune.nativeaudio.ae_probe_codec_name
import moe.rukamori.archivetune.nativeaudio.ae_probe_create
import moe.rukamori.archivetune.nativeaudio.ae_probe_destroy
import moe.rukamori.archivetune.nativeaudio.ae_probe_info
import moe.rukamori.archivetune.nativeaudio.ae_probe_read
import moe.rukamori.archivetune.nativeaudio.ae_probe_status
import moe.rukamori.archivetune.shared.domain.dsp.acoustic.ACOUSTIC_LOSSLESS_CODECS
import moe.rukamori.archivetune.shared.domain.dsp.acoustic.AcousticAuditLimits
import moe.rukamori.archivetune.shared.domain.dsp.acoustic.AcousticPcm
import moe.rukamori.archivetune.shared.domain.dsp.acoustic.AcousticWindow
import moe.rukamori.archivetune.shared.domain.playback.PlaybackLocator

/**
 * The iOS acoustic-audit decoder: the same portable source-rate PCM probe the desktop host uses, compiled
 * into the iOS native core. It owns a private demuxer and decoder, never touches the playback output, and
 * decodes the first audio stream at the rate the source reports. Only a source the engine could open as a
 * plain string is auditable: ciphertext, leases and prepared views stay out, exactly like on desktop.
 */
object IosAcousticProbe {
    /**
     * The bounded PCM window of [locator]'s source, or the refusal reason. The blocking read runs off the
     * caller's dispatcher; a cancelled caller asks the native read to stop, then waits for it to return
     * before the probe is destroyed, because the handle must not be freed while a read still uses it.
     * [requestKey] is stamped onto the window so the audit owner binds the result to the exact request.
     */
    suspend fun window(
        locator: PlaybackLocator,
        startPositionMs: Long = 0L,
        requestKey: String = "",
    ): AcousticWindow {
        if (startPositionMs < 0L) return AcousticWindow.Refused("input_unreadable")
        val source = iosProbeSourceOf(locator) ?: return AcousticWindow.Refused("no_safe_source")
        val samples = FloatArray(ACOUSTIC_WINDOW_SAMPLES)
        val probe = ae_probe_create() ?: return AcousticWindow.Refused("input_unreadable")
        try {
            return coroutineScope {
                val reading = async(Dispatchers.Default) {
                    readInto(probe, source, startPositionMs * MICROS_PER_MILLI, samples)
                }
                val info = try {
                    reading.await()
                } catch (cancelled: CancellationException) {
                    ae_probe_cancel(probe)
                    withContext(NonCancellable) { reading.join() }
                    throw cancelled
                }
                decodedOf(locator, probe, samples, info, startPositionMs, requestKey)
            }
        } finally {
            ae_probe_destroy(probe)
        }
    }

    /** The Kotlin-owned copy of a read's `ae_probe_info`; the C struct dies with the read's scope. */
    private data class ProbeInfo(
        val frames: Int,
        val channels: Int,
        val sampleRate: Int,
        val advertisedSampleRate: Int,
        val endOfSource: Boolean,
    )

    private fun readInto(
        probe: CPointer<ae_probe>,
        source: String,
        startUs: Long,
        samples: FloatArray,
    ): ProbeInfo = memScoped {
        val info = alloc<ae_probe_info>()
        val error = allocArray<ByteVar>(ERROR_LEN)
        val status = samples.usePinned { pinned ->
            ae_probe_read(
                probe, source.cstr, startUs.toULong(), AcousticAuditLimits.MAX_PCM_FRAMES.toUInt(),
                pinned.addressOf(0), samples.size.toULong(), info.ptr,
                error, ERROR_LEN.toULong(),
            )
        }
        // Copy the fields before the scope closes: the alloc'd struct is only valid inside memScoped.
        val infoCopy = ProbeInfo(
            frames = info.frames.toInt(),
            channels = info.channels.toInt(),
            sampleRate = info.sample_rate.toInt(),
            advertisedSampleRate = info.advertised_sample_rate.toInt(),
            endOfSource = info.end_of_source != 0u,
        )
        when (status) {
            ae_probe_status.AE_PROBE_OK -> infoCopy
            ae_probe_status.AE_PROBE_CANCELLED -> throw CancellationException("the acoustic probe was cancelled")
            else -> throw IosProbeFailed()
        }
    }

    /** Wraps a native read failure; the refusal code is the only message the audit ever shows. */
    private class IosProbeFailed : Exception()

    private fun decodedOf(
        locator: PlaybackLocator,
        probe: CPointer<ae_probe>,
        samples: FloatArray,
        info: ProbeInfo,
        startPositionMs: Long,
        requestKey: String,
    ): AcousticWindow {
        if (info.frames <= 0 || info.channels !in 1..8 || info.sampleRate <= 0) {
            return AcousticWindow.Refused("insufficient_pcm")
        }
        val codec = runCatching {
            memScoped {
                val buffer = allocArray<ByteVar>(64)
                val length = ae_probe_codec_name(probe, buffer, 64uL)
                if (length > 0uL) buffer.toKString() else null
            }
        }.getOrNull()
        return AcousticWindow.Decoded(
            pcm = AcousticPcm(
                samples = samples.copyOf(info.frames * info.channels),
                sampleRate = info.sampleRate,
                channelCount = info.channels,
                startUs = startPositionMs * MICROS_PER_MILLI,
                frames = info.frames,
                truncated = !info.endOfSource,
            ),
            sourceLabel = if (locator is PlaybackLocator.LocalFile) "LOCAL_FILE" else "REMOTE_STREAM",
            advertisedSampleRate = info.advertisedSampleRate.takeIf { it > 0 },
            claimedLossless = codec in ACOUSTIC_LOSSLESS_CODECS,
            limitations = buildList {
                if (codec.isNullOrBlank()) add("codec_unknown")
            },
            requestKey = requestKey,
        )
    }

    private const val MICROS_PER_MILLI = 1_000L
    private const val ERROR_LEN = 256

    /** Interleaved samples the audit window is bounded by: the shared PCM budget in samples. */
    private const val ACOUSTIC_WINDOW_SAMPLES = AcousticAuditLimits.MAX_PCM_BYTES / Float.SIZE_BYTES
}

/**
 * The string the probe opens for [locator], or null when the probe cannot open it as a string, mirroring
 * the desktop `engineSourceOf`: an absolute local path or a header-free http(s) URL, nothing leased.
 */
internal fun iosProbeSourceOf(locator: PlaybackLocator): String? = when (locator) {
    is PlaybackLocator.LocalFile -> locator.path.takeIf { it.startsWith("/") && '\u0000' !in it }
    is PlaybackLocator.Remote -> locator.url.takeIf { isWebUrl(it) && locator.headers.isEmpty() }
    is PlaybackLocator.DeezerEncrypted -> null
    is PlaybackLocator.TelegramFile -> null
    is PlaybackLocator.PreparedPlaintext -> null
}
