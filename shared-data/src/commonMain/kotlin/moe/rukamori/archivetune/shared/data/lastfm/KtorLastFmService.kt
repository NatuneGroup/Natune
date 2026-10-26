/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.lastfm

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import moe.rukamori.archivetune.shared.data.bytesource.Md5
import moe.rukamori.archivetune.shared.data.provider.readBoundedText
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmApiConfig
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmApiException
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmBatchResult
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmChartPeriod
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmDashboardLimits
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmDashboardService
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmEndpoints
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmMobileAuthMode
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmPage
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmRecentTrack
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmScrobble
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmService
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmSession
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmTopAlbum
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmTopArtist
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmTopTrack
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmTrackMetadata
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmUserProfile

/**
 * Ktor implementation of [LastFmService] and [LastFmDashboardService], ported from the retained Android client.
 *
 * Every call is signed the way Last.fm requires: the parameters (without `format`) are sorted by name, each is
 * concatenated as name followed by value, the shared secret is appended, and the lowercase MD5 hex digest is sent as
 * `api_sig`. The configuration is read on every call, so an endpoint or key change applies to the next request.
 *
 * The injected client must be built with `expectSuccess = false`. Last.fm reports API errors with 4xx and 5xx
 * statuses, and the error code is only readable from the response body.
 */
class KtorLastFmService(
    private val client: HttpClient,
    private val config: () -> LastFmApiConfig,
) : LastFmService, LastFmDashboardService {
    override suspend fun requestWebToken(): Result<String> =
        guarded {
            parseObject(post(method = "auth.getToken", sessionKey = null, extra = emptyMap()))
                .stringOrThrow("token")
        }

    override fun authorizationUrl(token: String): String {
        val current = config()
        val apiKey = current.credentials.apiKey
        return if (current.endpoint == LastFmEndpoints.LIBREFM) {
            "https://libre.fm/api/auth?api_key=$apiKey&token=$token"
        } else {
            "https://www.last.fm/api/auth/?api_key=$apiKey&token=$token"
        }
    }

    override suspend fun exchangeWebToken(token: String): Result<LastFmSession> =
        guarded {
            parseSession(post(method = "auth.getSession", sessionKey = null, extra = mapOf("token" to token)))
        }

    override suspend fun signInWithPassword(
        username: String,
        password: String,
        mode: LastFmMobileAuthMode,
    ): Result<LastFmSession> =
        guarded {
            val credentials =
                when (mode) {
                    LastFmMobileAuthMode.PASSWORD -> mapOf("password" to password)
                    // Libre.fm accepts only md5(lowercase username + md5(password)) here, never the plain password.
                    LastFmMobileAuthMode.LEGACY_AUTH_TOKEN ->
                        mapOf("authToken" to md5Hex(username.lowercase() + md5Hex(password)))
                }
            parseSession(
                post(
                    method = "auth.getMobileSession",
                    sessionKey = null,
                    extra = mapOf("username" to username) + credentials,
                ),
            )
        }

    override suspend fun updateNowPlaying(
        track: LastFmTrackMetadata,
        sessionKey: String,
    ): Result<Unit> =
        guarded {
            post(
                method = "track.updateNowPlaying",
                sessionKey = sessionKey,
                extra =
                    buildMap {
                        put("artist", track.artist)
                        put("track", track.track)
                        track.album?.takeIf { it.isNotBlank() }?.let { put("album", it) }
                        track.durationSeconds?.takeIf { it > 0 }?.let { put("duration", it.toString()) }
                    },
            )
            Unit
        }

    override suspend fun scrobbleBatch(
        scrobbles: List<LastFmScrobble>,
        sessionKey: String,
    ): Result<LastFmBatchResult> =
        guarded {
            require(scrobbles.size in 1..LastFmEndpoints.MAX_SCROBBLES_PER_BATCH) {
                "Last.fm accepts 1 to ${LastFmEndpoints.MAX_SCROBBLES_PER_BATCH} plays per batch"
            }
            val text =
                post(
                    method = "track.scrobble",
                    sessionKey = sessionKey,
                    extra =
                        buildMap {
                            scrobbles.forEachIndexed { index, item ->
                                put("artist[$index]", item.artist)
                                put("track[$index]", item.track)
                                put("timestamp[$index]", item.timestampEpochSeconds.toString())
                                item.album?.takeIf { it.isNotBlank() }?.let { put("album[$index]", it) }
                                item.albumArtist?.takeIf { it.isNotBlank() }?.let { put("albumArtist[$index]", it) }
                                item.trackNumber?.takeIf { it > 0 }?.let { put("trackNumber[$index]", it.toString()) }
                                item.durationSeconds?.takeIf { it > 0 }?.let { put("duration[$index]", it.toString()) }
                                if (item.chosenByUser) put("chosenByUser[$index]", "1")
                            }
                        },
                )
            parseBatchResult(text, scrobbles.size)
        }

    override suspend fun setLoved(
        artist: String,
        track: String,
        loved: Boolean,
        sessionKey: String,
    ): Result<Unit> =
        guarded {
            post(
                method = if (loved) "track.love" else "track.unlove",
                sessionKey = sessionKey,
                extra = mapOf("artist" to artist, "track" to track),
            )
            Unit
        }

    override suspend fun getUserProfile(username: String): Result<LastFmUserProfile> =
        guarded {
            require(username.isNotBlank()) { "A Last.fm profile needs a user name" }
            LastFmDashboardResponses.userProfile(
                post(method = "user.getInfo", sessionKey = null, extra = mapOf("user" to username)),
            )
        }

    override suspend fun getRecentTracks(
        username: String,
        limit: Int,
        page: Int,
    ): Result<LastFmPage<LastFmRecentTrack>> =
        guarded {
            requireListing(username, limit, page)
            require(limit <= LastFmDashboardLimits.MAX_RECENT_TRACKS_PER_PAGE) {
                "Last.fm returns at most ${LastFmDashboardLimits.MAX_RECENT_TRACKS_PER_PAGE} recent tracks per page"
            }
            LastFmDashboardResponses.recentTracks(
                text = post(method = "user.getRecentTracks", sessionKey = null, extra = listing(username, limit, page)),
                requestedPage = page,
                requestedLimit = limit,
            )
        }

    override suspend fun getTopTracks(
        username: String,
        period: LastFmChartPeriod,
        limit: Int,
        page: Int,
    ): Result<LastFmPage<LastFmTopTrack>> =
        guarded {
            requireListing(username, limit, page)
            LastFmDashboardResponses.topTracks(
                text =
                    post(
                        method = "user.getTopTracks",
                        sessionKey = null,
                        extra = listing(username, limit, page, period),
                    ),
                requestedPage = page,
                requestedLimit = limit,
            )
        }

    override suspend fun getTopArtists(
        username: String,
        period: LastFmChartPeriod,
        limit: Int,
        page: Int,
    ): Result<LastFmPage<LastFmTopArtist>> =
        guarded {
            requireListing(username, limit, page)
            LastFmDashboardResponses.topArtists(
                text =
                    post(
                        method = "user.getTopArtists",
                        sessionKey = null,
                        extra = listing(username, limit, page, period),
                    ),
                requestedPage = page,
                requestedLimit = limit,
            )
        }

    override suspend fun getTopAlbums(
        username: String,
        period: LastFmChartPeriod,
        limit: Int,
        page: Int,
    ): Result<LastFmPage<LastFmTopAlbum>> =
        guarded {
            requireListing(username, limit, page)
            LastFmDashboardResponses.topAlbums(
                text =
                    post(
                        method = "user.getTopAlbums",
                        sessionKey = null,
                        extra = listing(username, limit, page, period),
                    ),
                requestedPage = page,
                requestedLimit = limit,
            )
        }

    override suspend fun getTrackTags(
        artist: String,
        track: String,
    ): Result<List<String>> =
        guarded {
            require(artist.isNotBlank() && track.isNotBlank()) { "A Last.fm track needs an artist and a title" }
            LastFmDashboardResponses.trackTags(
                post(method = "track.getInfo", sessionKey = null, extra = mapOf("artist" to artist, "track" to track)),
            )
        }

    private fun requireListing(
        username: String,
        limit: Int,
        page: Int,
    ) {
        require(username.isNotBlank()) { "A Last.fm listing needs a user name" }
        require(limit >= 1) { "A Last.fm listing needs at least one item per page" }
        require(page >= 1) { "Last.fm pages start at 1" }
    }

    /** The parameters of a user listing. Recent tracks take no period, so [period] is null for them. */
    private fun listing(
        username: String,
        limit: Int,
        page: Int,
        period: LastFmChartPeriod? = null,
    ): Map<String, String> =
        buildMap {
            put("user", username)
            period?.let { put("period", it.apiValue) }
            put("limit", limit.toString())
            put("page", page.toString())
        }

    /**
     * Sends one signed POST and returns the response text. A Last.fm API error is thrown with its code whatever the
     * HTTP status, and a non-success status without an API error body is thrown as a non-API failure.
     */
    private suspend fun post(
        method: String,
        sessionKey: String?,
        extra: Map<String, String>,
    ): String {
        val current = config()
        val signed = linkedMapOf("method" to method, "api_key" to current.credentials.apiKey)
        sessionKey?.let { signed["sk"] = it }
        signed.putAll(extra)
        val signature = signatureOf(signed, current.credentials.sharedSecret)
        val form =
            Parameters.build {
                signed.forEach { (key, value) -> append(key, value) }
                append("api_sig", signature)
                append("format", "json")
            }
        return client
            .preparePost(current.endpoint) {
                header(HttpHeaders.UserAgent, USER_AGENT)
                setBody(FormDataContent(form))
            }.execute { response ->
                val text = response.readBoundedText(MAX_RESPONSE_BYTES)
                val httpStatus = response.status.value.takeUnless { response.status.isSuccess() }
                apiError(text, httpStatus)?.let { throw it }
                if (httpStatus != null) {
                    throw LastFmApiException(
                        code = httpStatus,
                        message = response.status.description,
                        httpStatus = httpStatus,
                        isApiError = false,
                    )
                }
                text
            }
    }

    private fun apiError(
        text: String,
        httpStatus: Int?,
    ): LastFmApiException? {
        val body = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        val code = (body["error"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
        val message = (body["message"] as? JsonPrimitive)?.content.orEmpty()
        return LastFmApiException(code = code, message = message, httpStatus = httpStatus, isApiError = true)
    }

    private fun parseSession(text: String): LastFmSession {
        val session = parseObject(text)["session"] as? JsonObject ?: throw malformed()
        return LastFmSession(
            username = session.stringOrThrow("name"),
            sessionKey = session.stringOrThrow("key"),
            subscriber = (session["subscriber"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
        )
    }

    /**
     * Reads the outcome of a batch from the counts Last.fm states for it: how many plays it accepted and how many it
     * ignored. The two must cover every play sent. A reply without those counts, or whose counts do not cover the
     * batch, is not an acknowledgement. It throws a non-API failure, so the owner keeps the plays and retries them
     * instead of removing plays that Last.fm never confirmed. Last.fm sends one entry as an object and several as an
     * array, and the entries only add the ignore codes.
     */
    private fun parseBatchResult(
        text: String,
        submitted: Int,
    ): LastFmBatchResult {
        val scrobbles = parseObject(text)["scrobbles"] as? JsonObject ?: throw malformed()
        val attr = scrobbles["@attr"] as? JsonObject ?: throw malformed()
        val accepted = attr.countOrThrow("accepted")
        val ignored = attr.countOrThrow("ignored")
        if (accepted + ignored != submitted) throw malformed()
        val entries =
            when (val entry = scrobbles["scrobble"]) {
                is JsonArray -> entry.mapNotNull { it as? JsonObject }
                is JsonObject -> listOf(entry)
                else -> emptyList()
            }
        val ignoredCodes =
            entries.mapNotNull { item ->
                val ignoredMessage = item["ignoredMessage"] as? JsonObject ?: return@mapNotNull null
                (ignoredMessage["code"] as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it != 0 }
            }
        return LastFmBatchResult(accepted = accepted, ignored = ignored, ignoredCodes = ignoredCodes)
    }

    private fun parseObject(text: String): JsonObject =
        (runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject) ?: throw malformed()

    private fun JsonObject.stringOrThrow(key: String): String =
        (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: throw malformed()

    private fun JsonObject.countOrThrow(key: String): Int =
        (this[key] as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it >= 0 } ?: throw malformed()

    private fun malformed(): LastFmApiException =
        LastFmApiException(
            code = MALFORMED_CODE,
            message = "Last.fm returned an unreadable response",
            isApiError = false,
        )

    /** Converts any failure into a [Result] but rethrows cancellation, so a cancelled caller never sees a failure. */
    private suspend fun <T> guarded(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }

    companion object {
        private const val USER_AGENT = "ArchiveTune (https://github.com/rukamori/ArchiveTune)"
        private const val MAX_RESPONSE_BYTES = 256 * 1024

        /** A local code for a reply that cannot be read. Last.fm never uses 0, and it is treated as retryable. */
        private const val MALFORMED_CODE = 0

        /**
         * The Last.fm signature: each sorted parameter name followed by its value, then the shared secret, as an
         * MD5 lowercase hex digest. Sorting uses the natural string order, matching the baseline's sorted map.
         */
        internal fun signatureOf(
            params: Map<String, String>,
            sharedSecret: String,
        ): String {
            val joined = params.keys.sorted().joinToString(separator = "") { key -> key + params.getValue(key) }
            return md5Hex(joined + sharedSecret)
        }

        private fun md5Hex(text: String): String = Md5.hexDigest(text.encodeToByteArray())
    }
}
