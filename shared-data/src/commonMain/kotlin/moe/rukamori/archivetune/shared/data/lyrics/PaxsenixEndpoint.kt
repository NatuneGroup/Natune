package moe.rukamori.archivetune.shared.data.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json

/**
 * Connection settings the Android app pushed into the Paxsenix singleton. Here they belong to the
 * instance, so two providers can point at different mirrors without sharing state.
 *
 * [bearerValue] is sent as `Authorization: Bearer` on Paxsenix requests when it is non-blank. The
 * Android app fell back to a built-in value when none was set; that fallback is not carried here,
 * so the host supplies its own value or none.
 *
 * [storefront] is the Apple Music storefront. Android read it from the device locale, which common
 * code cannot do, so the host passes it in; anything other than a two-letter code becomes "us".
 */
class PaxsenixSettings(
    val baseUrl: String = DEFAULT_BASE_URL,
    val bearerValue: String? = null,
    val userAgent: String = "ArchiveTune",
    val storefront: String = "us",
) {
    // The bearer value must not reach logs or crash reports that print the settings object.
    override fun toString(): String = "PaxsenixSettings(baseUrl=$baseUrl, userAgent=$userAgent, storefront=$storefront)"

    companion object {
        const val DEFAULT_BASE_URL = "https://lyrics.paxsenix.org/"
    }
}

/**
 * Paxsenix wire format: lenient, ignores unknown fields and treats absent nulls as defaults, which
 * is the Android client's ContentNegotiation configuration.
 */
internal val PaxsenixJson: Json =
    Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

/**
 * One Paxsenix path. Every branch of the Android client treats a non-200 answer as a miss, and reads
 * the body only for that exact status, so this does the same. Other 2xx answers are misses too.
 */
internal class PaxsenixEndpoint(
    private val client: HttpClient,
    settings: PaxsenixSettings,
) {
    private val baseUrl: String = normalizeEndpoint(settings.baseUrl)
    private val requestHeaders: Map<String, String> =
        buildMap {
            put(HttpHeaders.UserAgent, settings.userAgent)
            put(HttpHeaders.Accept, "application/json, text/plain, */*")
            put(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9")
            settings.bearerValue?.trim()?.takeIf { it.isNotEmpty() }?.let { put(HttpHeaders.Authorization, "Bearer $it") }
        }

    /** The two-letter storefront the Apple Music search uses, lowercase. */
    val storefront: String = normalizeStorefront(settings.storefront)

    suspend fun get(
        path: String,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): LyricsHttpResponse = paxsenixGet(client, baseUrl + path, requestHeaders, configure)
}

/**
 * Sends a GET and reads the body only for an exact 200. [prepareGet] with `execute` releases the
 * connection when the block returns, including for the non-200 answers whose bodies are never read.
 */
internal suspend fun paxsenixGet(
    client: HttpClient,
    url: String,
    requestHeaders: Map<String, String>,
    configure: HttpRequestBuilder.() -> Unit = {},
): LyricsHttpResponse =
    client
        .prepareGet(url) {
            this.configure()
            requestHeaders.forEach { (name, value) -> header(name, value) }
        }.execute { response ->
            val status = response.status.value
            LyricsHttpResponse(status = status, body = if (status == OK_STATUS) response.bodyAsText() else null)
        }

internal const val OK_STATUS = 200

/** Blank input means the default endpoint; a missing trailing slash is added so relative paths join. */
internal fun normalizeEndpoint(raw: String): String {
    val trimmed = raw.trim()
    return when {
        trimmed.isBlank() -> PaxsenixSettings.DEFAULT_BASE_URL
        trimmed.endsWith("/") -> trimmed
        else -> "$trimmed/"
    }
}

internal fun normalizeStorefront(raw: String): String {
    val country = raw.trim()
    return if (country.length == 2) country.lowercase() else "us"
}
