/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.desktop.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.shared.domain.dsp.spectral.SpectralAssessment
import moe.rukamori.archivetune.shared.domain.dsp.spectral.SpectralAuthenticityAnalyzer
import moe.rukamori.archivetune.shared.domain.dsp.spectral.SpectralVerdict
import moe.rukamori.archivetune.shared.domain.playback.PlaybackLocator

/**
 * The spectral authenticity evidence for one source. [assessment] is UNKNOWN whenever the check could not run or
 * could not be conclusive. It is never a guess from the quality tier. The other fields say what was measured, so
 * a cache can bind the result to this source and to the rate it was decoded at.
 */
data class DesktopSpectralEvidence(
    val locator: PlaybackLocator,
    val assessment: SpectralAssessment,
    val pcmSampleRateHz: Int?,
    val channelCount: Int?,
    val codecName: String?,
    val claimedLossless: Boolean,
)

/**
 * Bounded spectral authenticity check over the source's own decoded PCM.
 *
 * The check opens a private probe on an IO thread, reads at most [SpectralAuthenticityAnalyzer.MAX_ANALYSIS_FRAMES]
 * frames from the start position, and analyses them on the default dispatcher. It never touches a playback deck, the
 * mixer or the playback executor, so it cannot change the audible output or the bit-perfect state. It also never
 * analyses engine output: the engine resamples every deck to the device rate, which would misreport the source.
 *
 * Only a codec that is lossless by construction may claim lossless input. The claim comes from the codec the decoder
 * reports, never from a quality tier, because a lossy codec's artefacts would look like provenance.
 */
class DesktopSpectralAuthenticity internal constructor(
    private val timeoutMs: Long,
) {
    constructor() : this(DEFAULT_TIMEOUT_MS)

    /**
     * Returns the evidence for [locator]. A missing native library, an undecodable source or a timeout produces
     * UNKNOWN, not an exception. A cancelled caller is always rethrown. A negative [startPositionMs] is a caller
     * bug and throws [IllegalArgumentException].
     */
    suspend fun assess(locator: PlaybackLocator, startPositionMs: Long = 0L): DesktopSpectralEvidence {
        require(startPositionMs >= 0L) { "startPositionMs must not be negative, was $startPositionMs" }
        val source = engineSourceOf(locator) ?: return unknown(locator)
        val decoded: DecodedWindow? = try {
            withTimeoutOrNull(timeoutMs) { decode(source, startPositionMs * MICROS_PER_MILLI) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalStateException) {
            // The engine library is missing or the source cannot be decoded. Either way nothing was measured.
            null
        } catch (_: IllegalArgumentException) {
            // The probe refused the request shape. The source still has no measurement, so it stays UNKNOWN.
            null
        }
        if (decoded == null) return unknown(locator)
        return analyze(locator, decoded)
    }

    /**
     * Reads one window on an IO thread. A cancelled caller asks the native read to stop, then waits for it to return
     * before the probe is closed, because the native handle must not be freed while a read still uses it.
     */
    private suspend fun decode(source: String, startUs: Long): DecodedWindow {
        val probe = NativeSpectralProbe.create()
        try {
            return coroutineScope {
                val samples = FloatArray(WINDOW_SAMPLES)
                val reading = async(Dispatchers.IO) { probe.read(source, startUs, MAX_FRAMES, samples) }
                val window = try {
                    reading.await()
                } catch (cancelled: CancellationException) {
                    probe.cancel()
                    withContext(NonCancellable) { reading.join() }
                    throw cancelled
                }
                DecodedWindow(samples, window)
            }
        } finally {
            probe.close()
        }
    }

    private suspend fun analyze(locator: PlaybackLocator, decoded: DecodedWindow): DesktopSpectralEvidence {
        val window = decoded.window
        if (window.frames == 0 || window.channels !in 1..MAX_CHANNELS) return unknown(locator)

        val claimedLossless = window.codecName in LOSSLESS_CODECS
        val samples = decoded.samples.copyOf(window.frames * window.channels)
        val caller = currentCoroutineContext()[Job]
        val assessment = withContext(Dispatchers.Default) {
            SpectralAuthenticityAnalyzer.analyzeInterleavedPcm(
                samples = samples,
                pcmSampleRateHz = window.pcmSampleRateHz,
                channelCount = window.channels,
                advertisedSampleRateHz = window.advertisedSampleRateHz.takeIf { it > 0 },
                claimedLossless = claimedLossless,
                checkActive = { caller?.ensureActive() },
            )
        }
        return DesktopSpectralEvidence(
            locator = locator,
            assessment = assessment,
            pcmSampleRateHz = window.pcmSampleRateHz,
            channelCount = window.channels,
            codecName = window.codecName.ifEmpty { null },
            claimedLossless = claimedLossless,
        )
    }

    private fun unknown(locator: PlaybackLocator): DesktopSpectralEvidence = DesktopSpectralEvidence(
        locator = locator,
        assessment = SpectralAssessment(verdict = SpectralVerdict.UNKNOWN, confidence = 0.0),
        pcmSampleRateHz = null,
        channelCount = null,
        codecName = null,
        claimedLossless = false,
    )

    private class DecodedWindow(val samples: FloatArray, val window: ProbeWindow)

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
        const val MICROS_PER_MILLI = 1_000L
        const val STEREO = 2
        const val MAX_CHANNELS = 8
        const val MAX_FRAMES = SpectralAuthenticityAnalyzer.MAX_ANALYSIS_FRAMES

        /**
         * Sized for stereo, the common case. A wider source gets fewer frames from the same buffer, but never fewer
         * than [SpectralAuthenticityAnalyzer.MIN_ANALYSIS_FRAMES] for up to eight channels, so it is still analysed.
         */
        const val WINDOW_SAMPLES = MAX_FRAMES * STEREO

        /**
         * Codecs that are lossless by construction. Anything else, including lossy codecs in a lossless container,
         * stays out, so the analyzer never treats a codec artefact as provenance.
         */
        val LOSSLESS_CODECS: Set<String> = setOf(
            "flac", "alac", "ape", "tta", "tak", "wmalossless",
            "pcm_s8", "pcm_u8", "pcm_s16le", "pcm_s16be", "pcm_s24le", "pcm_s24be", "pcm_s32le", "pcm_s32be",
            "pcm_f32le", "pcm_f32be", "pcm_f64le", "pcm_f64be",
        )
    }
}
