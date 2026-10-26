package moe.rukamori.archivetune.shared.data.lyrics

import io.ktor.client.HttpClient
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsPayload
import kotlin.math.abs
import kotlin.time.TimeSource

/**
 * Port of the Android Megalobiz provider: bearer-keyed JSON search scored against title/artist/length,
 * with the app key recovered from the site's /lrc page scripts when the default is rejected.
 */
class MegalobizLyricsProvider(
    private val client: HttpClient,
) : LyricsProvider {
    override val name: String = "Megalobiz"

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
        isrc: String?,
    ): Result<String> =
        suspendRunCatching {
            fetchLyrics(title.forLyricsSearch(), artist.artistForLyricsSearch(), durationSec)
                ?: throw IllegalStateException("No Megalobiz lyrics for $title — $artist")
        }

    private suspend fun fetchLyrics(
        title: String,
        artist: String,
        duration: Int,
    ): String? {
        val query = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ").trim()
        if (query.isEmpty()) return null

        val key = appKey()
        if (key != null) {
            when (val outcome = searchAndConvert(query, title, artist, duration, key)) {
                is SearchOutcome.Found -> return outcome.lrc
                SearchOutcome.Miss -> return null
                // Only a rejected key is worth the scrape: it downloads the /lrc page and up
                // to MAX_SCRAPED_CHUNKS scripts, which an ordinary no-match must not trigger.
                SearchOutcome.Unauthorized -> Unit
            }
        }

        val fresh = refreshAppKey() ?: return null
        if (fresh == key) return null
        return (searchAndConvert(query, title, artist, duration, fresh) as? SearchOutcome.Found)?.lrc
    }

    private sealed interface SearchOutcome {
        data class Found(val lrc: String) : SearchOutcome
        data object Miss : SearchOutcome
        data object Unauthorized : SearchOutcome
    }

    private suspend fun searchAndConvert(
        query: String,
        title: String,
        artist: String,
        duration: Int,
        key: String,
    ): SearchOutcome {
        val url = "$API_BASE/lrcs?search=${query.encodeURLParameter()}&pageSize=$PAGE_SIZE"
        val response =
            getOrNull(
                url,
                mapOf("Authorization" to "Bearer $key", "Accept" to "application/json"),
            ) ?: return SearchOutcome.Miss
        if (response.status == 401 || response.status == 403) return SearchOutcome.Unauthorized
        val body = response.body ?: return SearchOutcome.Miss
        val root = LyricsPayload.parseJson(body) as? JsonObject ?: return SearchOutcome.Miss
        val models = root["models"] as? JsonArray ?: return SearchOutcome.Miss
        if (models.isEmpty()) return SearchOutcome.Miss

        val sheets =
            models.mapNotNull { element ->
                val sheet = element as? JsonObject ?: return@mapNotNull null
                val lines = (sheet["content"] as? JsonObject)?.get("lines") as? JsonArray
                    ?: return@mapNotNull null
                Sheet(
                    title = (sheet["title"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    artist = (sheet["artist"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    length = (sheet["length"] as? JsonPrimitive)?.doubleOrNull,
                    lines = lines,
                )
            }
        val best = best(sheets, title, artist, duration) ?: return SearchOutcome.Miss
        return toLrc(best.lines)?.let { SearchOutcome.Found(it) } ?: SearchOutcome.Miss
    }

    private fun best(
        sheets: List<Sheet>,
        title: String,
        artist: String,
        duration: Int,
    ): Sheet? {
        val wantedTitle = normalized(title)
        val wantedArtists = artist.split(ARTIST_SEPARATORS).map(::normalized).filter { it.isNotEmpty() }

        fun score(sheet: Sheet): Int {
            val haystack = normalized("${sheet.title} ${sheet.artist}")
            if (wantedTitle.isEmpty() || haystack.isEmpty()) return 0

            var score = 0
            when {
                normalized(sheet.title) == wantedTitle -> score += 20
                haystack.contains(wantedTitle) -> score += 10
                else -> return 0
            }
            if (wantedArtists.isNotEmpty()) {
                when {
                    wantedArtists.any { it.length > 2 && haystack.contains(it) } -> score += 15
                    else -> score -= 10
                }
            }
            if (duration > 0 && sheet.length != null) {
                val distance = abs(sheet.length - duration)
                score +=
                    when {
                        distance <= 3.0 -> 10
                        distance <= 10.0 -> 5
                        else -> 0
                    }
            }
            return score
        }

        return sheets.map { it to score(it) }
            .filter { (_, score) -> score >= MINIMUM_MATCH_SCORE }
            .maxByOrNull { (_, score) -> score }
            ?.first
    }

    private fun toLrc(lines: List<JsonElement>): String? {
        val builder = StringBuilder()
        lines.forEach { element ->
            val line = element as? JsonObject ?: return@forEach
            val time = (line["time"] as? JsonPrimitive)?.longOrNull ?: return@forEach
            val text = (line["text"] as? JsonPrimitive)?.contentOrNull ?: ""
            builder
                .append('[')
                .append(formatStamp(time))
                .append(']')
                .append(text)
                .append('\n')
        }
        val lrc = builder.toString()
        return lrc.takeIf { LRC_LINE.containsMatchIn(it) }
    }

    private fun formatStamp(ms: Long): String {
        val totalSeconds = ms / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        val centis = (ms % 1000L) / 10L
        return "${minutes.pad2()}:${seconds.pad2()}.${centis.pad2()}"
    }

    private fun Long.pad2(): String = toString().padStart(2, '0')

    private fun normalized(value: String): String =
        value
            .lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .replace(WHITESPACE, " ")
            .trim()

    private suspend fun appKey(): String? = cachedAppKey ?: appKeyMutex.withLock {
        cachedAppKey ?: DEFAULT_APP_KEY.also { cachedAppKey = it }
    }

    private suspend fun refreshAppKey(): String? {
        val now = TimeSource.Monotonic.markNow()
        lastScrapeAttempt?.let { if (now - it < SCRAPE_COOLDOWN) return null }
        lastScrapeAttempt = now

        return appKeyMutex.withLock {
            val page = getOrNull(SITE_LRC_PAGE)?.body ?: return@withLock null
            val chunks = SCRIPT_PATH.findAll(page).map { it.value }.distinct().take(MAX_SCRAPED_CHUNKS)
            for (path in chunks) {
                val script = getOrNull("$SITE_HOST$path")?.body ?: continue
                if (!script.contains(API_HOST)) continue
                val key = APP_KEY.find(script)?.value ?: continue
                cachedAppKey = key
                return@withLock key
            }
            null
        }
    }

    private data class Sheet(
        val title: String,
        val artist: String,
        val length: Double?,
        val lines: List<JsonElement>,
    )

    /** Transport errors read as a miss, like the Android helper's null response. */
    private suspend fun getOrNull(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): LyricsHttpResponse? = runCatching { LyricsHttp.getWithStatus(client, url, headers) }.getOrNull()

    private companion object {
        const val API_HOST = "api.megalobiz.com"
        const val API_BASE = "https://$API_HOST/api/v1"
        const val SITE_HOST = "https://megalobiz.com"
        const val SITE_LRC_PAGE = "$SITE_HOST/lrc"

        const val PAGE_SIZE = 8
        const val MINIMUM_MATCH_SCORE = 10

        const val DEFAULT_APP_KEY =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
                "eyJhcHBfaWQiOjEsImlhdCI6MTc5MDQ1NzA4MH0." +
                "2bIxON27e030cTRsC8GOTafnd5wU_LjnxWI-iEUfYw0"

        val SCRAPE_COOLDOWN = kotlin.time.Duration.parse("5m")
        const val MAX_SCRAPED_CHUNKS = 8

        val WHITESPACE = Regex("""\s+""")
        val ARTIST_SEPARATORS =
            Regex(
                """\s*(?:,|&|;|/|\s+and\s+|\s+x\s+|\s+with\s+|\s+feat\.?\s+|\s+ft\.?\s+)\s*""",
                RegexOption.IGNORE_CASE,
            )
        val LRC_LINE = Regex("""\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?]""")

        val SCRIPT_PATH = Regex("""/_next/static/chunks/[A-Za-z0-9._-]+\.js""")
        val APP_KEY = Regex("""eyJ[A-Za-z0-9_-]+\.eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""")

        // Key cache, mutex and scrape cooldown live on the companion — as the Android object
        // singleton did — so recreating the provider on a settings change keeps both.
        val appKeyMutex = Mutex()

        @Volatile
        var lastScrapeAttempt: TimeSource.Monotonic.ValueTimeMark? = null

        @Volatile
        var cachedAppKey: String? = null
    }
}
