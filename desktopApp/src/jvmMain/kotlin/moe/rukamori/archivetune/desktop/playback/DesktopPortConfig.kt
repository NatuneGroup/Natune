package moe.rukamori.archivetune.desktop.playback

import moe.rukamori.archivetune.shared.domain.dsp.bitperfect.BitPerfectRouteFacts
import moe.rukamori.archivetune.shared.domain.dsp.bitperfect.SoftwareStageRequest
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBand
import moe.rukamori.archivetune.shared.domain.playback.PlaybackRequest

/**
 * The occurrence a deck plays. [trackGainDb] and [trackPeak] describe it for normalization, and are null
 * when the caller has not supplied them, which leaves the track at unity gain.
 */
internal data class DeckSession(
    val request: PlaybackRequest,
    val generation: Long,
    val trackGainDb: Double? = null,
    val trackPeak: Double? = null,
)

/**
 * Plain playback runs no software stage. The route is unverified, so bit-perfect is never claimed for it.
 * The native output reports no route facts, so the sample rate here is only a placeholder that yields no
 * resampler stage; the engine resamples every source to its output rate inside its own graph.
 */
internal data class StageConfig(
    val request: SoftwareStageRequest = PLAIN_REQUEST,
    val bands: List<EqualizerBand> = emptyList(),
    val routeFacts: BitPerfectRouteFacts = UNVERIFIED_ROUTE,
)

internal val PLAIN_REQUEST = SoftwareStageRequest(
    normalizationEnabled = false,
    equalizerEnabled = false,
    speed = 1.0,
    pitchSemitones = 0.0,
    silenceSkippingEnabled = false,
    twoDeckMixingEnabled = true,
)

internal val UNVERIFIED_ROUTE = BitPerfectRouteFacts(
    bitPerfectRequested = false,
    softwareDspEngaged = false,
    usbExclusive = false,
    directPlaybackQueried = false,
    directPlaybackSupported = false,
    inputSampleRateHz = 48_000,
    inputBitDepth = 32,
)
