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
import moe.rukamori.archivetune.shared.domain.dsp.eq.GraphicEqualizer
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
    /** `equalizerOutputGainMb` — the user preamp, in millibels. Read only while its own switch is on. */
    val equalizerOutputGainMb: Int = 0,
    /** `equalizerOutputGainEnabled` — the user preamp's own switch. Off keeps the stored gain stored, not applied. */
    val equalizerOutputGainEnabled: Boolean = true,
    /** `lw_eq_enabled` — the legacy 15-band equalizer switch. */
    val lastWaveEqualizerEnabled: Boolean = false,
    /** `lw_eq_gains` — fifteen comma-separated decibel levels. */
    val lastWaveGainsDb: String? = null,
    /**
     * `tryptifyAudioProcessing` — the retired Tryptify engine switch. A stage is only migrated while the
     * engine that ran it was on, so a preset left behind by a dormant engine stays dormant.
     */
    val tryptifyEqualizerEnabled: Boolean = false,
    /** `eq_enabled` — the Tryptify graphic stage's own switch. */
    val tryptifyGraphicStageEnabled: Boolean = false,
    /** `param_eq_enabled` — the Tryptify parametric stage's own switch. */
    val tryptifyParametricStageEnabled: Boolean = false,
    /** `eq_bands_json` / `param_eq_bands_json` — the two stages' stored band lists. */
    val tryptifyGraphicBandsJson: String? = null,
    val tryptifyParametricBandsJson: String? = null,
    /** `eq_stereo_mode` and `eq_bands_r_json` — the graphic stage's independent right-channel graph. */
    val tryptifyStereoMode: Boolean = false,
    val tryptifyRightGraphicBandsJson: String? = null,
    /** `eq_preamp` / `param_eq_preamp` — each stage's own preamp, in decibels. */
    val tryptifyGraphicPreampDb: Double? = null,
    val tryptifyParametricPreampDb: Double? = null,
    /** `lastwaveAudioProcessing` — the retired LastWave engine switch, gating both of its substages. */
    val lastWaveEngineEnabled: Boolean = false,
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
 * Every stored equalizer format the app has written is understood. A retired engine's stage is migrated only
 * while that engine and that stage were switched on, in this precedence: Tryptify's parametric stage, its
 * graphic stage, the legacy fifteen-band gains, and finally the shared millibel levels. A band list survives
 * as the same shapes through [EqualizerBand], so a host that only ever knew millibels still arrives at the
 * same gains.
 *
 * `docs/engineering/dsp.md` records what one band list and one preamp cannot represent: both Tryptify stages
 * enabled at once, a right-channel graph of another width, and a right-channel band type. Those stored values
 * stay untouched and inactive rather than being approximated.
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
    const val GRAPHIC_BAND_Q = GraphicEqualizer.BAND_Q

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

    /**
     * Everything a host stores, read into the one settings model.
     *
     * A retired engine's presets are migrated only while the engine and that stage were switched on, so a
     * preset a dormant engine left on disk stays dormant instead of becoming audible under the new
     * processing switch. The stages migrate in this order: Tryptify's parametric stage, then its graphic
     * stage (with `eq_stereo_mode`'s right-channel graph when it lines up band for band), then LastWave's
     * fifteen gains, and only then the shared millibel levels.
     */
    fun fromPersisted(values: PersistedDspValues, bandCentresHz: List<Double>): DspSettings {
        val parametric = bandsFromTryptifyJson(values.tryptifyParametricBandsJson)
        val graphic = bandsFromTryptifyJson(values.tryptifyGraphicBandsJson)
        val legacy = bandsFromLastWaveGains(values.lastWaveGainsDb)
        val levels = bandsFromLevelsMb(values.equalizerBandLevelsMb, bandCentresHz)
        // A stage is switched on by its engine and its own switch; an empty band list is a valid state, because
        // the stage's preamp ran whether or not it had a curve.
        val parametricActive = values.tryptifyEqualizerEnabled && values.tryptifyParametricStageEnabled
        val graphicActive = values.tryptifyEqualizerEnabled && values.tryptifyGraphicStageEnabled
        val parametricStage = parametricActive && parametric.isNotEmpty()
        val graphicStage = graphicActive && graphic.isNotEmpty()
        val lastWaveStage =
            values.lastWaveEngineEnabled && values.lastWaveEqualizerEnabled && legacy.isNotEmpty()
        val migrated = when {
            parametricStage -> parametric
            graphicStage -> withRightOverrides(graphic, values)
            lastWaveStage -> legacy
            else -> emptyList()
        }
        val bands = (if (migrated.isEmpty()) levels else migrated).take(DspSettings.MAX_EQUALIZER_BANDS)

        val floatChain = values.floatDspEnabled
        val stagePreampDb = migratedStagePreampDb(values, parametricActive, graphicActive)
        return DspSettings(
            linearGain = 1f,
            preampDb = (sharedPreampDb(values) + stagePreampDb)
                .coerceIn(-DspSettings.MAX_PREAMP_DB, DspSettings.MAX_PREAMP_DB),
            // The user preamp applies while its own switch is on. A retired stage applied its own gain
            // whenever that stage ran, so it keeps contributing after the shared preamp switch was left off.
            preampEnabled = values.equalizerOutputGainEnabled || stagePreampDb != 0.0,
            normalizationEnabled = values.audioNormalization,
            equalizerEnabled = values.equalizerEnabled || parametricStage || graphicStage || lastWaveStage,
            equalizerBands = bands,
            clarity = clarityFromStored(
                enabled = values.lastWaveEngineEnabled && values.clarityEnabled,
                presetIndex = values.clarityPresetIndex,
            ),
            dcBlockerEnabled = floatChain,
            limiterEnabled = floatChain,
            speed = tempoOf(values.playbackSpeed),
            pitchSemitones = semitonesOf(values.playbackPitch),
            transitionGain = values.transitionGain.coerceIn(0f, DspSettings.MAX_TRANSITION_GAIN),
            dither = if (floatChain) DspDither(enabled = true) else DspDither.OFF,
        )
    }

    /** The shared equalizer's output gain, already gated by its own switch at the host's read boundary. */
    private fun sharedPreampDb(values: PersistedDspValues): Double = values.equalizerOutputGainMb / MILLIBELS_PER_DB

    /**
     * The preamp the migrated stage carried. Each retired stage applied its own gain whenever that stage was
     * switched on, whether or not it also had a band curve, so a preamp-only preset keeps its gain. The first
     * enabled stage still supplies the one preamp this model has, in the same precedence the bands use.
     */
    private fun stagePreampDb(preampDb: Double?): Double =
        if (preampDb != null && preampDb.isFinite()) preampDb else 0.0

    private fun migratedStagePreampDb(
        values: PersistedDspValues,
        parametricActive: Boolean,
        graphicActive: Boolean,
    ): Double =
        when {
            parametricActive -> stagePreampDb(values.tryptifyParametricPreampDb)
            graphicActive -> stagePreampDb(values.tryptifyGraphicPreampDb)
            else -> 0.0
        }

    /**
     * Carries `eq_stereo_mode` into the band list when the stored right-channel graph has the same shape as
     * the left one, so each band keeps its own right-channel design. A right graph of another width cannot be
     * paired band for band, and the shared band model carries no right-channel band type, so those are not
     * guessed at: the bands then run linked and the stored value stays untouched.
     */
    private fun withRightOverrides(bands: List<EqualizerBand>, values: PersistedDspValues): List<EqualizerBand> {
        if (!values.tryptifyStereoMode) return bands
        val right = bandsFromTryptifyJson(values.tryptifyRightGraphicBandsJson)
        if (right.size != bands.size) return bands
        return bands.mapIndexed { index, band ->
            val rightBand = right[index]
            band.copy(
                rightFrequencyHz = rightBand.frequencyHz.takeIf { it != band.frequencyHz },
                rightGainDb = rightBand.gainDb.takeIf { it != band.gainDb },
                rightQ = rightBand.q.takeIf { it != band.q },
            )
        }
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
