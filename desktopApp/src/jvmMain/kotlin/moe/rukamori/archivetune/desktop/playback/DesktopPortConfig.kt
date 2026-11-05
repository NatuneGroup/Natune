package moe.rukamori.archivetune.desktop.playback

import moe.rukamori.archivetune.shared.domain.dsp.bitperfect.BitPerfectGate
import moe.rukamori.archivetune.shared.domain.dsp.bitperfect.BitPerfectRouteFacts
import moe.rukamori.archivetune.shared.domain.dsp.bitperfect.SoftwareStagePlan
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

/**
 * A pitch request this host cannot honour is kept neutral from the moment it arrives, so nothing downstream can report
 * an alteration the engine does not make.
 */
internal fun SoftwareStageRequest.neutralPitch(): SoftwareStageRequest =
    if (pitchSemitones == 0.0) this else copy(pitchSemitones = 0.0)

/**
 * The software stages the desktop engine can honour, resolved through the shared bit-perfect gate.
 *
 * The engine graph has no pitch stage: the pinned FFmpeg build carries no pitch shifter, and a varispeed resample would
 * sound unlike the phase-vocoder shift Android and iOS apply. Pitch therefore never counts as a stage that alters the
 * samples, so a pitch value cannot withhold bit-perfect output for a track this host plays untouched.
 */
internal fun desktopStagePlan(config: StageConfig): SoftwareStagePlan {
    val request = config.request.neutralPitch()
    val equalizerEngaged = request.equalizerEnabled && config.bands.any { it.gainDb != 0.0 }
    val samplesAltered = equalizerEngaged || request.normalizationEnabled || request.speed != 1.0
    val decision = BitPerfectGate.evaluate(config.routeFacts.copy(softwareDspEngaged = samplesAltered))
    return SoftwareStagePlan.resolve(decision, request)
}

/** The engine's tempo is the playback speed, and the shared plan keeps tempo off whenever the route is requested. */
internal fun desktopEffectiveSpeed(plan: SoftwareStagePlan, request: SoftwareStageRequest): Float =
    if (plan.tempoPitch) request.speed.toFloat() else 1f
