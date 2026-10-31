package moe.rukamori.archivetune.shared.domain.dsp.eq

import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The equalizer's persisted band levels, shared by every host. Each level is a gain in millibels (100 per dB), and the
 * whole list is stored as a JSON array under `equalizerBandLevelsMb`, the key Android writes. Android sizes the list to
 * its device's bands, so a host with another band count resamples by index, as Android does.
 */
object EqualizerBandLevels {
    /** The widest level a stored band may hold: the portable gain limit, so a stored value can never fail a band. */
    const val MAX_LEVEL_MB: Int = 2_400

    private val serializer = ListSerializer(Int.serializer())

    /**
     * The levels stored in [raw], resampled to [bandCount]. A blank or unreadable value reads as flat, as Android reads
     * it, so a damaged value never blocks playback.
     */
    fun decode(raw: String?, bandCount: Int): List<Int> {
        val stored = if (raw.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching { Json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
        }
        return resample(stored.map { it.coerceIn(-MAX_LEVEL_MB, MAX_LEVEL_MB) }, bandCount)
    }

    /** The JSON array Android stores for [levelsMb]. */
    fun encode(levelsMb: List<Int>): String = Json.encodeToString(serializer, levelsMb)

    /**
     * Resamples [levelsMb] to [bandCount] by index, as Android does: equal counts pass through, one band takes the mean,
     * and other counts interpolate linearly between the neighbouring stored levels. An empty list reads as flat.
     */
    fun resample(levelsMb: List<Int>, bandCount: Int): List<Int> {
        if (bandCount <= 0) return emptyList()
        if (levelsMb.isEmpty()) return List(bandCount) { 0 }
        if (levelsMb.size == bandCount) return levelsMb
        if (bandCount == 1) return listOf(levelsMb.sum() / levelsMb.size)
        val lastIndex = (levelsMb.size - 1).toFloat()
        return List(bandCount) { index ->
            val position = index * lastIndex / (bandCount - 1)
            val low = floor(position).toInt().coerceIn(0, levelsMb.lastIndex)
            val high = ceil(position).toInt().coerceIn(0, levelsMb.lastIndex)
            val fraction = (position - low).coerceIn(0f, 1f)
            val lowLevel = levelsMb[low]
            val highLevel = levelsMb[high]
            (lowLevel + (highLevel - lowLevel) * fraction).toInt()
        }
    }
}
