package moe.rukamori.archivetune.desktop.provider

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import moe.rukamori.archivetune.shared.data.provider.delegatedSessionIdOrNull
import moe.rukamori.archivetune.shared.data.provider.withTransientRetry
import moe.rukamori.archivetune.shared.data.provider.youTubeSapisidAuthorization

/*
 * Desktop protocol layer for the retained YouTube Music player request. It mirrors the baseline
 * `core` InnerTube client's request shape (`YouTubeClient.toContext`, `InnerTube.player`,
 * `YouTube.visitorData`) and the shipped `YTPlayerUtils` ranking, but it runs on the graph's one
 * borrowed Ktor client and parses JSON itself because that client has no ContentNegotiation. A
 * signed-in request carries the cookie, its SAPISIDHASH and the delegated session only for a client
 * that supports cookie authentication, as the core client does. PO tokens are not minted here.
 */

private const val ORIGIN_YOUTUBE = "https://www.youtube.com"
private const val ORIGIN_YOUTUBE_MUSIC = "https://music.youtube.com"
private const val REFERER_YOUTUBE_MUSIC = "$ORIGIN_YOUTUBE_MUSIC/"
private const val REFERER_YOUTUBE_TV = "$ORIGIN_YOUTUBE/tv"
private const val USER_AGENT_WEB = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
private const val USER_AGENT_WEB_REMIX = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

/** Bounds on attacker- or outage-controlled bodies; a real player response is far below these. */
private const val MAX_PLAYER_RESPONSE_CHARS = 8 * 1024 * 1024

/** One InnerTube client identity. Field names and flags mirror the core `YouTubeClient` model. */
internal data class DesktopYouTubeClient(
    val clientName: String,
    val clientVersion: String,
    val clientId: String,
    val userAgent: String,
    val friendlyName: String? = null,
    val osName: String? = null,
    val osVersion: String? = null,
    val deviceMake: String? = null,
    val deviceModel: String? = null,
    val androidSdkVersion: String? = null,
    val loginSupported: Boolean = false,
    val supportsCookieAuthentication: Boolean = false,
    val loginRequired: Boolean = false,
    val useSignatureTimestamp: Boolean = false,
    val isEmbedded: Boolean = false,
) {
    /** Same identity string the shipped diagnostics use, so a log line names the same client. */
    val key: String
        get() = "$clientName@$clientVersion"

    fun requestOrigin(): String =
        when (clientName.uppercase(Locale.US)) {
            "TVHTML5", "TVHTML5_SIMPLY_EMBEDDED_PLAYER", "TVHTML5_SIMPLY" -> ORIGIN_YOUTUBE
            else -> ORIGIN_YOUTUBE_MUSIC
        }

    fun requestReferer(): String =
        when (clientName.uppercase(Locale.US)) {
            "TVHTML5", "TVHTML5_SIMPLY_EMBEDDED_PLAYER", "TVHTML5_SIMPLY" -> REFERER_YOUTUBE_TV
            else -> REFERER_YOUTUBE_MUSIC
        }

    fun requestApiUrl(endpoint: String): String = "${requestOrigin()}/youtubei/v1/$endpoint"
}

/**
 * The client table the shipped resolver walks. Only the clients the desktop ladder can use are
 * present; every other baseline client is either login-bound or belongs to a tier that is not
 * ported here, so it is omitted rather than approximated.
 */
internal object DesktopYouTubeClients {
    /**
     * The main client supplies metadata and the first stream attempt. Metadata from another client
     * can carry a different loudness target, so the shipped resolver keeps it on this one.
     */
    val MAIN_CLIENT: DesktopYouTubeClient =
        DesktopYouTubeClient(
            clientName = "WEB_REMIX",
            clientVersion = "1.20260213.01.00",
            clientId = "67",
            userAgent = USER_AGENT_WEB_REMIX,
            friendlyName = "Web Music (YouTube Music)",
            loginSupported = true,
            supportsCookieAuthentication = true,
            useSignatureTimestamp = true,
        )

    val WEB: DesktopYouTubeClient =
        DesktopYouTubeClient(
            clientName = "WEB",
            clientVersion = "2.20260114.00.00",
            clientId = "1",
            userAgent = USER_AGENT_WEB,
            friendlyName = "Web",
        )

    val MWEB: DesktopYouTubeClient =
        DesktopYouTubeClient(
            clientName = "MWEB",
            clientVersion = "2.20260114.00.00",
            clientId = "2",
            userAgent = "Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36",
            friendlyName = "Mobile Web",
        )

    val WEB_CREATOR: DesktopYouTubeClient =
        DesktopYouTubeClient(
            clientName = "WEB_CREATOR",
            clientVersion = "1.20260114.00.00",
            clientId = "62",
            userAgent = USER_AGENT_WEB,
            loginSupported = true,
            supportsCookieAuthentication = true,
            loginRequired = true,
            useSignatureTimestamp = true,
        )

    val TVHTML5: DesktopYouTubeClient =
        DesktopYouTubeClient(
            clientName = "TVHTML5",
            clientVersion = "7.20260114.00.00",
            clientId = "7",
            userAgent = "Mozilla/5.0(SMART-TV; Linux; Tizen 4.0.0.2) AppleWebkit/605.1.15 (KHTML, like Gecko) SamsungBrowser/9.2 TV Safari/605.1.15",
            loginSupported = true,
            supportsCookieAuthentication = true,
            loginRequired = true,
            useSignatureTimestamp = true,
        )

    val VISIONOS: DesktopYouTubeClient =
        DesktopYouTubeClient(
            clientName = "VISIONOS",
            clientVersion = "0.1",
            clientId = "101",
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15",
            osName = "visionOS",
            osVersion = "1.3.21O771",
            deviceMake = "Apple",
            deviceModel = "RealityDevice14,1",
            friendlyName = "visionOS",
        )

    val ANDROID_VR_1_65_10: DesktopYouTubeClient =
        DesktopYouTubeClient(
            clientName = "ANDROID_VR",
            clientVersion = "1.65.10",
            clientId = "28",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            osName = "Android",
            osVersion = "12L",
            deviceMake = "Oculus",
            deviceModel = "Quest 3",
            androidSdkVersion = "32",
            friendlyName = "Android VR 1.65",
        )

    val IOS: DesktopYouTubeClient =
        DesktopYouTubeClient(
            clientName = "IOS",
            clientVersion = "19.29.1",
            clientId = "5",
            userAgent = "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X;)",
            osVersion = "17.5.1.21F90",
            friendlyName = "iOS",
        )

    val ANDROID_MUSIC: DesktopYouTubeClient =
        DesktopYouTubeClient(
            clientName = "ANDROID_MUSIC",
            clientVersion = "7.27.52",
            clientId = "21",
            userAgent = "com.google.android.apps.youtube.music/7.27.52 (Linux; U; Android 15; en_US; Pixel 9 Pro; Build/AP4A.250205.002; Cronet/132.0.6834.79) gzip",
            osName = "Android",
            osVersion = "15",
            deviceMake = "Google",
            deviceModel = "Pixel 9 Pro",
            androidSdkVersion = "35",
            friendlyName = "Android Music",
            loginSupported = true,
            useSignatureTimestamp = true,
        )

    /**
     * Age-gate bypass clients. Their login flags are cleared so the anonymous ladder can reach them,
     * exactly as the shipped resolver's AGE_GATE_BYPASS_CLIENTS does.
     */
    val TVHTML5_SIMPLY_EMBEDDED_PLAYER_ANONYMOUS: DesktopYouTubeClient =
        DesktopYouTubeClient(
            clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
            clientVersion = "2.0",
            clientId = "85",
            userAgent = "Mozilla/5.0 (PlayStation; PlayStation 4/12.02) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.4 Safari/605.1.15",
            friendlyName = "TV Embedded (age-gate bypass)",
            useSignatureTimestamp = true,
            isEmbedded = true,
        )

    val WEB_EMBEDDED_ANONYMOUS: DesktopYouTubeClient =
        DesktopYouTubeClient(
            clientName = "WEB_EMBEDDED_PLAYER",
            clientVersion = "1.20260114.00.00",
            clientId = "56",
            userAgent = USER_AGENT_WEB,
            friendlyName = "Web Embedded (age-gate bypass)",
            isEmbedded = true,
        )

    /** Clients tried after the preferred one, in the shipped resolver's order. */
    val STREAM_FALLBACK_CLIENTS: List<DesktopYouTubeClient> =
        listOf(VISIONOS, ANDROID_VR_1_65_10, MAIN_CLIENT, WEB, MWEB, WEB_CREATOR)

    val AGE_GATE_BYPASS_CLIENTS: List<DesktopYouTubeClient> =
        listOf(TVHTML5_SIMPLY_EMBEDDED_PLAYER_ANONYMOUS, WEB_EMBEDDED_ANONYMOUS)
}

/**
 * Client order for one playback request. The preferred client leads, the last client that actually
 * played this session comes next, then the fallback set, the main client if it was not already
 * first, and the age-gate clients only when the user has opted in.
 */
internal fun streamClientOrder(
    preferred: DesktopYouTubeClient,
    lastSuccessful: DesktopYouTubeClient?,
    ageGateAllowed: Boolean,
): List<DesktopYouTubeClient> =
    buildList {
        add(preferred)
        lastSuccessful?.takeIf { it != preferred }?.let { add(it) }
        addAll(DesktopYouTubeClients.STREAM_FALLBACK_CLIENTS)
        if (preferred != DesktopYouTubeClients.MAIN_CLIENT) add(DesktopYouTubeClients.MAIN_CLIENT)
        if (ageGateAllowed) addAll(DesktopYouTubeClients.AGE_GATE_BYPASS_CLIENTS)
    }.distinct()

/** Quality as the shipped resolver names it; any unknown value keeps the automatic choice. */
internal enum class DesktopYouTubeAudioQuality {
    AUTO,
    LOW,
    HIGH,
    HIGHEST,
    ;

    companion object {
        fun fromRequestValue(value: String?): DesktopYouTubeAudioQuality =
            when (value?.trim()?.uppercase(Locale.US)) {
                "LOW" -> LOW
                "HIGH" -> HIGH
                "HIGHEST" -> HIGHEST
                else -> AUTO
            }
    }
}

/**
 * The quality ladder for one request. Each attempt is a full client walk; a lower quality is tried
 * only after the higher one produced no playable stream, matching the shipped resolver.
 */
internal fun qualityAttempts(quality: DesktopYouTubeAudioQuality): List<DesktopYouTubeAudioQuality> =
    when (quality) {
        DesktopYouTubeAudioQuality.HIGHEST -> listOf(DesktopYouTubeAudioQuality.HIGHEST, DesktopYouTubeAudioQuality.HIGH, DesktopYouTubeAudioQuality.LOW)
        DesktopYouTubeAudioQuality.HIGH -> listOf(DesktopYouTubeAudioQuality.HIGH, DesktopYouTubeAudioQuality.LOW)
        DesktopYouTubeAudioQuality.AUTO -> listOf(DesktopYouTubeAudioQuality.AUTO, DesktopYouTubeAudioQuality.HIGH, DesktopYouTubeAudioQuality.LOW)
        DesktopYouTubeAudioQuality.LOW -> listOf(DesktopYouTubeAudioQuality.LOW, DesktopYouTubeAudioQuality.HIGH, DesktopYouTubeAudioQuality.AUTO)
    }.distinct()

/** One audio format from a player response, or a NewPipe stream converted to the same shape. */
internal data class DesktopAudioFormat(
    val itag: Int,
    val url: String?,
    val signatureCipher: String?,
    val cipher: String?,
    val mimeType: String,
    val bitrate: Int,
    val audioSampleRate: Int?,
    val contentLength: Long?,
    val isDefaultAudioTrack: Boolean,
) {
    val codec: String?
        get() = extractCodec(mimeType)
}

internal class DesktopYouTubeHttpException(
    val status: Int,
) : IllegalStateException("YouTube player request failed with HTTP $status")

internal class DesktopYouTubeBadResponseException(
    message: String = "YouTube player returned an unreadable response",
) : IllegalStateException(message)

/** The login-only playability reasons. Terminal: no other client can answer for a signed-out viewer. */
internal class DesktopYouTubeLoginRequiredException(
    val videoId: String,
    reason: String?,
) : IllegalStateException(reason)

internal class DesktopYouTubeBotDetectionException(
    val videoId: String,
    val clients: Set<String>,
) : IllegalStateException("YouTube playback bot detection blocked all stream clients")

internal class DesktopYouTubeNoPlayableResponseException(
    val videoId: String,
    val failedClients: Set<String>,
    cause: Throwable?,
) : IllegalStateException("YouTube playback stream clients returned no playable response", cause)

/**
 * Sends one `/youtubei/v1/player` request for [client]. Non-2xx answers are reported through
 * [DesktopYouTubeHttpException] because the borrowed client does not throw on status.
 */
internal suspend fun requestYouTubePlayer(
    http: HttpClient,
    videoId: String,
    playlistId: String?,
    client: DesktopYouTubeClient,
    signatureTimestamp: Int?,
    visitorData: String?,
    gl: String,
    hl: String,
    signIn: DesktopYouTubeSignIn? = null,
): JsonObject {
    // A client without cookie authentication would reject or ignore the cookie, so it is only ever sent as a visitor.
    val session = signIn?.takeIf { client.supportsCookieAuthentication }
    val body = playerRequestBody(videoId, playlistId, client, signatureTimestamp, visitorData, gl, hl, session)
    val text =
        withTransientRetry {
            val response =
                http.post(client.requestApiUrl("player")) {
                    parameter("prettyPrint", false)
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.UserAgent, client.userAgent)
                    header("X-Goog-Api-Format-Version", "1")
                    header("X-YouTube-Client-Name", client.clientId)
                    header("X-YouTube-Client-Version", client.clientVersion)
                    header("X-Origin", client.requestOrigin())
                    header("Referer", client.requestReferer())
                    visitorData?.let { header("X-Goog-Visitor-Id", it) }
                    session?.let {
                        header(HttpHeaders.Cookie, it.cookie)
                        header(
                            HttpHeaders.Authorization,
                            youTubeSapisidAuthorization(it.loginValue, client.requestOrigin(), System.currentTimeMillis() / 1000),
                        )
                        header("X-Goog-AuthUser", "0")
                    }
                    setBody(body.toString())
                }
            val status = response.status.value
            if (status !in 200..299) throw DesktopYouTubeHttpException(status)
            response.bodyAsText()
        }
    if (text.length > MAX_PLAYER_RESPONSE_CHARS) throw DesktopYouTubeBadResponseException()
    return Json.parseToJsonElement(text) as? JsonObject ?: throw DesktopYouTubeBadResponseException()
}

private fun playerRequestBody(
    videoId: String,
    playlistId: String?,
    client: DesktopYouTubeClient,
    signatureTimestamp: Int?,
    visitorData: String?,
    gl: String,
    hl: String,
    session: DesktopYouTubeSignIn?,
): JsonObject =
    buildJsonObject {
        put("context", contextJson(client, videoId, visitorData, gl, hl, session))
        put("videoId", videoId)
        playlistId?.let { put("playlistId", it) }
        put("racyCheckOk", true)
        put("contentCheckOk", true)
        if (client.useSignatureTimestamp) {
            // The timestamp is what lets the player request mint URLs for the current base.js; without
            // it the response is ciphered for a stale script, so an absent value is omitted, not zeroed.
            putJsonObject("playbackContext") {
                putJsonObject("contentPlaybackContext") {
                    signatureTimestamp?.let { put("signatureTimestamp", it) }
                    put("vis", 0)
                    put("splay", false)
                    put("lactMilliseconds", "-1")
                }
            }
        }
    }

private fun contextJson(
    client: DesktopYouTubeClient,
    videoId: String,
    visitorData: String?,
    gl: String,
    hl: String,
    session: DesktopYouTubeSignIn?,
): JsonObject =
    buildJsonObject {
        put(
            "client",
            buildJsonObject {
                put("clientName", client.clientName)
                put("clientVersion", client.clientVersion)
                client.osName?.let { put("osName", it) }
                client.osVersion?.let { put("osVersion", it) }
                client.deviceMake?.let { put("deviceMake", it) }
                client.deviceModel?.let { put("deviceModel", it) }
                client.androidSdkVersion?.let { put("androidSdkVersion", it) }
                put("gl", gl)
                put("hl", hl)
                visitorData?.let { put("visitorData", it) }
            },
        )
        if (client.isEmbedded) {
            put(
                "thirdParty",
                buildJsonObject {
                    put("embedUrl", "https://www.youtube.com/watch?v=$videoId")
                },
            )
        }
        put(
            "request",
            buildJsonObject {
                put("internalExperimentFlags", buildJsonArray { })
                put("useSsl", true)
            },
        )
        put(
            "user",
            buildJsonObject {
                put("lockedSafetyMode", false)
                session?.dataSyncId.delegatedSessionIdOrNull()?.let { put("onBehalfOfUser", it) }
            },
        )
    }

internal fun JsonObject.stringOf(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.intOf(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.longOf(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

internal fun JsonObject.booleanOf(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

/** Playability status string, for example OK, LOGIN_REQUIRED or UNPLAYABLE; null when absent. */
internal fun JsonObject.playabilityStatus(): String? = (this["playabilityStatus"] as? JsonObject)?.stringOf("status")

internal fun JsonObject.playabilityReason(): String = (this["playabilityStatus"] as? JsonObject)?.stringOf("reason").orEmpty()

internal fun JsonObject.videoDetails(): JsonObject? = this["videoDetails"] as? JsonObject

/** Audio-only adaptive formats in the order YouTube lists them; video and unknown entries are dropped. */
internal fun JsonObject.audioFormats(): List<DesktopAudioFormat> {
    val streamingData = this["streamingData"] as? JsonObject ?: return emptyList()
    val adaptive = streamingData["adaptiveFormats"] as? JsonArray ?: return emptyList()
    return adaptive.mapNotNull { element -> (element as? JsonObject)?.toAudioFormat() }
}

private fun JsonObject.toAudioFormat(): DesktopAudioFormat? {
    val mimeType = stringOf("mimeType") ?: return null
    if (!mimeType.startsWith("audio/")) return null
    val itag = intOf("itag") ?: return null
    val audioTrack = this["audioTrack"] as? JsonObject
    return DesktopAudioFormat(
        itag = itag,
        url = stringOf("url"),
        signatureCipher = stringOf("signatureCipher"),
        cipher = stringOf("cipher"),
        mimeType = mimeType,
        bitrate = intOf("bitrate") ?: 0,
        audioSampleRate = stringOf("audioSampleRate")?.toIntOrNull(),
        contentLength = stringOf("contentLength")?.toLongOrNull(),
        // An absent audio track is the ordinary single-language case; only an explicit false marks a dub.
        isDefaultAudioTrack = audioTrack == null || audioTrack.booleanOf("audioIsDefault") != false,
    )
}

/**
 * The quality-aware audio ranking, ported from the shipped `rankAudioFormatCandidates`. Dubs are
 * confined to the released track when one exists, so the chosen language does not depend on which
 * itag happened to encode best.
 */
internal fun rankAudioFormats(
    formats: List<DesktopAudioFormat>,
    audioQuality: DesktopYouTubeAudioQuality,
    networkMetered: Boolean,
): List<DesktopAudioFormat> {
    val allAudioFormats =
        formats.filter { it.bitrate > 0 && (it.url != null || it.signatureCipher != null || it.cipher != null) }
    val audioFormats = allAudioFormats.filter { it.isDefaultAudioTrack }.ifEmpty { allAudioFormats }
    if (audioFormats.isEmpty()) return emptyList()

    val effectiveQuality =
        when (audioQuality) {
            DesktopYouTubeAudioQuality.AUTO -> if (networkMetered) DesktopYouTubeAudioQuality.HIGH else DesktopYouTubeAudioQuality.HIGHEST
            else -> audioQuality
        }
    val targetBitrateBps: Int? =
        when (effectiveQuality) {
            DesktopYouTubeAudioQuality.LOW -> 70_000
            DesktopYouTubeAudioQuality.HIGH -> 160_000
            DesktopYouTubeAudioQuality.HIGHEST -> 320_000
            DesktopYouTubeAudioQuality.AUTO -> null
        }

    val preferHigher =
        compareByDescending<DesktopAudioFormat> { it.url != null }
            .thenByDescending { it.bitrate }
            .thenByDescending { codecRank(it.codec) }
            .thenByDescending { it.audioSampleRate ?: 0 }
    val preferLowerAboveTarget =
        compareByDescending<DesktopAudioFormat> { it.url != null }
            .thenBy { it.bitrate }
            .thenByDescending { codecRank(it.codec) }
            .thenByDescending { it.audioSampleRate ?: 0 }

    return if (targetBitrateBps == null || effectiveQuality == DesktopYouTubeAudioQuality.HIGHEST) {
        audioFormats.sortedWith(preferHigher)
    } else {
        val preferred = audioFormats.filter { it.bitrate <= targetBitrateBps }.sortedWith(preferHigher)
        val fallback = audioFormats.filter { it.bitrate > targetBitrateBps }.sortedWith(preferLowerAboveTarget)
        preferred + fallback
    }
}

private fun codecRank(codec: String?): Int =
    when {
        codec.isNullOrBlank() -> 0
        codec.contains("opus", ignoreCase = true) -> 3
        codec.contains("mp4a", ignoreCase = true) -> 2
        else -> 1
    }

private val CODECS_PARAMETER_REGEX = Regex("""codecs="([^"]+)"""")

internal fun extractCodec(mimeType: String): String? =
    CODECS_PARAMETER_REGEX
        .find(mimeType)
        ?.groupValues
        ?.getOrNull(1)
        ?.split(",")
        ?.firstOrNull()
        ?.trim()

/** The container type without its codecs parameter, for example `audio/webm`. */
internal fun mimeBase(mimeType: String): String = mimeType.substringBefore(';').trim()

/** The full codec list inside the mime type's quotes, which is what the player's codec field carries. */
internal fun mimeCodecs(mimeType: String): String =
    Regex("""codecs="([^"]*)"""").find(mimeType)?.groupValues?.getOrNull(1).orEmpty()

/**
 * Login and bot wording the shipped resolver classifies on. Both checks run on the reason text
 * YouTube returns, so they stay byte-for-byte equivalent to the baseline predicates.
 */
internal fun isBotDetectionReason(reason: String): Boolean {
    val lower = reason.lowercase(Locale.US)
    return "bot" in lower ||
        "unusual traffic" in lower ||
        "automated" in lower ||
        "confirm" in lower && "not a" in lower ||
        "not a robot" in lower ||
        "verify" in lower && "human" in lower
}

internal fun isLoginRecoveryReason(reason: String): Boolean {
    val lower = reason.lowercase(Locale.US)
    return "confirm your age" in lower ||
        "age-restricted" in lower ||
        "age restricted" in lower ||
        "inappropriate for some users" in lower ||
        "mature audiences" in lower ||
        "adult" in lower && "sign in" in lower ||
        "please sign in" in lower ||
        "sign in to confirm" in lower ||
        "allow" in lower && "youtube music" in lower
}

internal fun isLoginRecoveryResponse(
    status: String?,
    reason: String,
): Boolean = status.equals("LOGIN_REQUIRED", ignoreCase = true) || isLoginRecoveryReason(reason)

internal fun Throwable.isForbiddenPlayerRequest(): Boolean = (this as? DesktopYouTubeHttpException)?.status == 403

/**
 * True when the failure came from the JavaScript deobfuscation step rather than from the stream
 * itself. The shipped resolver stops trying ciphered candidates for that client in this case,
 * because every further candidate would fail for the same reason.
 */
internal fun Throwable.isJavaScriptPlayerExtractorFailure(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        val message = current.message.orEmpty()
        if (
            message.contains("deobfuscation", ignoreCase = true) ||
            message.contains("JavaScript player", ignoreCase = true) ||
            message.contains("base JavaScript player", ignoreCase = true)
        ) {
            return true
        }
        current = current.cause
    }
    return false
}
