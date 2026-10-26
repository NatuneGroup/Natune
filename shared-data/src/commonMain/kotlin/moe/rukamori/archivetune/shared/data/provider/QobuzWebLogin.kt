package moe.rukamori.archivetune.shared.data.provider

import io.ktor.client.HttpClient
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import moe.rukamori.archivetune.shared.data.bytesource.Md5
import moe.rukamori.archivetune.shared.domain.settings.QobuzTokenEntry
import moe.rukamori.archivetune.shared.domain.settings.SourceHealthStatus

/**
 * Turns what the Qobuz web player's bundle scripts yield into an ordered list of app_secret
 * candidates, exactly as the Android side's [QobuzBundleSecrets] did.
 *
 * The bundle never labels the secret unambiguously, and it is not the only 32-character hex string
 * in there — chunk hashes and asset digests look identical. So nothing here decides which candidate
 * is real; the caller verifies each against the API in order and keeps the first that signs
 * requests. That ordering is all this object owns, and it is why the result is a list rather than a
 * set: a secret found beside the app id is worth trying before an anonymous hex string.
 *
 * Two bundle generations are covered. The current one writes the secret next to the app id, so the
 * scan keys on it. The older one splits it across a `seed` and a timezone-keyed `info`/`extras`
 * pair that concatenate into base64 with 44 trailing characters of filler.
 */
object QobuzBundleSecrets {
    const val DEFAULT_LIMIT = 12

    private const val LEGACY_PADDING_LENGTH = 44
    private val HexSecret = Regex("^[a-f0-9]{32}$")

    /** The extraction patterns the injected hook applied to every bundle script. */
    private val KeyedSecret = Regex("app_?[sS]ecret\"?\\s*[:=]\\s*\"([a-f0-9]{32})\"")
    private val LegacySeed = Regex("[a-z]\\.initialSeed\\(\"([\\w=]+)\",\\s*window\\.utimezone\\.[a-z]+\\)")
    private val LegacyFragment = Regex("name:\"[^\"]*/[A-Za-z_]+\",info:\"([\\w=]+)\",extras:\"([\\w=]+)\"")
    private val BareHex = Regex("[^a-fA-F0-9]([a-f0-9]{32})[^a-fA-F0-9]")

    /**
     * One candidate per line, because the hook that produced this payload ran inside a WebView:
     * ```
     * keyed:<32 hex>
     * hex:<32 hex>
     * legacy:<seed>:<info>:<extras>
     * ```
     * @param limit how many candidates the caller is willing to verify over the network.
     */
    fun candidates(
        payload: String,
        limit: Int = DEFAULT_LIMIT,
    ): List<String> {
        val keyed = mutableListOf<String>()
        val legacy = mutableListOf<String>()
        val bare = mutableListOf<String>()

        payload.lineSequence().forEach { line ->
            val parts = line.trim().split(':')
            when (parts.firstOrNull()) {
                "keyed" -> parts.getOrNull(1)?.takeIf(HexSecret::matches)?.let { keyed += it }
                "hex" -> parts.getOrNull(1)?.takeIf(HexSecret::matches)?.let { bare += it }
                "legacy" ->
                    if (parts.size >= 4) {
                        decodeLegacy(seed = parts[1], info = parts[2], extras = parts[3])
                            ?.let { legacy += it }
                    }
            }
        }

        return (keyed + legacy + bare).distinct().take(limit)
    }

    /**
     * Reassembles an older bundle's split secret. The three fragments concatenate into one base64
     * string whose last [LEGACY_PADDING_LENGTH] characters are filler.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun decodeLegacy(
        seed: String,
        info: String,
        extras: String,
    ): String? {
        val joined = seed.trim() + info.trim() + extras.trim()
        if (joined.length <= LEGACY_PADDING_LENGTH) return null
        val decoded =
            runCatching { Base64.Default.decode(joined.dropLast(LEGACY_PADDING_LENGTH)).decodeToString() }
                .getOrNull()
                ?: return null
        return decoded.takeIf(HexSecret::matches)
    }

    /**
     * The same scan the hook ran against each fetched bundle script: keyed secrets first, then every
     * legacy seed crossed with every timezone fragment, then bare hex strings not already reported.
     * The result is the payload [candidates] consumes, so the two paths share the ordering rules.
     */
    fun extractPayload(script: String): String {
        val out = StringBuilder()
        val seen = mutableSetOf<String>()
        KeyedSecret.findAll(script).forEach { match ->
            if (seen.add(match.groupValues[1])) out.append("keyed:").append(match.groupValues[1]).append('\n')
        }
        val seeds = LegacySeed.findAll(script).map { it.groupValues[1] }.toList()
        val fragments = LegacyFragment.findAll(script).map { it.groupValues[1] to it.groupValues[2] }.toList()
        seeds.forEach { seed ->
            fragments.forEach { (info, extras) ->
                out.append("legacy:").append(seed).append(':').append(info).append(':').append(extras).append('\n')
            }
        }
        BareHex.findAll(script).forEach { match ->
            if (seen.add(match.groupValues[1])) out.append("hex:").append(match.groupValues[1]).append('\n')
        }
        return out.toString()
    }
}

/**
 * The bundle fetch the WebView hook performed from inside the page, done from outside: read the
 * player's document, enumerate its script URLs, fetch each bounded script and run the same
 * extraction. Both the document and each script are capped so a hostile or bloated bundle cannot
 * pin the search.
 */
class QobuzBundleScrape(client: HttpClient) {
    private val http = ProviderHttp(client)

    /**
     * The ordered candidate secrets of the live web-player bundle, or null when the player cannot
     * be read at all. An empty list means the bundle shape changed; the caller asks for the secret
     * by hand, like the Android screen did.
     */
    suspend fun candidates(): List<String>? = coroutineScope {
        val document = try {
            http.text(PLAYER_URL)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@coroutineScope null
        }
        val sources = ScriptSource.findAll(document)
            .map { it.groupValues[1] }
            .filter { it.startsWith("https://") || it.startsWith("http://") || it.startsWith('/') }
            .map { if (it.startsWith('/')) "https://play.qobuz.com$it" else it }
            .distinct()
            .take(MAX_SCRIPTS)
            .toList()
        val scripts = sources.map { source ->
            async {
                try {
                    http.text(source)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    ""
                }
            }
        }
        val payload = StringBuilder()
        scripts.forEach { deferred -> payload.append(QobuzBundleSecrets.extractPayload(deferred.await())) }
        QobuzBundleSecrets.candidates(payload.toString())
    }

    private companion object {
        const val PLAYER_URL = "https://play.qobuz.com/"
        const val MAX_SCRIPTS = 24
        val ScriptSource = Regex("<script[^>]+src=\"([^\"]+)\"")
    }
}

/**
 * The direct-API proof the Android login ran for each candidate: `user/get` proves the token and
 * reports the subscription, then a signed `track/getFileUrl` request proves the app secret signs.
 * Both run through [ProviderHttp] so the credential-bearing URL never reaches an exception or a
 * log, and every call is bounded.
 */
@OptIn(ExperimentalTime::class)
class QobuzAccountVerifier(
    client: HttpClient,
    private val digest: QobuzRequestDigest = QobuzRequestDigest { Md5.hexDigest(it.encodeToByteArray()) },
    private val nowSeconds: () -> Long = { Clock.System.now().epochSeconds },
) {
    private val http = ProviderHttp(client)

    /**
     * The [SourceHealthStatus] the settings health map stores: HEALTHY when the account reports a
     * lossless-capable subscription, PREVIEW_ONLY when the token works but cannot stream lossless,
     * UNREACHABLE for any failure — wrong secret, dead token, refused request or network error all
     * collapse into it, like the baseline's probe.
     */
    suspend fun verify(token: QobuzTokenEntry): SourceHealthStatus {
        val premium = accountPremium(token) ?: return SourceHealthStatus.UNREACHABLE
        if (!secretSigns(token)) return SourceHealthStatus.UNREACHABLE
        return if (premium) SourceHealthStatus.HEALTHY else SourceHealthStatus.PREVIEW_ONLY
    }

    /**
     * `user/get` with the app id as a query parameter and both credential headers: the body must
     * carry an `"id"` or `credential` to count as a live account, and the premium markers decide
     * the reported tier.
     */
    private suspend fun accountPremium(token: QobuzTokenEntry): Boolean? {
        val reply = try {
            http.exchange(
                "$QOBUZ_API_BASE/user/get",
                params = mapOf("app_id" to token.appId, "user_auth_token" to token.token),
                headers = mapOf("X-App-Id" to token.appId, "X-User-Auth-Token" to token.token),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        if (reply.status !in 200..299) return null
        val normalized = reply.body.lowercase()
        if (!normalized.contains("\"id\"") && !normalized.contains("credential")) return null
        return PremiumMarker.containsMatchIn(normalized)
    }

    /**
     * A signed `track/getFileUrl` against the canned probe track: the signature covers the same
     * fields the baseline signed, and only an "invalid request signature" body convicts the secret.
     */
    private suspend fun secretSigns(token: QobuzTokenEntry): Boolean {
        val ts = nowSeconds()
        val sig = digest.md5Hex(
            "trackgetFileUrlformat_id${HEALTH_PROBE_FORMAT_ID}intentstreamtrack_id$HEALTH_PROBE_TRACK_ID$ts${token.appSecret}",
        )
        val reply = try {
            http.exchange(
                "$QOBUZ_API_BASE/track/getFileUrl",
                params = mapOf(
                    "request_ts" to ts.toString(),
                    "request_sig" to sig,
                    "track_id" to HEALTH_PROBE_TRACK_ID,
                    "format_id" to HEALTH_PROBE_FORMAT_ID.toString(),
                    "intent" to "stream",
                    "app_id" to token.appId,
                ),
                headers = mapOf("X-App-Id" to token.appId, "X-User-Auth-Token" to token.token),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        }
        val body = reply.body.lowercase()
        return !body.contains("invalid request signature") && !body.contains("invalidrequestsignature")
    }

    private companion object {
        const val QOBUZ_API_BASE = "https://www.qobuz.com/api.json/0.2"
        const val HEALTH_PROBE_TRACK_ID = "5966783"
        const val HEALTH_PROBE_FORMAT_ID = 5
        val PremiumMarker = Regex("""lossless|hi-res|hires|studio|sublime|"format_id"\s*:\s*(6|7|27)""")
    }
}

/**
 * The instance health probe the settings page ran against every configured Qobuz proxy, kept
 * network-identical: normalize the URL, search `api/get-music` for a track id (or take the caller's
 * probe id), then ask `api/download-music` for it and read whether the answer is a real stream or a
 * preview. A proxy that only serves previews is PREVIEW_ONLY; one that fails anywhere along the way
 * is UNREACHABLE.
 */
class QobuzInstanceProbe(client: HttpClient) {
    private val http = ProviderHttp(client)

    suspend fun verify(baseUrl: String, probeTrackId: String?, formatId: Int): SourceHealthStatus {
        val normalized = normalizeQobuzInstanceUrl(baseUrl) ?: return SourceHealthStatus.UNREACHABLE
        val trackId = probeTrackId?.trim().orEmpty().ifEmpty {
            // A search response only proves reachability; a resolved id lets the download call
            // verify the backing entitlement too, like the baseline probe did.
            searchTrackId(normalized, DEFAULT_SEARCH_QUERY).orEmpty()
        }
        if (trackId.isEmpty()) return SourceHealthStatus.UNREACHABLE
        val download = try {
            fetchDownload(normalized, trackId, formatId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return SourceHealthStatus.UNREACHABLE
        } ?: return SourceHealthStatus.UNREACHABLE
        return if (download.preview) SourceHealthStatus.PREVIEW_ONLY else SourceHealthStatus.HEALTHY
    }

    private suspend fun searchTrackId(baseUrl: String, query: String): String? {
        val reply = try {
            http.exchange(
                "$baseUrl/api/get-music",
                params = mapOf("q" to query, "offset" to "0"),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        if (reply.status !in 200..299) return null
        val root = runCatching { Json.parseToJsonElement(reply.body).jsonObject }.getOrNull() ?: return null
        return findTrackItems(root)?.firstNotNullOfOrNull { it.trackId() }
    }

    private suspend fun fetchDownload(baseUrl: String, trackId: String, formatId: Int): DownloadReply? {
        val reply = try {
            http.exchange(
                "$baseUrl/api/download-music",
                params = mapOf("track_id" to trackId, "quality" to formatId.toString()),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        if (reply.status !in 200..299) return null
        val root = runCatching { Json.parseToJsonElement(reply.body).jsonObject }.getOrNull() ?: return null
        val stream = root.findStreamUrl() ?: return null
        return DownloadReply(stream, root.looksLikePreview())
    }

    /** The track item array across the proxy response shapes, exactly the baseline's fallback order. */
    private fun findTrackItems(root: JsonObject): JsonArray? {
        val data = root.obj("data") ?: root
        data.obj("tracks")?.array("items")?.let { return it }
        data.array("items")?.let { return it }
        data.array("tracks")?.let { return it }
        root.array("items")?.let { return it }
        root.array("tracks")?.let { return it }
        return null
    }

    private fun JsonElement.trackId(): String? =
        (this as? JsonObject)?.let { item ->
            listOf("id", "track_id", "trackId").firstNotNullOfOrNull { key ->
                when (val value = item[key]) {
                    is JsonPrimitive -> value.content.takeIf { it.isNotBlank() }
                        ?: value.longOrNull?.toString()
                    else -> null
                }
            }
        }

    /** The first plausible stream URL field, in the baseline's order. */
    private fun JsonObject.findStreamUrl(): String? {
        for (key in listOf("url", "downloadUrl", "download_url", "stream_url", "streamUrl", "link")) {
            text(key)?.takeIf { it.startsWith("http") }?.let { return it }
        }
        return null
    }

    /** The baseline's preview markers: the explicit sample flag or a short-item duration cap. */
    private fun JsonObject.looksLikePreview(): Boolean {
        if (flag("sample") || flag("preview") || flag("is_sample")) return true
        val seconds = (this["duration"] as? JsonPrimitive)?.longOrNull
            ?: (this.obj("data"))?.let { (it["duration"] as? JsonPrimitive)?.longOrNull }
        return seconds != null && seconds in 1..PREVIEW_SECONDS_MAX
    }

    private class DownloadReply(val url: String, val preview: Boolean)

    private companion object {
        const val DEFAULT_SEARCH_QUERY = "adele hello"
        const val PREVIEW_SECONDS_MAX = 60L
    }
}

/** Normalizes an instance URL to `scheme://host[:port]` form, or null when invalid. */
fun normalizeQobuzInstanceUrl(raw: String): String? {
    val trimmed = raw.trim().trimEnd('/')
    if (trimmed.isEmpty()) return null
    val withScheme =
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    val url = runCatching { io.ktor.http.Url(withScheme) }.getOrNull() ?: return null
    if (url.host.isBlank() || !url.host.contains('.')) return null
    return withScheme
}
