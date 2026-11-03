package moe.rukamori.archivetune.shared.data.news

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import moe.rukamori.archivetune.shared.domain.repository.ContentNews
import moe.rukamori.archivetune.shared.domain.repository.NewsRepository
import kotlin.coroutines.cancellation.CancellationException

/** The baked-in feed every host falls back to when its build does not name one. */
const val DEFAULT_NATUNE_NEWS_URL = "https://natune.pages.dev/api/news"

/** A feed or article answer that is unusable: a transport failure, a non-2xx status, an empty body or HTML. */
class NewsFeedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The first-party Natune announcements feed: `<base>/index.json` plus Markdown bodies at `<base>/content/<id>`.
 * A blank [baseUrl] disables the feed, so [list] answers empty and the hosts hide the News entry.
 *
 * A failed refresh keeps the last good index: [list] answers it again and throws only when no index has loaded yet.
 * Every request shares the engine of [client], so the host's proxy route and engine lifetime stay in charge.
 *
 * The private client's job is a child of that engine's job, so an open one keeps the engine from finishing its
 * shutdown. [close] releases it and never the borrowed engine; closing [client] releases it too, so a host whose
 * teardown never reaches [close] still lets its engine stop.
 */
class NatuneNewsClient(
    client: HttpClient,
    baseUrl: String,
    timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : NewsRepository {
    val baseUrl: String = baseUrl.trim().trimEnd('/')
    val enabled: Boolean get() = baseUrl.isNotEmpty()

    private val http = HttpClient(client.engine) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = timeoutMillis
            connectTimeoutMillis = timeoutMillis
            socketTimeoutMillis = timeoutMillis
        }
    }

    init {
        client.coroutineContext[Job]?.invokeOnCompletion { close() }
    }

    private val cache = MutableStateFlow<List<ContentNews>?>(null)

    /** The last index that loaded, newest first; null until one has. */
    val cached: StateFlow<List<ContentNews>?> = cache.asStateFlow()

    override suspend fun list(): List<ContentNews> {
        if (!enabled) return emptyList()
        return try {
            parseNewsIndex(fetch("$baseUrl/index.json", "News feed request failed")).also { cache.value = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            cache.value ?: throw (failure as? NewsFeedException ?: NewsFeedException("News feed request failed", failure))
        }
    }

    override suspend fun content(id: String): String {
        if (!enabled) return ""
        if (!isSafeNewsId(id)) throw NewsFeedException("News article id is not a content file name")
        return try {
            fetch("$baseUrl/content/$id", "News article unavailable")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: NewsFeedException) {
            throw failure
        } catch (failure: Exception) {
            throw NewsFeedException("News article unavailable", failure)
        }
    }

    fun cachedItem(id: String): ContentNews? = cache.value?.firstOrNull { it.id == id }

    internal val isOpen: Boolean get() = http.isActive

    /** Releases this feed's own client; the borrowed engine and client stay open. Later requests fail. */
    fun close() {
        http.close()
    }

    private suspend fun fetch(url: String, failure: String): String {
        val response = http.get(url) {
            headers {
                append(HttpHeaders.CacheControl, "no-cache, no-store, must-revalidate")
                append(HttpHeaders.Pragma, "no-cache")
                append(HttpHeaders.Expires, "0")
            }
        }
        val text = response.bodyAsText()
        invalidNewsResponse(response.status.value, response.headers[HttpHeaders.ContentType], text)
            ?.let { reason -> throw NewsFeedException("$failure: $reason") }
        return text
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS: Long = 15_000L
    }
}

/** The id doubles as the content file name; it must stay inside `content/` with no traversal. */
fun isSafeNewsId(id: String): Boolean = SAFE_NEWS_ID.matches(id) && id.any { it != '.' }

private val SAFE_NEWS_ID = Regex("[A-Za-z0-9._-]+")

/**
 * The reason one feed or article answer is unusable, or null for a usable payload: 2xx, non-blank and not HTML.
 * A static host can answer a missing path with HTTP 200 and the site shell, so an HTML-looking reply must surface as
 * a failure (the retry UI) instead of rendering as an article or replacing the cached index.
 */
fun invalidNewsResponse(statusCode: Int, contentType: String?, body: String?): String? {
    if (statusCode !in 200..299) return "HTTP $statusCode"
    if (body.isNullOrBlank()) return "empty body"
    val trimmed = body.trimStart()
    if (trimmed.startsWith("<!doctype", ignoreCase = true) || trimmed.startsWith("<html", ignoreCase = true)) {
        return "HTML shell, not article content"
    }
    if (contentType != null && contentType.trimStart().startsWith("text/html", ignoreCase = true)) {
        return "HTML content-type"
    }
    return null
}

private val newsJson = Json { ignoreUnknownKeys = true }

/**
 * Parses `index.json` newest first. The wire names are the Android `NewsItem` ones. `Date` is epoch seconds as a string
 * or a number, `ImageURL` a list or one string. An entry without a title is skipped rather than failing the feed, and
 * anything other than a JSON array fails the parse.
 */
fun parseNewsIndex(text: String): List<ContentNews> {
    val root = try {
        newsJson.parseToJsonElement(text)
    } catch (failure: Exception) {
        throw NewsFeedException("News feed is not JSON", failure)
    }
    val entries = root as? JsonArray ?: throw NewsFeedException("News feed is not a list")
    return entries.mapNotNull { (it as? JsonObject)?.toContentNews() }.sortedByDescending { it.timestamp }
}

private fun JsonObject.toContentNews(): ContentNews? {
    val title = string("Title")?.takeIf { it.isNotBlank() } ?: return null
    return ContentNews(
        id = string("id").orEmpty(),
        title = title,
        description = string("Description").orEmpty(),
        imageUrls = imageUrls(get("ImageURL")),
        important = (get("Important") as? JsonPrimitive)?.booleanOrNull ?: false,
        author = string("Author").orEmpty(),
        timestamp = string("Date")?.trim()?.toLongOrNull() ?: 0L,
    )
}

private fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull

private fun imageUrls(element: JsonElement?): List<String> = when (element) {
    is JsonArray -> element.mapNotNull { item ->
        (item as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }
    is JsonNull -> emptyList()
    is JsonPrimitive -> element.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let(::listOf).orEmpty()
    else -> emptyList()
}

/** The unread dot: some item is newer than the last feed the user opened. Timestamps are the feed's epoch seconds. */
fun hasUnreadNews(items: List<ContentNews>, lastReadTimestamp: Long): Boolean =
    items.isNotEmpty() && items.maxOf { it.timestamp } > lastReadTimestamp
