package moe.rukamori.archivetune.shared.data.provider

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Reads the privately owned tracks shelf. Rows are found by renderer key rather than by a fixed path,
 * because the first page and the continuation pages nest the shelf differently.
 */
internal object YouTubeUploadBrowse {
    private const val BULLET = "\u2022"
    private val TIMESTAMP = Regex("""\d{1,2}(:\d{2}){1,2}""")

    fun parse(root: JsonElement): YouTubeUploadedSongsPage {
        val songs = ArrayList<YouTubeUploadedSong>()
        root.forEachRenderer("musicResponsiveListItemRenderer") { row ->
            row.toSong()?.let { songs += it }
        }
        val continuation = root.firstObject("nextContinuationData")?.text("continuation")
            ?: root.firstObject("continuationCommand")?.text("token")
        return YouTubeUploadedSongsPage(songs, continuation)
    }

    /** Visits every object stored under [key] without descending into the rows it finds. */
    private fun JsonElement.forEachRenderer(key: String, visit: (JsonObject) -> Unit) {
        when (this) {
            is JsonObject -> for ((name, value) in this) {
                if (name == key && value is JsonObject) visit(value) else value.forEachRenderer(key, visit)
            }
            is JsonArray -> for (value in this) value.forEachRenderer(key, visit)
            is JsonPrimitive -> Unit
        }
    }

    private fun JsonElement.firstObject(key: String): JsonObject? = when (this) {
        is JsonObject -> (this[key] as? JsonObject) ?: values.firstNotNullOfOrNull { it.firstObject(key) }
        is JsonArray -> firstNotNullOfOrNull { it.firstObject(key) }
        else -> null
    }

    private fun JsonElement.firstString(key: String): String? = when (this) {
        is JsonObject -> (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
            ?: values.firstNotNullOfOrNull { it.firstString(key) }
        is JsonArray -> firstNotNullOfOrNull { it.firstString(key) }
        else -> null
    }

    private fun JsonObject.toSong(): YouTubeUploadedSong? {
        val videoId = obj("playlistItemData")?.text("videoId") ?: firstString("videoId") ?: return null
        val title = flexRuns(0).joinToString("") { it.text("text").orEmpty() }.trim().ifEmpty { videoId }
        val segments = subtitleSegments()
        val artist = segments.firstOrNull()?.takeIf { !TIMESTAMP.matches(it) }
        val album = segments.getOrNull(1)?.takeIf { !TIMESTAMP.matches(it) }
        val duration = fixedDuration() ?: segments.lastOrNull()?.takeIf { TIMESTAMP.matches(it) }?.let { durationSeconds(it) }
        val thumbnail = obj("thumbnail")?.obj("musicThumbnailRenderer")?.obj("thumbnail")
            ?.array("thumbnails")?.lastOrNull()?.let { (it as? JsonObject)?.text("url") }
        val entityId = firstObject("musicDeletePrivatelyOwnedEntityCommand")?.text("entityId")
        return YouTubeUploadedSong(videoId, entityId, title, artist, album, duration, thumbnail)
    }

    private fun JsonObject.flexRuns(index: Int): List<JsonObject> {
        val column = array("flexColumns")?.getOrNull(index) as? JsonObject ?: return emptyList()
        val renderer = column.obj("musicResponsiveListItemFlexColumnRenderer") ?: return emptyList()
        return renderer.obj("text")?.array("runs")?.mapNotNull { it as? JsonObject }.orEmpty()
    }

    /** The subtitle split at the bullet separators, so artist, album and duration stay apart. */
    private fun JsonObject.subtitleSegments(): List<String> {
        val segments = mutableListOf(StringBuilder())
        for (run in flexRuns(1)) {
            val text = run.text("text") ?: continue
            if (text.trim() == BULLET) segments += StringBuilder() else segments.last().append(text)
        }
        return segments.map { it.toString().trim() }.filter(String::isNotEmpty)
    }

    private fun JsonObject.fixedDuration(): Int? {
        val column = array("fixedColumns")?.getOrNull(0) as? JsonObject ?: return null
        val runs = column.obj("musicResponsiveListItemFixedColumnRenderer")?.obj("text")?.array("runs")
        val text = (runs?.firstOrNull() as? JsonObject)?.text("text") ?: return null
        return if (TIMESTAMP.matches(text)) durationSeconds(text) else null
    }

    private fun durationSeconds(text: String): Int? {
        var total = 0
        for (part in text.split(':')) {
            total = total * 60 + (part.toIntOrNull() ?: return null)
        }
        return total
    }
}
