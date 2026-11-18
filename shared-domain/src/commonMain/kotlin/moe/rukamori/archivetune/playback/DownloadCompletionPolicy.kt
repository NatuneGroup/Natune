
package moe.rukamori.archivetune.playback

private val contentRangeTotalRegex = Regex("(?i)^bytes\\s+\\d+-\\d+/(\\d+)$")

fun resolveExpectedDownloadedLength(
    explicitExpectedLength: String?,
    headContentLength: String?,
    rangeResponseCode: Int?,
    rangeContentRange: String?,
    rangeContentLength: String?,
): Long =
    parsePositiveLength(explicitExpectedLength)
        ?: parsePositiveLength(headContentLength)
        ?: when (rangeResponseCode) {
            206 -> contentRangeTotalRegex
                .matchEntire(rangeContentRange?.trim().orEmpty())
                ?.groupValues
                ?.getOrNull(1)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
            200 -> parsePositiveLength(rangeContentLength)
            else -> null
        }
        ?: 0L

fun isDownloadedContentTruncated(actualLength: Long, expectedLength: Long): Boolean =
    expectedLength > 0L && actualLength < expectedLength

/**
 * One download attempt's failure bucket. Status-0 transport failures (no HTTP answer at all),
 * 5xx server errors and silently truncated bodies earn another attempt — the same classes
 * Spotify retries for media segments. A real HTTP error status or an empty success stays fatal.
 */
enum class DownloadFailureClass {
    NO_RESPONSE,
    SERVER,
    HTTP_STATUS,
    TRUNCATED_BODY,
    EMPTY_RESULT,
}

fun classifyDownloadFailure(
    isConnectionError: Boolean,
    isServerError: Boolean,
    responseCode: Int,
): DownloadFailureClass =
    when {
        isConnectionError || responseCode <= 0 -> DownloadFailureClass.NO_RESPONSE
        isServerError -> DownloadFailureClass.SERVER
        else -> DownloadFailureClass.HTTP_STATUS
    }

internal fun isRetryableDownloadFailure(failureClass: DownloadFailureClass): Boolean =
    when (failureClass) {
        DownloadFailureClass.NO_RESPONSE,
        DownloadFailureClass.SERVER,
        DownloadFailureClass.TRUNCATED_BODY,
        -> true

        DownloadFailureClass.HTTP_STATUS,
        DownloadFailureClass.EMPTY_RESULT,
        -> false
    }

private fun parsePositiveLength(value: String?): Long? =
    value?.trim()?.toLongOrNull()?.takeIf { it > 0L }
