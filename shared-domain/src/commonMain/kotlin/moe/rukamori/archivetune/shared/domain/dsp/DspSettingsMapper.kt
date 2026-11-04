package moe.rukamori.archivetune.shared.domain.dsp

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import moe.rukamori.archivetune.shared.domain.dsp.clarity.ClarityPreset
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBand
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBandLevels
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBandType
import moe.rukamori.archivetune.shared.domain.dsp.tempo.PitchConversion
import moe.rukamori.archivetune.shared.domain.dsp.tempo.TempoPitchStage

/**
 * The raw stored values the app already keeps for DSP, so a host can hand them over unchanged. Every field
 * names the preference it is read from; a value the host does not have stays at its default.
 */
data class PersistedDspValues(
    /** `audioNormalization` — the track normalization switch. */
    val audioNormalization: Boolean = false,
    /** `equalizerEnabled` — the shared equalizer switch. */
    val equalizerEnabled: Boolean = false,
    /** `equalizerBandLevelsMb` — a JSON array of millibel levels, one per band of the writing host. */
    val equalizerBandLevelsMb: String? = null,
    /** `equalizerOutputGainMb` — the user preamp, in millibels. */
    val equalizerOutputGainMb: Int = 0,
    /** `lw_eq_enabled` — the legacy 15-band equalizer switch. */
    val lastWaveEqualizerEnabled: Boolean = false,
    /** `lw_eq_gains` — fifteen comma-separated decibel levels. */
    val lastWaveGainsDb: String? = null,
    /** `eq_bands_json` / `param_eq_bands_json` — parametric bands, and their enabled flags. */
    val tryptifyEqualizerEnabled: Boolean = false,
    val tryptifyGraphicBandsJson: String? = null,
    val tryptifyParametricBandsJson: String? = null,
    /** `lw_music_enhancer` and `lw_clarity_preset` — the Studio Master Clarity switch and preset index. */
    val clarityEnabled: Boolean = false,
    val clarityPresetIndex: Int = 0,
    /** `floatDspEnabled` — the native float chain that ran a DC blocker, limiter and TPDF dither. */
    val floatDspEnabled: Boolean = false,
    /** `playbackSpeed` and `playbackPitch` — multiplier controls bounded to 0.25..2. */
    val playbackSpeed: Float = 1f,
    val playbackPitch: Float = 1f,
    /** The automix or crossfade gain for the block being played. */
    val transitionGain: Float = 1f,
)

/**
 * Reads the settings the app already stores into one [DspSettings].
 *
 * Every stored equalizer format the app has written is understood, in this precedence: Tryptify's parametric
 * bands, then its graphic bands, then the legacy fifteen-band gains, then the shared millibel levels. A band
 * list survives as the same shapes through [EqualizerBand], so a host that only ever knew millibels still
 * arrives at the same gains.
 *
 * `dsp_state_json` (the Tryptify multi-bus preset graph) is deliberately not mapped: it describes a plugin
 * graph, not an equalizer, and the unified chain has no bus to restore. A host that used to read it drops it.
 */
object DspSettingsMapper {
    const val MILLIBELS_PER_DB = 100.0

    /** The legacy equalizer's fifteen ISO centres. */
    val LAST_WAVE_BAND_FREQUENCIES_HZ: List<Double> = listOf(
        25.0, 40.0, 63.0, 100.0, 160.0,
        250.0, 400.0, 630.0, 1_000.0, 1_600.0,
        2_500.0, 4_000.0, 6_300.0, 10_000.0, 16_000.0,
    )

    /** One octave per band, the spacing both graphic equalizers used. */
    const val GRAPHIC_BAND_Q = 1.4142135623730951

    /** The legacy parametric band model, unchanged: `{id, type, freq, gain, q, enabled}`. */
    private const val MIN_Q = 0.1

    private val json = Json { ignoreUnknownKeys = true }

    /** Bands from the shared millibel levels, laid out on the caller's band centres. */
    fun bandsFromLevelsMb(
        levelsJson: String?,
        bandCentresHz: List<Double>,
        q: Double = GRAPHIC_BAND_Q,
    ): List<EqualizerBand> {
        if (levelsJson.isNullOrBlank()) return emptyList()
        val levels = EqualizerBandLevels.decode(levelsJson, bandCentresHz.size)
        return bandCentresHz.mapIndexed { index, centreHz ->
            EqualizerBand(
                type = EqualizerBandType.PEAKING,
                frequencyHz = centreHz,
                gainDb = levels[index] / MILLIBELS_PER_DB,
                q = q,
            )
        }
    }

    /** Bands from the legacy fifteen-band gains, comma separated decibels. */
    fun bandsFromLastWaveGains(gainsCsv: String?): List<EqualizerBand> {
        if (gainsCsv.isNullOrBlank()) return emptyList()
        val gains = gainsCsv.split(',').mapNotNull { value -> value.trim().toDoubleOrNull() }
        if (gains.size != LAST_WAVE_BAND_FREQUENCIES_HZ.size) return emptyList()
        return LAST_WAVE_BAND_FREQUENCIES_HZ.mapIndexedNotNull { index, centreHz ->
            val gain = gains[index]
            if (!gain.isFinite()) return@mapIndexedNotNull null
            EqualizerBand(
                type = EqualizerBandType.PEAKING,
                frequencyHz = centreHz,
                gainDb = gain.coerceIn(-EqualizerBand.MAX_GAIN_DB, EqualizerBand.MAX_GAIN_DB),
                q = GRAPHIC_BAND_Q,
            )
        }
    }

    /**
     * Bands from Tryptify's stored parametric list: an array of `{type, freq, gain, q, enabled}`. A disabled
     * band is dropped, an unknown type is dropped, and an entry that does not form a valid band is skipped.
     */
    fun bandsFromTryptifyJson(raw: String?): List<EqualizerBand> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = try {
            json.parseToJsonElement(raw) as? JsonArray
        } catch (_: SerializationException) {
            null
        } ?: return emptyList()

        val bands = ArrayList<EqualizerBand>()
        for (entry in array) {
            if (bands.size == DspSettings.MAX_EQUALIZER_BANDS) break
            val band = entry as? JsonObject ?: continue
            if ((band["enabled"] as? JsonPrimitive)?.booleanOrNull == false) continue
            val frequencyHz = band.double("freq") ?: continue
            val gainDb = band.double("gain") ?: continue
            val type = when (band.string("type")) {
                "LOWSHELF" -> EqualizerBandType.LOW_SHELF
                "HIGHSHELF" -> EqualizerBandType.HIGH_SHELF
                null, "PEAKING" -> EqualizerBandType.PEAKING
                else -> continue
            }
            val decoded = runCatching {
                EqualizerBand(
                    type = type,
                    frequencyHz = frequencyHz,
                    gainDb = gainDb.coerceIn(-EqualizerBand.MAX_GAIN_DB, EqualizerBand.MAX_GAIN_DB),
                    q = (band.double("q") ?: 1.0).coerceAtLeast(MIN_Q),
                )
            }.getOrNull() ?: continue
            bands.add(decoded)
        }
        return bands
    }

    /** The clarity preset a stored `lw_clarity_preset` index names, or null while the switch is off. */
    fun clarityFromStored(enabled: Boolean, presetIndex: Int): ClarityPreset? =
        if (enabled) ClarityPreset.fromIndex(presetIndex) else null

    /** Everything a host stores, read into the one settings model. */
    fun fromPersisted(values: PersistedDspValues, bandCentresHz: List<Double>): DspSettings {
        val parametric = bandsFromTryptifyJson(values.tryptifyParametricBandsJson)
        val graphic = bandsFromTryptifyJson(values.tryptifyGraphicBandsJson)
        val legacy = bandsFromLastWaveGains(values.lastWaveGainsDb)
        val levels = bandsFromLevelsMb(values.equalizerBandLevelsMb, bandCentresHz)
        val bands = when {
            values.tryptifyEqualizerEnabled && parametric.isNotEmpty() -> parametric
            values.tryptifyEqualizerEnabled && graphic.isNotEmpty() -> graphic
            values.lastWaveEqualizerEnabled && legacy.isNotEmpty() -> legacy
            else -> levels
        }.take(DspSettings.MAX_EQUALIZER_BANDS)

        val floatChain = values.floatDspEnabled
        return DspSettings(
            linearGain = 1f,
            preampDb = (values.equalizerOutputGainMb / MILLIBELS_PER_DB)
                .coerceIn(-DspSettings.MAX_PREAMP_DB, DspSettings.MAX_PREAMP_DB),
            normalizationEnabled = values.audioNormalization,
            equalizerEnabled = values.equalizerEnabled || values.lastWaveEqualizerEnabled ||
                values.tryptifyEqualizerEnabled,
            equalizerBands = bands,
            clarity = clarityFromStored(values.clarityEnabled, values.clarityPresetIndex),
            dcBlockerEnabled = floatChain,
            limiterEnabled = floatChain,
            speed = tempoOf(values.playbackSpeed),
            pitchSemitones = semitonesOf(values.playbackPitch),
            transitionGain = values.transitionGain.coerceIn(0f, DspSettings.MAX_TRANSITION_GAIN),
            dither = if (floatChain) DspDither(enabled = true) else DspDither.OFF,
        )
    }

    private fun tempoOf(speed: Float): Double =
        if (!speed.isFinite()) 1.0 else speed.toDouble().coerceIn(TempoPitchStage.MIN_SPEED, TempoPitchStage.MAX_SPEED)

    private fun semitonesOf(pitch: Float): Double {
        if (!pitch.isFinite() || pitch <= 0f) return 0.0
        val multiplier = pitch.toDouble().coerceIn(MIN_PITCH_MULTIPLIER, MAX_PITCH_MULTIPLIER)
        return PitchConversion.multiplierToSemitones(multiplier)
            .coerceIn(-TempoPitchStage.MAX_PITCH_SEMITONES, TempoPitchStage.MAX_PITCH_SEMITONES)
    }

    private const val MIN_PITCH_MULTIPLIER = 0.25
    private const val MAX_PITCH_MULTIPLIER = 2.0

    private fun JsonObject.double(name: String): Double? =
        (this[name] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
