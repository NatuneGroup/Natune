/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.playlistimport

import moe.rukamori.archivetune.shared.data.persistence.PlaylistExchange
import moe.rukamori.archivetune.shared.data.persistence.PlaylistExchangeTrack

/**
 * Lenient readers for the CSV and M3U files a user brings in, accepting what the baseline import accepted. They are
 * separate from [PlaylistExchange]'s strict round-trip readers, which describe exactly the files this app writes.
 *
 * A CSV file may come from any spreadsheet or another app. Its title and artist columns are found by header name in any
 * order, and a file with neither header is read by position, title first. An M3U file is read from its #EXTINF labels.
 * The location line after a label is kept only as an identity, so the owner can find a stable watch link. Neither
 * reader fetches anything.
 */
internal object PlaylistImportReaders {
    /** The reason a reader gives for a playlist longer than it accepts. The import owner reports it as unreadable. */
    const val TRACK_LIMIT_MESSAGE = "Playlist exceeds the track limit"
    private const val BYTE_ORDER_MARK = '\uFEFF'
    private const val MAX_TRACKS = 100_000
    private const val EXTINF = "#EXTINF:"
    private const val LABEL_SEPARATOR = " - "
    private val titleHeaders = setOf("title", "tracktitle", "songtitle")
    private val artistHeaders = setOf("artist", "artists", "artistname")
    private const val ALBUM_HEADER = "album"
    private const val MEDIA_ID_HEADER = "mediaid"
    private val csvArtistSeparators = charArrayOf(';', '|')
    private val m3uArtistSeparators = charArrayOf(';')

    /**
     * Reads a CSV playlist. Blank records are dropped and a row with no title is skipped. A file that yields no song is
     * refused with the shared empty-playlist message.
     */
    fun csv(raw: String): List<PlaylistExchangeTrack> {
        val records = csvRecords(raw.removePrefix(BYTE_ORDER_MARK.toString()))
        val header = records.firstOrNull()?.map { normalizeHeader(it) }.orEmpty()
        val titleIndex = header.indexOfFirst { it in titleHeaders }
        val artistIndex = header.indexOfFirst { it in artistHeaders }
        val hasHeader = titleIndex >= 0 && artistIndex >= 0
        val titleColumn = if (hasHeader) titleIndex else 0
        val artistColumn = if (hasHeader) artistIndex else 1
        val albumColumn = if (hasHeader) header.indexOf(ALBUM_HEADER) else -1
        val mediaIdColumn = if (hasHeader) header.indexOf(MEDIA_ID_HEADER) else -1
        val dataRecords = if (hasHeader) records.drop(1) else records
        val tracks = dataRecords.mapNotNull { record ->
            val title = record.column(titleColumn).trimStart(BYTE_ORDER_MARK).trim()
            if (title.isEmpty()) {
                null
            } else {
                PlaylistExchangeTrack(
                    title = title,
                    artist = joinArtists(record.column(artistColumn), csvArtistSeparators),
                    album = record.column(albumColumn),
                    mediaId = record.column(mediaIdColumn),
                )
            }
        }
        return validated(tracks)
    }

    /**
     * Reads an M3U playlist from its #EXTINF labels. The file must open with #EXTM3U. A label names its song as
     * "artist - title", as the writer and the baseline both do. A location line that follows a label is kept as its
     * uri. A location with no label before it names no song, so it is skipped, as the baseline skipped it.
     */
    fun m3u(raw: String): List<PlaylistExchangeTrack> {
        val lines = raw.removePrefix(BYTE_ORDER_MARK.toString()).lines()
        require(lines.firstOrNull()?.trim()?.startsWith("#EXTM3U") == true) { "Missing M3U header" }
        val tracks = mutableListOf<PlaylistExchangeTrack>()
        var label: String? = null
        var duration: Int? = null
        for (line in lines.drop(1).map(String::trim)) {
            when {
                line.isEmpty() -> Unit
                line.startsWith(EXTINF) -> {
                    label?.let { tracks += m3uTrack(it, duration, uri = "") }
                    duration = line.removePrefix(EXTINF).substringBefore(',').trim().toIntOrNull()?.takeIf { it >= 0 }
                    label = line.substringAfter(',', "")
                }
                line.startsWith('#') -> Unit
                label != null -> {
                    tracks += m3uTrack(label.orEmpty(), duration, uri = line)
                    label = null
                    duration = null
                }
                else -> Unit
            }
        }
        label?.let { tracks += m3uTrack(it, duration, uri = "") }
        return validated(tracks)
    }

    private fun m3uTrack(label: String, duration: Int?, uri: String): PlaylistExchangeTrack {
        // Without the separator the whole label is the title and no artist is named, so the search is by title alone.
        val separator = label.indexOf(LABEL_SEPARATOR)
        val artist = if (separator < 0) "" else label.substring(0, separator)
        val title = if (separator < 0) label else label.substring(separator + LABEL_SEPARATOR.length)
        return PlaylistExchangeTrack(
            title = title.trim(),
            artist = joinArtists(artist, m3uArtistSeparators),
            album = "",
            mediaId = "",
            uri = uri,
            durationSeconds = duration,
        )
    }

    private fun validated(tracks: List<PlaylistExchangeTrack>): List<PlaylistExchangeTrack> {
        require(tracks.size <= MAX_TRACKS) { TRACK_LIMIT_MESSAGE }
        require(tracks.isNotEmpty()) { PlaylistExchange.EMPTY_PLAYLIST_MESSAGE }
        return tracks
    }

    /** The header cell as the baseline matched it: trimmed, lower case, and without spaces, underscores or hyphens. */
    private fun normalizeHeader(cell: String): String =
        cell.trim().trimStart(BYTE_ORDER_MARK).lowercase().replace(" ", "").replace("_", "").replace("-", "")

    /** The trimmed cell at [index], or an empty string when the column is absent from the record. */
    private fun List<String>.column(index: Int): String = if (index < 0) "" else getOrNull(index).orEmpty().trim()

    /** The non-blank names in [raw], split on [separators] and rejoined on ';' so one artist field has one form. */
    private fun joinArtists(raw: String, separators: CharArray): String =
        raw.split(*separators).map(String::trim).filter(String::isNotEmpty).joinToString(";")

    /**
     * The baseline record reader. A quoted field may hold commas and line breaks, a doubled quote is one quote, and a
     * record with no visible text is dropped. A quote that opens mid-field is kept as text, as the baseline kept it.
     */
    private fun csvRecords(raw: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var record = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var index = 0

        fun endField() {
            record += field.toString()
            field.setLength(0)
        }

        fun endRecord() {
            endField()
            if (record.any(String::isNotBlank)) records += record
            record = mutableListOf()
        }

        while (index < raw.length) {
            val ch = raw[index++]
            when {
                ch == '"' && inQuotes -> {
                    if (index < raw.length && raw[index] == '"') {
                        field.append('"')
                        index++
                    } else {
                        inQuotes = false
                    }
                }
                ch == '"' -> {
                    if (field.isEmpty()) {
                        inQuotes = true
                    } else {
                        field.append('"')
                    }
                }
                ch == ',' && !inQuotes -> endField()
                ch == '\n' && !inQuotes -> endRecord()
                ch == '\r' && !inQuotes -> {
                    if (index < raw.length && raw[index] == '\n') index++
                    endRecord()
                }
                else -> field.append(ch)
            }
        }
        if (field.isNotEmpty() || record.isNotEmpty()) endRecord()
        return records
    }
}
