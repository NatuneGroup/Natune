/*
 * Adapted from BitChord (GPL-3.0) https://github.com/kushagrasinghx/BitChord
 * (shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsHttp.kt) —
 * original re-implementation for ArchiveTune.
 */
package moe.rukamori.archivetune.shared.data.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Lenient parsing matches the Android providers. Providers never build an engine: the host
 * injects the HttpClient, so timeouts, proxies and content encoding are the host's decision.
 */
internal val LyricsJson: Json =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

/** An answered request. [body] is read only for a 2xx [status], as the Android helper did. */
internal class LyricsHttpResponse(
    val status: Int,
    val body: String?,
) {
    fun requireBody(): String = body ?: throw LyricsHttpStatusException(status)
}

/** Raised when a provider needs a body and the server answered with a non-2xx status. */
internal class LyricsHttpStatusException(
    val status: Int,
) : IllegalStateException("Lyrics request answered with HTTP $status")

internal object LyricsHttp {
    const val USER_AGENT = "ArchiveTune (https://github.com/rukamori)"

    /**
     * Sends a GET. Unlike the Android helper this does not turn transport errors into null:
     * the caller wraps the call in suspendRunCatching, so the failure reason is kept.
     * [defaultUserAgent] is off for providers whose Android client sent no User-Agent at all.
     */
    suspend fun getWithStatus(
        client: HttpClient,
        url: String,
        headers: Map<String, String> = emptyMap(),
        defaultUserAgent: Boolean = true,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): LyricsHttpResponse {
        val response =
            client.get(url) {
                this.configure()
                var sentAgent = false
                headers.forEach { (name, value) ->
                    header(name, value)
                    if (name.equals(HttpHeaders.UserAgent, ignoreCase = true)) sentAgent = true
                }
                if (defaultUserAgent && !sentAgent) header(HttpHeaders.UserAgent, USER_AGENT)
            }
        val ok = response.status.isSuccess()
        return LyricsHttpResponse(
            status = response.status.value,
            body = if (ok) response.bodyAsText() else null,
        )
    }

    /** GET then decode the body with [LyricsJson]; a non-2xx answer throws [LyricsHttpStatusException]. */
    internal suspend inline fun <reified T> getJson(
        client: HttpClient,
        url: String,
        defaultUserAgent: Boolean = true,
        noinline configure: HttpRequestBuilder.() -> Unit = {},
    ): T {
        val response = getWithStatus(client, url, defaultUserAgent = defaultUserAgent, configure = configure)
        return LyricsJson.decodeFromString<T>(response.requireBody())
    }
}

/**
 * Normalises a track title for a provider search: strips feature and video credits such as
 * "(feat. X)" or "(Official Video)", then collapses whitespace. Falls back to the trimmed input
 * when stripping would leave nothing, so a title made only of credits still searches.
 */
internal fun String.forLyricsSearch(): String {
    var name = this
    CREDITS.forEach { pattern -> name = pattern.replace(name, " ") }
    return name
        .replace(SEARCH_WHITESPACE, " ")
        .trim()
        .trimEnd(',', '-', '–', '—')
        .trim()
        .ifBlank { trim() }
}

/** YouTube Music appends " - Topic" to auto-generated artist channels; providers search the bare name. */
internal fun String.artistForLyricsSearch(): String = removeSuffix(" - Topic").trim().ifBlank { trim() }

private val SEARCH_WHITESPACE = Regex("""\s+""")

private val CREDITS =
    listOf(
        Regex("""\s*[(\[]\s*(feat|ft|featuring|with)\b[^)\]]*[)\]]""", RegexOption.IGNORE_CASE),
        Regex("""\s+(feat|ft|featuring)\.?\s+.*$""", RegexOption.IGNORE_CASE),
        Regex(
            """\s*[(\[]\s*(official\s*)?(music\s*)?""" +
                """(video|audio|visuali[sz]er|lyrics?\s*video|lyrics?|m/?v|hd|hq|4k|full\s*song)""" +
                """\s*[)\]]""",
            RegexOption.IGNORE_CASE,
        ),
        Regex("""\s*[(\[]\s*official\s*[)\]]""", RegexOption.IGNORE_CASE),
    )
