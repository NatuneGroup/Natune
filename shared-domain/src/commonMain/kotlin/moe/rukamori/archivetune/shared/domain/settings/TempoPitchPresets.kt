package moe.rukamori.archivetune.shared.domain.settings

import kotlin.random.Random
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Bounds of the tempo and pitch dialog's multiplier controls, so a stored preset never applies a
 * value the dialog sliders could not express.
 */
const val TEMPO_PITCH_VALUE_MIN = 0.25f
const val TEMPO_PITCH_VALUE_MAX = 2f

/** A named, persisted speed+pitch pair saved from the player menu's tempo and pitch dialog. */
data class TempoPitchPreset(
    val id: String,
    val name: String,
    val speed: Float,
    val pitch: Float,
) {
    val isValid: Boolean
        get() =
            id.isNotBlank() &&
                name.isNotBlank() &&
                speed.inTempoPitchRange() &&
                pitch.inTempoPitchRange()

    /**
     * Applies speed before pitch — the order the baseline apply path used, so a host with linked
     * parameters never sees an intermediate state it cannot express. An invalid preset applies nothing.
     */
    fun applyTo(
        setSpeed: (Float) -> Unit,
        setPitch: (Float) -> Unit,
    ): Boolean {
        if (!isValid) return false
        setSpeed(speed)
        setPitch(pitch)
        return true
    }
}

fun Float.inTempoPitchRange(): Boolean = isFinite() && this in TEMPO_PITCH_VALUE_MIN..TEMPO_PITCH_VALUE_MAX

private val tempoPitchPresetsJson = Json { ignoreUnknownKeys = true }

/**
 * Decodes the `tempoPitchPresetsJson` setting. Malformed JSON, entries outside the dialog range and
 * blank ids or names drop out instead of failing the whole list, and a repeated id keeps the first entry.
 * The codec is hand-built: this module uses the JSON library without the serialization plugin.
 */
fun parseTempoPitchPresets(raw: String?): List<TempoPitchPreset> {
    if (raw.isNullOrBlank()) return emptyList()
    val root =
        try {
            tempoPitchPresetsJson.parseToJsonElement(raw)
        } catch (_: SerializationException) {
            return emptyList()
        }
    val array = root as? JsonArray ?: return emptyList()
    return array.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val id = obj.stringField("id") ?: return@mapNotNull null
        val name = obj.stringField("name") ?: return@mapNotNull null
        val speed = obj["speed"]?.jsonPrimitive?.floatOrNull ?: return@mapNotNull null
        val pitch = obj["pitch"]?.jsonPrimitive?.floatOrNull ?: return@mapNotNull null
        TempoPitchPreset(id, name, speed, pitch).takeIf(TempoPitchPreset::isValid)
    }.distinctBy(TempoPitchPreset::id)
}

/** Only valid presets are written back, so the stored string always stays well-formed. */
fun encodeTempoPitchPresets(presets: List<TempoPitchPreset>): String =
    buildJsonArray {
        presets.filter(TempoPitchPreset::isValid).forEach { preset ->
            addJsonObject {
                put("id", preset.id)
                put("name", preset.name)
                put("speed", preset.speed)
                put("pitch", preset.pitch)
            }
        }
    }.toString()

/** Replaces the same id in place or appends a new one; an invalid preset changes nothing. */
fun upsertTempoPitchPreset(
    presets: List<TempoPitchPreset>,
    preset: TempoPitchPreset,
): List<TempoPitchPreset> {
    if (!preset.isValid) return presets
    return if (presets.any { it.id == preset.id }) {
        presets.map { if (it.id == preset.id) preset else it }
    } else {
        presets + preset
    }
}

/** Renames a preset in place; a blank name or unknown id changes nothing. */
fun renameTempoPitchPreset(
    presets: List<TempoPitchPreset>,
    id: String,
    name: String,
): List<TempoPitchPreset> {
    val trimmed = name.trim()
    if (id.isBlank() || trimmed.isEmpty() || presets.none { it.id == id }) return presets
    return presets.map { if (it.id == id) it.copy(name = trimmed) else it }
}

fun removeTempoPitchPreset(
    presets: List<TempoPitchPreset>,
    id: String,
): List<TempoPitchPreset> = if (id.isBlank()) presets else presets.filterNot { it.id == id }

/** Sixteen random hex characters; the caller's distinct-by-id policy covers the collision case. */
fun newTempoPitchPresetId(random: Random = Random.Default): String {
    val digits = "0123456789abcdef"
    return buildString(16) {
        repeat(8) {
            val value = random.nextInt(256)
            append(digits[value ushr 4])
            append(digits[value and 0xF])
        }
    }
}

private fun JsonObject.stringField(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
