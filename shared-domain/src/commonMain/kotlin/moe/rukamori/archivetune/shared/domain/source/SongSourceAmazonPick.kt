package moe.rukamori.archivetune.shared.domain.source

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class AmazonTrackPick(val title: String, val artist: String, val album: String?, val durationMs: Long?)

/** The Android JSON layout: metadata, not an Apple catalog id masquerading as an Amazon id. */
object SongSourceAmazonPick {
    fun parse(raw: String?): Map<String, AmazonTrackPick> {
        val root = raw?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() as? JsonObject } ?: return emptyMap()
        return buildMap {
            root.forEach { (songId, value) ->
                val row = value as? JsonObject ?: return@forEach
                fun text(name: String) = (row[name] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                val title = text("title")
                val artist = text("artist")
                if (songId.isBlank() || title.isEmpty() || artist.isEmpty()) return@forEach
                put(songId, AmazonTrackPick(title, artist, text("album").ifEmpty { null },
                    (row["durationMs"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0L }))
            }
        }
    }

    fun serialize(picks: Map<String, AmazonTrackPick>): String =
        if (picks.isEmpty()) "" else buildJsonObject {
            picks.forEach { (songId, pick) ->
                put(songId, buildJsonObject {
                    put("title", pick.title)
                    put("artist", pick.artist)
                    pick.album?.let { put("album", it) }
                    pick.durationMs?.let { put("durationMs", it) }
                })
            }
        }.toString()

    fun get(raw: String?, songId: String): AmazonTrackPick? = parse(raw)[songId]

    fun withOverride(raw: String?, songId: String, pick: AmazonTrackPick?): String {
        val picks = LinkedHashMap(parse(raw))
        if (pick == null) picks.remove(songId) else picks[songId] = pick
        return serialize(picks)
    }
}
