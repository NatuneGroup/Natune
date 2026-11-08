package moe.rukamori.archivetune.shared.data.platform.ios

import cnames.structs.at_audio_output
import moe.rukamori.archivetune.nativeaudio.at_eq_band
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import moe.rukamori.archivetune.nativeaudio.AT_AUDIO_EQ_MAX_BANDS
import moe.rukamori.archivetune.nativeaudio.at_audio_equalizer
import moe.rukamori.archivetune.nativeaudio.at_audio_preamp
import kotlin.math.log10
import moe.rukamori.archivetune.shared.domain.dsp.clarity.ClarityFilterType
import moe.rukamori.archivetune.shared.domain.dsp.DcBlocker
import moe.rukamori.archivetune.shared.domain.dsp.DspSettings
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBand
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBandType
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBandwidth

/**
 * One equalizer band in the shape the host's two render paths take: the native `at_eq_band` ABI on the
 * ordinary output and the AVAudioUnitEQ parameters on a blend deck. [type] packs the shared band type
 * as the ABI's 0/1/2 (peaking/low shelf/high shelf).
 */
internal data class IosEqBand(
    val type: Int,
    val frequencyHz: Float,
    val bandwidthOctaves: Float,
    val gainDb: Float,
)

/**
 * The shared settings value's band list converted once, for both the native output and the blend decks. The
 * bands are the shared model's, so this adapter carries no layout, no gain conversion and no coefficient design;
 * AVAudioUnitEQ renders the parameters the core describes.
 */
internal fun DspSettings.toIosEqBands(): List<IosEqBand> = buildList {
    if (equalizerEnabled) addAll(equalizerBands.toIosEqBands())
    clarity?.bands?.forEach { band ->
        add(IosEqBand(when (band.type) {
            ClarityFilterType.HIGH_PASS -> 3
            ClarityFilterType.PEAKING -> 0
            ClarityFilterType.HIGH_SHELF -> 2
        }, band.frequencyHz.toFloat(), EqualizerBandwidth.octavesForQ(band.q).toFloat(), band.gainDb.toFloat()))
    }
    // AVAudioUnitEQ's high-pass is an available approximation, not the shared first-order DC-blocker transfer.
    if (dcBlockerEnabled) add(IosEqBand(3, DcBlocker.DEFAULT_CUTOFF_HZ.toFloat(), 1f, 0f))
}

/** The shared band list converted once, for both the native output and the blend decks. */
internal fun List<EqualizerBand>.toIosEqBands(): List<IosEqBand> = map { band ->
    IosEqBand(
        type = band.type.iosEqType,
        frequencyHz = band.frequencyHz.toFloat(),
        bandwidthOctaves = EqualizerBandwidth.octavesForQ(band.q).toFloat(),
        gainDb = band.gainDb.toFloat(),
    )
}

/** The shared band type packed for the `at_eq_band` ABI. */
internal val EqualizerBandType.iosEqType: Int
    get() = when (this) {
        EqualizerBandType.PEAKING -> 0
        EqualizerBandType.LOW_SHELF -> 1
        EqualizerBandType.HIGH_SHELF -> 2
    }

/**
 * The preamp the native unit renders: its own switch decides, as in the shared chain, and 0 dB is unity. The ABI
 * clamps to its own ±24 dB, the same bound as one band's gain.
 */
internal val DspSettings.iosPreampDb: Float
    get() = if (preampEnabled) preampDb.toFloat() else 0f

/** The gain actually supplied by the host, with unknown track measurement remaining unity. */
internal val DspSettings.iosInputGainDb: Float
    get() = (iosPreampDb + if (normalizationEnabled) 20f * log10(linearGain) else 0f).coerceIn(-24f, 24f)

/**
 * Applies the shared settings' tone to [output]: the equalizer bands and their switch, and the user preamp on the
 * same unit's global gain. The one value the desktop and iOS ports both take, so the host never passes a band list
 * of its own.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun applyIosDsp(output: CPointer<at_audio_output>, settings: DspSettings) {
    applyIosTone(output, settings.toIosEqBands(), true, settings.iosInputGainDb)
}

/**
 * Packs [bands] into the native ABI and applies them and [preampDb] to [output], capped at the unit's band count.
 * Both the playback port and a blend's native outputs use this, so a promoted deck keeps the tone the blend rendered.
 * The unit keeps the bands bypassed while [enabled] is off, and the preamp still applies because its own switch is
 * separate from the equalizer's.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun applyIosTone(
    output: CPointer<at_audio_output>,
    bands: List<IosEqBand>,
    enabled: Boolean,
    preampDb: Float,
) {
    memScoped {
        val count = minOf(bands.size, AT_AUDIO_EQ_MAX_BANDS.toInt())
        val array = allocArray<at_eq_band>(count)
        for (index in 0 until count) {
            val band = bands[index]
            array[index].type = band.type.toUInt()
            array[index].frequency_hz = band.frequencyHz
            array[index].bandwidth_octaves = band.bandwidthOctaves
            array[index].gain_db = band.gainDb
        }
        at_audio_equalizer(output, array, count.toUInt(), if (enabled) 1 else 0)
    }
    at_audio_preamp(output, preampDb)
}
