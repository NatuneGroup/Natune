package moe.rukamori.archivetune.desktop

import moe.rukamori.archivetune.shared.domain.repository.*
import org.json.JSONArray
import org.json.JSONObject

internal data class DesktopModuleMetadataItem(
    val kind: ContentKind,
    val id: String,
    val title: String,
    val artists: List<String> = emptyList(),
    val album: String? = null,
    val durationMs: Long? = null,
    val isrc: String? = null,
) {
    fun queueItem(installation: String, audio: Boolean): ContentItem = ContentItem(
        ContentRef("modules", kind, "${if (audio) "module" else "metadata"}:$installation:$id"), title,
        subtitle = artists.joinToString(", "), durationMs = durationMs, album = album,
        artists = artists.filter(String::isNotBlank).map { ContentCredit(it) }, isrc = isrc)
}

/** Projects only catalog fields. Settings, auth material and arbitrary JSON never enter the page. */
internal object DesktopModuleMetadataReader {
    private val collections = mapOf("tracks" to ContentKind.SONG, "songs" to ContentKind.SONG,
        "albums" to ContentKind.ALBUM, "artists" to ContentKind.ARTIST, "playlists" to ContentKind.PLAYLIST)
    fun read(value: Any): List<DesktopModuleMetadataItem> {
        val rows = ArrayList<DesktopModuleMetadataItem>()
        var visited = 0
        fun visit(value: Any?, hint: ContentKind?, depth: Int) {
            if (depth > 6 || visited++ >= 2000 || rows.size >= 200) return
            when (value) {
                is JSONArray -> for (index in 0 until value.length().coerceAtMost(200)) visit(value.opt(index), hint, depth + 1)
                is JSONObject -> {
                    val id = value.text("id").ifBlank { value.text("trackId") }
                    val title = value.text("title").ifBlank { value.text("name") }
                    val kind = when (value.text("type").lowercase()) {
                        "track", "song" -> ContentKind.SONG
                        "album" -> ContentKind.ALBUM
                        "artist" -> ContentKind.ARTIST
                        "playlist" -> ContentKind.PLAYLIST
                        else -> hint ?: ContentKind.SONG.takeIf { value.has("artists") || value.has("artist") || value.has("duration") }
                    }
                    val containers = (collections.keys + listOf("sections", "items", "results", "data", "contents"))
                        .filter { value.opt(it) is JSONArray || value.opt(it) is JSONObject }
                        .filterNot { it == "artists" && id.isNotBlank() && title.isNotBlank() && kind != null }
                    if (containers.isNotEmpty()) {
                        containers.forEach { visit(value.opt(it), collections[it] ?: hint, depth + 1) }
                        return
                    }
                    if (kind == null || id.isBlank() || title.isBlank() || id.length > 2048 || title.length > 2048) return
                    val artists = when (val raw = value.opt("artists") ?: value.opt("artist")) {
                        is JSONArray -> (0 until raw.length().coerceAtMost(32)).mapNotNull { name(raw.opt(it)) }
                        else -> listOfNotNull(name(raw))
                    }
                    val duration = if (value.has("durationMs")) value.optLong("durationMs") else value.optLong("duration") * 1000L
                    rows += DesktopModuleMetadataItem(kind, id, title, artists, name(value.opt("album")),
                        duration.takeIf { it in 1..86_400_000L }, value.text("isrc").takeIf(String::isNotBlank))
                }
            }
        }
        visit(value, null, 0)
        return rows.distinctBy { it.kind to it.id }
    }
    private fun JSONObject.text(key: String): String = (opt(key) as? String).orEmpty()
    private fun name(value: Any?): String? = when (value) {
        is String -> value
        is JSONObject -> value.text("name").ifBlank { value.text("title") }
        else -> null
    }?.takeIf { it.isNotBlank() && it.length <= 2048 }
}
