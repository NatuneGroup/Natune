package moe.rukamori.archivetune.shared.domain.playback

/** The kinds of the playback error dialog, the same kinds and precedence the Android dialog uses. */
enum class PlaybackErrorKind {
    LOGIN_REFRESH_REQUIRED,
    CONFIRMATION_REQUIRED,
    NO_INTERNET,
    TIMEOUT,
    NO_STREAM,
    MALFORMED_STREAM,
    DECODER,
    HTTP,
    UNKNOWN,
}

/**
 * What the playback error dialog shows for one failure. Engines without numeric error codes report the failure reason
 * as [code], so the copied details still name what went wrong.
 */
data class PlaybackErrorInfo(
    val kind: PlaybackErrorKind,
    val code: String,
    val httpStatus: Int?,
)

/**
 * Classifies a failure the way the Android dialog classifies a Media3 error: network and timeout failures first, then
 * the statuses that mean the stream is gone (403, 404, 410, 416), then malformed media, then any other status. A source
 * that resolved nothing, or a locator the engine would not open, is "No stream available", as on desktop before.
 * Exception names follow the transport classes of the JVM, Ktor and Darwin hosts; engine text follows FFmpeg's wording.
 */
fun PlaybackFailure.toPlaybackErrorInfo(): PlaybackErrorInfo {
    val status = httpStatus ?: httpStatusIn(message)
    val names = causes.map { it.substringBefore(':').trim() }
    val text = (listOf(message) + causes).joinToString("\n").lowercase()
    val kind = when {
        names.any { it.endsWith("TimeoutException") } || TIMEOUT_TEXT.any { it in text } -> PlaybackErrorKind.TIMEOUT
        names.any { it in NETWORK_EXCEPTIONS } || NETWORK_TEXT.any { it in text } -> PlaybackErrorKind.NO_INTERNET
        status in GONE_STATUSES -> PlaybackErrorKind.NO_STREAM
        reason == PlaybackFailureReason.DECODE_FAILED -> PlaybackErrorKind.MALFORMED_STREAM
        status != null -> PlaybackErrorKind.HTTP
        reason in NO_STREAM_REASONS -> PlaybackErrorKind.NO_STREAM
        else -> PlaybackErrorKind.UNKNOWN
    }
    return PlaybackErrorInfo(kind = kind, code = reason.name, httpStatus = status)
}

/** The cause chain of [this], outermost first, as `Name: message` lines, at most [PLAYBACK_ERROR_CAUSE_DEPTH] deep. */
fun Throwable.playbackCauses(): List<String> {
    val lines = mutableListOf<String>()
    var throwable: Throwable? = this
    while (throwable != null && lines.size < PLAYBACK_ERROR_CAUSE_DEPTH) {
        val name = throwable::class.simpleName ?: "Throwable"
        val text = throwable.message?.trim().orEmpty()
        lines += if (text.isNotEmpty()) "$name: $text" else name
        throwable = throwable.cause
    }
    return lines
}

/** The Android dialog's cause depth. */
const val PLAYBACK_ERROR_CAUSE_DEPTH = 6

private fun httpStatusIn(message: String): Int? =
    HTTP_STATUS_TEXT.find(message)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull()

private val GONE_STATUSES = setOf(403, 404, 410, 416)

private val NO_STREAM_REASONS = setOf(
    PlaybackFailureReason.SOURCE_UNAVAILABLE,
    PlaybackFailureReason.UNSUPPORTED_LOCATOR,
    PlaybackFailureReason.OPEN_FAILED,
)

private val NETWORK_EXCEPTIONS = setOf(
    "ConnectException",
    "SocketException",
    "UnknownHostException",
    "NoRouteToHostException",
    "UnresolvedAddressException",
    "PortUnreachableException",
)

// FFmpeg's strerror text for the same conditions, which is all a native engine reports.
private val NETWORK_TEXT = listOf(
    "network is unreachable",
    "no route to host",
    "failed to resolve hostname",
    "name or service not known",
    "nodename nor servname",
    "connection refused",
    "not connected to the internet",
)

private val TIMEOUT_TEXT = listOf("connection timed out", "operation timed out", "request timed out")

private val HTTP_STATUS_TEXT = Regex("""Server returned (\d{3})|HTTP error (\d{3})|\bHTTP (\d{3})\b""")
