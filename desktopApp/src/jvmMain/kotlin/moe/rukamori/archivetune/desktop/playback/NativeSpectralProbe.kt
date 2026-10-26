/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.desktop.playback

import kotlinx.coroutines.CancellationException

/**
 * One source-rate probe handle, which serves one read at a time.
 *
 * [cancel] is the only call that may arrive from another thread while a read is in flight. [close] frees the native
 * handle, so it must come after the read has returned. [DesktopSpectralAuthenticity] keeps that order.
 */
internal class NativeSpectralProbe private constructor(private val handle: Long) : AutoCloseable {
    private val lock = Any()
    private var closed = false

    /**
     * Makes the read in progress end as cancelled, and every later read on this handle too, because the native
     * cancellation is sticky. Calls after [close] do nothing.
     */
    fun cancel() {
        synchronized(lock) {
            if (!closed) SpectralProbeNative.cancel(handle)
        }
    }

    /**
     * Decodes up to [maxFrames] frames of [source] into [out]. A cancelled read throws [CancellationException], which
     * is not a failure of the source. A failed read throws [IllegalStateException] with the native message.
     */
    fun read(source: String, startUs: Long, maxFrames: Int, out: FloatArray): ProbeWindow {
        check(!closed) { "the spectral probe is closed" }
        val info = LongArray(INFO_FIELDS)
        val message = SpectralProbeNative.read(handle, source, startUs, maxFrames, out, info)
        return when (info[STATUS]) {
            STATUS_OK -> ProbeWindow(
                frames = info[FRAMES].toInt(),
                channels = info[CHANNELS].toInt(),
                pcmSampleRateHz = info[PCM_SAMPLE_RATE].toInt(),
                advertisedSampleRateHz = info[ADVERTISED_SAMPLE_RATE].toInt(),
                endOfSource = info[END_OF_SOURCE] != 0L,
                codecName = SpectralProbeNative.codecName(handle),
            )
            STATUS_CANCELLED -> throw CancellationException("the spectral probe was cancelled")
            STATUS_INVALID -> throw IllegalArgumentException(message ?: "the spectral probe request is invalid")
            else -> throw IllegalStateException(message ?: "the spectral probe failed")
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            SpectralProbeNative.destroy(handle)
        }
    }

    companion object {
        /** Loads the engine library if needed, then opens a handle. Throws when the library is unavailable. */
        fun create(): NativeSpectralProbe {
            PlaybackEngineLibrary.ensureLoaded()
            // A library built before the probe existed still loads, but it lacks these entry points. That is an
            // unavailable check, so it becomes the same state error as a missing library instead of an Error.
            val handle = try {
                SpectralProbeNative.create()
            } catch (missing: UnsatisfiedLinkError) {
                throw IllegalStateException("The desktop playback engine has no spectral probe entry points.", missing)
            }
            check(handle != 0L) { "the spectral probe could not start" }
            return NativeSpectralProbe(handle)
        }

        private const val INFO_FIELDS = 6
        private const val STATUS = 0
        private const val FRAMES = 1
        private const val CHANNELS = 2
        private const val PCM_SAMPLE_RATE = 3
        private const val ADVERTISED_SAMPLE_RATE = 4
        private const val END_OF_SOURCE = 5
        private const val STATUS_OK = 0L
        private const val STATUS_CANCELLED = 1L
        private const val STATUS_INVALID = 3L
    }
}

/**
 * What one successful read reports. The samples themselves stay in the caller's array; [frames] is how many of
 * them are valid, and it can be fewer than requested when the source is short or the channel count is high.
 */
internal data class ProbeWindow(
    val frames: Int,
    val channels: Int,
    val pcmSampleRateHz: Int,
    val advertisedSampleRateHz: Int,
    val endOfSource: Boolean,
    val codecName: String,
)
