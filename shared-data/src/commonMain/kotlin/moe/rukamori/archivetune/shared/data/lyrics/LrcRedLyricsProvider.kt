package moe.rukamori.archivetune.shared.data.lyrics

import io.ktor.client.HttpClient
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsPayload
import kotlin.math.abs

/**
 * Port of the Android lrc.red provider: ISRC-keyed TTML document fetch with a search fallback,
 * matched on normalized title/version/artist and a three-second duration window.
 */
class LrcRedLyricsProvider(
    private val client: HttpClient,
) : LyricsProvider {
    override val name: String = "lrc.red"

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
        isrc: String?,
    ): Result<String> =
        suspendRunCatching {
            fetchLyrics(title, artist, durationSec, isrc)
                ?: throw IllegalStateException("No lrc.red lyrics for $title — $artist")
        }

    private suspend fun fetchLyrics(
        title: String,
        artist: String,
        duration: Int,
        isrc: String?,
    ): String? {
        val known = isrc?.let(::documentUrl)
        if (known != null) {
            document(known)?.let { return it }
        }

        val hit = search(title.forLyricsSearch(), artist.artistForLyricsSearch(), duration) ?: return null
        val found = hit.isrc?.let(::documentUrl)?.takeIf { it != known } ?: return null
        return document(found)
    }

    private suspend fun document(url: String): String? =
        LyricsHttp.getWithStatus(client, url).body?.let(LyricsPayload::toLyrics)

    private suspend fun search(
        title: String,
        artist: String,
        duration: Int,
    ): Hit? {
        val query = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ").trim()
        if (query.isEmpty()) return null
        val url = "https://lrc.red/search.json?q=${query.encodeURLParameter()}"
        val body = LyricsHttp.getWithStatus(client, url).body ?: return null
        val root = LyricsPayload.parseJson(body) as? JsonObject ?: return null
        val hits = root["hits"] as? JsonArray ?: return null
        val parsed = hits.mapNotNull { element ->
            val hit = element as? JsonObject ?: return@mapNotNull null
            Hit(
                isrc = (hit["isrc"] as? JsonPrimitive)?.content,
                title = (hit["title"] as? JsonPrimitive)?.content,
                artist = (hit["artist"] as? JsonPrimitive)?.content,
                duration = (hit["duration"] as? JsonPrimitive)?.doubleOrNull,
            )
        }
        return best(parsed, title, artist, duration)
    }

    private fun best(
        hits: List<Hit>,
        title: String,
        artist: String,
        duration: Int,
    ): Hit? {
        val wantedTitle = coreOf(title)
        val wantedVersion = versionOf(title)
        val wantedArtists = artistsOf(artist)
        val seconds = duration.toDouble()

        fun distance(hit: Hit): Double =
            if (duration > 0 && hit.duration != null) abs(hit.duration - seconds) else 0.0

        return hits
            .filter { hit ->
                val name = hit.title ?: return@filter false
                hit.isrc?.let { documentUrl(it) } != null &&
                    coreOf(name) == wantedTitle &&
                    versionOf(name) == wantedVersion &&
                    (wantedArtists.isEmpty() || artistsOf(hit.artist.orEmpty()).any { it in wantedArtists }) &&
                    distance(hit) <= DURATION_TOLERANCE_SECONDS
            }.minByOrNull(::distance)
    }

    private fun coreOf(title: String): String =
        normalized(title.replace(BRACKETED, " ").substringBefore(" - "))
            .ifEmpty { normalized(title) }

    private fun versionOf(title: String): Set<String> {
        val extras =
            BRACKETED.findAll(title).joinToString(" ") { it.value } +
                " " + title.substringAfter(" - ", "")
        return normalized(extras).split(' ').filter { it in VERSION_WORDS }.toSet()
    }

    private fun artistsOf(artist: String): Set<String> =
        artist.split(ARTIST_SEPARATORS).map(::normalized).filter { it.isNotEmpty() }.toSet()

    private fun normalized(value: String): String =
        stripCombiningMarks(canonicalNfd(value))
            .lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .replace(WHITESPACE, " ")
            .trim()

    private fun documentUrl(isrc: String): String? =
        isrc.trim().uppercase().takeIf { ISRC.matches(it) }?.let { "$BASE_URL/s/$it.ttml" }

    private data class Hit(
        val isrc: String?,
        val title: String?,
        val artist: String?,
        val duration: Double?,
    )

    private companion object {
        const val DURATION_TOLERANCE_SECONDS = 3.0
        const val BASE_URL = "https://lrc.red"

        val ISRC = Regex("""[A-Z]{2}[A-Z0-9]{3}\d{7}""")
        val BRACKETED = Regex("""[(\[][^)\]]*[)\]]""")
        val WHITESPACE = Regex("""\s+""")
        val ARTIST_SEPARATORS =
            Regex(
                """\s*(?:,|&|;|/|\s+and\s+|\s+x\s+|\s+with\s+|\s+feat\.?\s+|\s+ft\.?\s+)\s*""",
                RegexOption.IGNORE_CASE,
            )
        val VERSION_WORDS =
            setOf(
                "live", "remix", "remixed", "mix", "acoustic", "unplugged", "instrumental",
                "karaoke", "cappella", "acapella", "demo", "edit", "version", "cover",
                "sped", "slowed", "reverb", "nightcore", "lofi", "orchestral", "extended",
            )
    }
}

/** Combining marks (Unicode category M*), stripped after NFD as the Android `COMBINING_MARKS` regex did. */
internal fun stripCombiningMarks(value: String): String =
    value.filterNot { char ->
        char.category == CharCategory.NON_SPACING_MARK ||
            char.category == CharCategory.COMBINING_SPACING_MARK ||
            char.category == CharCategory.ENCLOSING_MARK
    }
