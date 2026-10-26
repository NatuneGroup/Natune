package moe.rukamori.archivetune.shared.data.persistence

import kotlinx.serialization.json.*

data class PlaylistExchangeTrack(val title: String, val artist: String, val album: String, val mediaId: String,
    val uri: String = "", val durationSeconds: Int? = null)
data class ImportedPlaylist(val title: String, val tracks: List<PlaylistExchangeTrack>, val tags: Set<String>)

object PlaylistExchange {
    /** The reason every reader gives for a playlist with no songs. The import owner tells that case apart by it. */
    const val EMPTY_PLAYLIST_MESSAGE = "Playlist contains no tracks"

    /** The largest document any reader accepts, in characters. A larger file is unreadable rather than malformed. */
    const val MAX_CHARACTERS = 16_000_000

    private val csvHeader = listOf("Title", "Artist", "Album", "Media ID")

    /** Exact shipped CSV writer: BOM, CRLF, quoted fields and spreadsheet formula guarding. */
    fun csv(tracks: List<PlaylistExchangeTrack>): String {
        require(tracks.isNotEmpty())
        return "\uFEFF" + (listOf(csvHeader) + tracks.map { listOf(it.title, it.artist, it.album, it.mediaId) })
            .joinToString("") { row -> row.joinToString(",") { field ->
                val safe = if (field.firstOrNull { !it.isWhitespace() } in setOf('=', '+', '-', '@')) "'$field" else field
                "\"${safe.replace("\"", "\"\"")}\""
            } + "\r\n" }
    }

    /** The protective apostrophe is retained: stripping it would corrupt literal user apostrophes. */
    fun readCsv(raw: String): List<PlaylistExchangeTrack> {
        bounded(raw)
        val rows = csvRows(raw.removePrefix("\uFEFF"))
        require(rows.isNotEmpty() && rows.first() == csvHeader) { "Unrecognized playlist CSV header" }
        return rows.drop(1).map { row ->
            require(row.size == 4 && row[3].isNotBlank()) { "Malformed playlist CSV row" }
            PlaylistExchangeTrack(row[0], row[1], row[2], row[3])
        }.also { require(it.isNotEmpty()) { EMPTY_PLAYLIST_MESSAGE } }
    }

    private fun csvRows(raw: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var quoteClosed = false
        var index = 0
        while (index < raw.length) {
            val ch = raw[index++]
            if (quoted) {
                if (ch == '"') {
                    if (index < raw.length && raw[index] == '"') { field.append('"'); index++ }
                    else { quoted = false; quoteClosed = true }
                } else field.append(ch)
            } else when(ch) {
                '"' -> { require(field.isEmpty() && !quoteClosed); quoted = true }
                ',' -> { row += field.toString(); field.clear(); quoteClosed = false }
                '\r', '\n' -> {
                    if (ch == '\r' && index < raw.length && raw[index] == '\n') index++
                    row += field.toString(); field.clear(); quoteClosed = false
                    rows += row; row = mutableListOf()
                }
                else -> { require(!quoteClosed); field.append(ch) }
            }
        }
        require(!quoted) { "Unterminated playlist CSV field" }
        if (field.isNotEmpty() || row.isNotEmpty() || quoteClosed) { row += field.toString(); rows += row }
        require(rows.size <= 100_001)
        return rows
    }

    fun m3u(tracks: List<PlaylistExchangeTrack>): String {
        require(tracks.isNotEmpty())
        return buildString {
            append("#EXTM3U\n")
            tracks.forEach {
                require(it.uri.isNotBlank() && it.uri.none { ch -> ch == '\r' || ch == '\n' || ch == '\u0000' } &&
                    !it.uri.startsWith('#'))
                append("#EXTINF:${it.durationSeconds ?: -1},${it.artist.lineSafe()} - ${it.title.lineSafe()}\n")
                append(it.uri).append('\n')
            }
        }
    }
    fun readM3u(raw: String): List<PlaylistExchangeTrack> {
        bounded(raw)
        val lines = raw.removePrefix("\uFEFF").lines()
        require(lines.firstOrNull()?.trim() == "#EXTM3U") { "Missing M3U header" }
        var label = ""
        var duration: Int? = null
        val result = mutableListOf<PlaylistExchangeTrack>()
        for (line in lines.drop(1).map(String::trim).filter(String::isNotEmpty)) {
            if (line.startsWith("#EXTINF:")) {
                duration = line.substringAfter(':').substringBefore(',').toIntOrNull()?.takeIf { it >= 0 }
                label = line.substringAfter(',', "")
            } else if (!line.startsWith('#')) {
                require('\u0000' !in line)
                // The writer puts "artist - title" in the label, so it splits the way the baseline M3U reader does.
                val artist = label.substringBefore(" - ", "").trim()
                val title = label.substringAfter(" - ", label).trim()
                result += PlaylistExchangeTrack(title.ifBlank { line.substringAfterLast('/') }, artist, "", line, line, duration)
                label = ""; duration = null
            }
        }
        require(result.isNotEmpty()) { EMPTY_PLAYLIST_MESSAGE }
        require(result.size <= 100_000) { "Playlist exceeds the track limit" }
        return result
    }

    fun json(title: String, tracks: List<PlaylistExchangeTrack>, tags: Set<String> = emptySet()): String {
        require(tracks.isNotEmpty())
        return buildJsonObject {
            put("format", "archivetune-playlist"); put("version", 1); put("title", title)
            put("tags", JsonArray(tags.sorted().map(::JsonPrimitive)))
            put("tracks", JsonArray(tracks.map { track -> buildJsonObject {
                put("title", track.title); put("artist", track.artist); put("album", track.album)
                put("mediaId", track.mediaId); put("uri", track.uri)
                put("durationSeconds", track.durationSeconds?.let(::JsonPrimitive) ?: JsonNull)
            } }))
        }.toString()
    }
    fun readJson(raw: String): ImportedPlaylist {
        bounded(raw)
        val root = Json.parseToJsonElement(raw).jsonObject
        require(root["format"]?.jsonPrimitive?.content == "archivetune-playlist" &&
            root["version"]?.jsonPrimitive?.int == 1) { "Unsupported playlist JSON format" }
        val tracks = root.getValue("tracks").jsonArray
        require(tracks.isNotEmpty()) { EMPTY_PLAYLIST_MESSAGE }
        require(tracks.size <= 100_000) { "Playlist exceeds the track limit" }
        return ImportedPlaylist(root.getValue("title").jsonPrimitive.content, tracks.map {
            val track = it.jsonObject
            PlaylistExchangeTrack(track.string("title"), track.string("artist"), track.string("album"),
                track.string("mediaId").also { id -> require(id.isNotBlank()) }, track.string("uri"),
                track["durationSeconds"]?.jsonPrimitive?.intOrNull)
        }, root.getValue("tags").jsonArray.map { it.jsonPrimitive.content }.toSet())
    }
    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.let { require(it.isString); it.content }
    private fun String.lineSafe(): String = replace('\r', ' ').replace('\n', ' ')
    private fun bounded(raw: String) { require(raw.length <= MAX_CHARACTERS) { "Playlist exchange exceeds size limit" } }
}
