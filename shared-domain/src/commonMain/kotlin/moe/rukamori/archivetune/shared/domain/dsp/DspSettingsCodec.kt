package moe.rukamori.archivetune.shared.domain.dsp

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import moe.rukamori.archivetune.shared.domain.dsp.clarity.ClarityPreset
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBand
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBandType
import moe.rukamori.archivetune.shared.domain.dsp.tempo.TempoPitchStage

/**
 * The stored form of [DspSettings]: one versioned JSON object under a single key, with an explicit field for
 * every setting a platform writes.
 *
 * Decoding is tolerant on purpose. A value that is missing, out of range or simply wrong keeps its default,
 * and an unreadable band is dropped rather than failing the document, because a stored settings blob must
 * never be able to stop playback.
 */
object DspSettingsCodec {
    const val FORMAT_VERSION = 1

    private const val MIN_Q = 0.1
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(settings: DspSettings): String = buildJsonObject {
        put("version", FORMAT_VERSION)
        put("linearGain", settings.linearGain.toDouble())
        put("preampDb", settings.preampDb)
        put("preamp", settings.preampEnabled)
        put("normalization", settings.normalizationEnabled)
        put("equalizer", settings.equalizerEnabled)
        putJsonArray("bands") {
            settings.equalizerBands.forEach { band ->
                addJsonObject {
                    put("type", band.type.name)
                    put("hz", band.frequencyHz)
                    put("gainDb", band.gainDb)
                    put("q", band.q)
                    band.rightFrequencyHz?.let { put("rightHz", it) }
                    band.rightGainDb?.let { put("rightGainDb", it) }
                    band.rightQ?.let { put("rightQ", it) }
                }
            }
        }
        settings.clarity?.let { put("clarity", it.id) }
        put("dcBlocker", settings.dcBlockerEnabled)
        put("limiter", settings.limiterEnabled)
        put("limiterCeilingDb", settings.limiterCeilingDb)
        put("speed", settings.speed)
        put("pitchSemitones", settings.pitchSemitones)
        put("transitionGain", settings.transitionGain.toDouble())
        putJsonObject("dither") {
            put("enabled", settings.dither.enabled)
            put("bitDepth", settings.dither.bitDepth)
            put("seed", settings.dither.seed)
        }
    }.toString()

    fun decode(raw: String?): DspSettings {
        if (raw.isNullOrBlank()) return DspSettings()
        val root = try {
            json.parseToJsonElement(raw) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return DspSettings()

        val defaults = DspSettings()
        val dither = root["dither"] as? JsonObject
        return DspSettings(
            linearGain = root.finiteDouble("linearGain")?.takeIf { it > 0.0 }?.toFloat() ?: defaults.linearGain,
            preampDb = root.finiteDouble("preampDb")
                ?.coerceIn(-DspSettings.MAX_PREAMP_DB, DspSettings.MAX_PREAMP_DB)
                ?: defaults.preampDb,
            // A document written before the preamp switch existed keeps the preamp it always had.
            preampEnabled = root.bool("preamp") ?: defaults.preampEnabled,
            normalizationEnabled = root.bool("normalization") ?: defaults.normalizationEnabled,
            equalizerEnabled = root.bool("equalizer") ?: defaults.equalizerEnabled,
            equalizerBands = bands(root["bands"] as? JsonArray),
            clarity = ClarityPreset.fromId(root.string("clarity")),
            dcBlockerEnabled = root.bool("dcBlocker") ?: defaults.dcBlockerEnabled,
            limiterEnabled = root.bool("limiter") ?: defaults.limiterEnabled,
            limiterCeilingDb = root.finiteDouble("limiterCeilingDb")
                ?.coerceIn(SoftKneeLimiter.MIN_CEILING_DB, 0.0)
                ?: defaults.limiterCeilingDb,
            speed = root.finiteDouble("speed")
                ?.coerceIn(TempoPitchStage.MIN_SPEED, TempoPitchStage.MAX_SPEED)
                ?: defaults.speed,
            pitchSemitones = root.finiteDouble("pitchSemitones")
                ?.coerceIn(-TempoPitchStage.MAX_PITCH_SEMITONES, TempoPitchStage.MAX_PITCH_SEMITONES)
                ?: defaults.pitchSemitones,
            transitionGain = root.finiteDouble("transitionGain")
                ?.coerceIn(0.0, DspSettings.MAX_TRANSITION_GAIN.toDouble())
                ?.toFloat()
                ?: defaults.transitionGain,
            dither = DspDither(
                enabled = dither?.bool("enabled") ?: defaults.dither.enabled,
                bitDepth = dither?.int("bitDepth")
                    ?.coerceIn(DspDither.MIN_BIT_DEPTH, DspDither.MAX_BIT_DEPTH)
                    ?: defaults.dither.bitDepth,
                seed = dither?.int("seed") ?: defaults.dither.seed,
            ),
        )
    }

    private fun bands(array: JsonArray?): List<EqualizerBand> {
        if (array == null) return emptyList()
        val bands = ArrayList<EqualizerBand>()
        for (element in array) {
            if (bands.size == DspSettings.MAX_EQUALIZER_BANDS) break
            val band = band(element as? JsonObject ?: continue) ?: continue
            bands.add(band)
        }
        return bands
    }

    private fun band(element: JsonObject): EqualizerBand? {
        val frequencyHz = element.finiteDouble("hz") ?: return null
        val gainDb = element.finiteDouble("gainDb") ?: return null
        return runCatching {
            EqualizerBand(
                type = bandType(element.string("type")) ?: return null,
                frequencyHz = frequencyHz,
                gainDb = gainDb.coerceIn(-EqualizerBand.MAX_GAIN_DB, EqualizerBand.MAX_GAIN_DB),
                q = (element.finiteDouble("q") ?: 1.0).coerceAtLeast(MIN_Q),
                rightFrequencyHz = element.finiteDouble("rightHz"),
                rightGainDb = element.finiteDouble("rightGainDb"),
                rightQ = element.finiteDouble("rightQ")?.coerceAtLeast(MIN_Q),
            )
        }.getOrNull()
    }

    private fun bandType(name: String?): EqualizerBandType? = when (name) {
        null, EqualizerBandType.PEAKING.name -> EqualizerBandType.PEAKING
        EqualizerBandType.LOW_SHELF.name -> EqualizerBandType.LOW_SHELF
        EqualizerBandType.HIGH_SHELF.name -> EqualizerBandType.HIGH_SHELF
        else -> null
    }

    private fun JsonObject.finiteDouble(name: String): Double? =
        (this[name] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }

    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.bool(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
