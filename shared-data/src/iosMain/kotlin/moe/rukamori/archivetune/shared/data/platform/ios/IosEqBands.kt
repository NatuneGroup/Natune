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
 * Packs [bands] into the native ABI and applies them to [output], capped at the unit's band count. Both the
 * playback port and a blend's native outputs use this, so a promoted deck keeps the tone the blend rendered.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun applyIosEqualizer(output: CPointer<at_audio_output>, bands: List<IosEqBand>, enabled: Boolean) {
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
}
