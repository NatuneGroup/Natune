package moe.rukamori.archivetune.shared.data.provider

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Privately owned (uploaded) tracks shelf of the signed-in YouTube Music library. */
const val YOUTUBE_UPLOADED_TRACKS_BROWSE_ID = "FEmusic_library_privately_owned_tracks"

/** Rules every host applies before it opens a picked file or sends a request. */
object YouTubeUploadPolicy {
    /** Exclusive ceiling: a file of exactly 300 MiB is refused, matching the upstream limit. */
    const val MAX_BYTES: Long = 314_572_800L

    val SUPPORTED_EXTENSIONS: Set<String> = setOf("mp3", "m4a", "flac", "ogg", "wma")

    fun extensionOf(fileName: String): String = fileName.substringAfterLast('.', "").lowercase()

    /** The first rule the file breaks, or null when it may be uploaded. */
    fun validate(fileName: String, contentLength: Long): YouTubeUploadFailure? = when {
        extensionOf(fileName) !in SUPPORTED_EXTENSIONS -> YouTubeUploadFailure.UNSUPPORTED_TYPE
        contentLength <= 0L -> YouTubeUploadFailure.EMPTY_FILE
        contentLength >= MAX_BYTES -> YouTubeUploadFailure.TOO_LARGE
        else -> null
    }
}

/** Why an upload, delete or browse request could not complete. Never carries account data. */
enum class YouTubeUploadFailure { SIGN_IN_REQUIRED, UNSUPPORTED_TYPE, EMPTY_FILE, TOO_LARGE, HTTP, UNKNOWN }

sealed interface YouTubeUploadResult {
    /** The service accepted the bytes. The song appears in the library once processing finishes. */
    data object Uploaded : YouTubeUploadResult

    /** HTTP 409: the account already holds this file. Informational, not an error. */
    data object AlreadyUploaded : YouTubeUploadResult

    data class Failed(val failure: YouTubeUploadFailure, val httpStatus: Int? = null) : YouTubeUploadResult
}

sealed interface YouTubeDeleteResult {
    data object Deleted : YouTubeDeleteResult

    /** A failed delete is never reported as success: the upload is still in the account. */
    data class Failed(val failure: YouTubeUploadFailure, val httpStatus: Int? = null) : YouTubeDeleteResult
}

/** Browse calls return pages rather than result values, so they throw this on failure. */
class YouTubeUploadException(val failure: YouTubeUploadFailure, val httpStatus: Int? = null) :
    IllegalStateException("YouTube upload request failed: ${failure.name}")

data class YouTubeUploadedSong(
    val videoId: String,
    /** Id the service needs to delete this upload. Null when the row offers no delete command. */
    val entityId: String?,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationSeconds: Int?,
    val thumbnailUrl: String?,
)

data class YouTubeUploadedSongsPage(val songs: List<YouTubeUploadedSong>, val continuation: String?)

/** Signed-in account state the host supplies for each request. The client keeps none of it. */
class YouTubeUploadSession(
    val cookie: String,
    val visitorData: String?,
    val dataSyncId: String?,
    val gl: String = "US",
    val hl: String = "en",
)

/** Forward-only upload body. Hosts open the file only after [YouTubeUploadPolicy.validate] passes. */
interface YouTubeUploadContent {
    val length: Long

    /** Fills [buffer] with the next bytes and returns the count, or -1 at the end of the content. */
    suspend fun read(buffer: ByteArray): Int
}

internal const val YOUTUBE_MUSIC_ORIGIN = "https://music.youtube.com"
internal const val YOUTUBE_MUSIC_API = "https://music.youtube.com/youtubei/v1/"
internal const val WEB_REMIX_CLIENT_ID = "67"
internal const val WEB_REMIX_CLIENT_NAME = "WEB_REMIX"
internal const val WEB_REMIX_CLIENT_VERSION = "1.20260213.01.00"
internal const val WEB_REMIX_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

/** SAPISIDHASH signing, mirroring the retained InnerTube core without depending on that JVM-only module. */
internal object YouTubeUploadAuth {
    private val LOGIN_COOKIES = listOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID")

    /** The cookie value that signs SAPISIDHASH, in the same preference order as the InnerTube core. */
    fun loginCookieValue(cookie: String): String? {
        val values = HashMap<String, String>()
        for (part in cookie.split(';')) {
            val separator = part.indexOf('=')
            if (separator <= 0) continue
            // The first occurrence of a cookie name wins, as it does upstream; putIfAbsent is JVM-only.
            val name = part.substring(0, separator).trim()
            if (name !in values) values[name] = part.substring(separator + 1).trim()
        }
        return LOGIN_COOKIES.firstNotNullOfOrNull { name -> values[name]?.takeIf(String::isNotBlank) }
    }

    fun authorization(loginValue: String, origin: String, epochSeconds: Long): String {
        val digest = Sha1.digest("$epochSeconds $loginValue $origin".encodeToByteArray())
        val hex = digest.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        return "SAPISIDHASH ${epochSeconds}_$hex"
    }
}

/** The brand-account delegate inside `dataSyncId`, encoded as `<id>||<rest>` when present. */
internal fun YouTubeUploadSession.delegatedSessionId(): String? {
    val value = dataSyncId?.trim()?.takeIf(String::isNotBlank) ?: return null
    val separator = value.indexOf("||")
    if (separator <= 0 || separator + 2 >= value.length) return null
    return value.substring(0, separator).trim().takeIf(String::isNotBlank)
}

/** The WEB_REMIX request context the InnerTube JSON endpoints expect in every body. */
internal fun YouTubeUploadSession.innerTubeContext(): JsonObject = buildJsonObject {
    putJsonObject("client") {
        put("clientName", WEB_REMIX_CLIENT_NAME)
        put("clientVersion", WEB_REMIX_CLIENT_VERSION)
        put("gl", gl)
        put("hl", hl)
        visitorData?.let { put("visitorData", it) }
    }
    putJsonObject("user") {
        put("lockedSafetyMode", false)
        delegatedSessionId()?.let { put("onBehalfOfUser", it) }
    }
    putJsonObject("request") { put("useSsl", true) }
}
