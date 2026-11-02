package moe.rukamori.archivetune.shared.domain.playback

/**
 * Retry and integrity rules for ranged HTTP segment fetches, shared by the Android stream
 * DataSources and the desktop `HttpRangeSource`.
 *
 * Spotify's transport retries a media segment when the request never produced a status at
 * all — the "status 0" class: offline, connection reset or a timed-out socket — and when a
 * SUCCESS body arrives shorter than its declared length, which is silent corruption rather
 * than a clean end of stream. A real HTTP status stays fatal: the server answered and the
 * answer was not retryable bytes.
 */
object SegmentFetchPolicy {
    /** Attempts one fetch gets, first try included (the per-segment budget is 2). */
    const val MAX_SEGMENT_ATTEMPTS = 2

    enum class FailureClass {
        /** No HTTP status ever arrived: connect/read timeout, reset, offline — status 0. */
        NO_RESPONSE,

        /** The body stopped before the response's declared length. */
        TRUNCATED_BODY,

        /** An HTTP status arrived that was not OK for this request. */
        HTTP_STATUS,

        /** Lease, origin, redirect or protocol violations — never a transport flake. */
        PROTOCOL,
    }

    /** Only transport failures and truncated bodies earn another attempt. */
    fun isRetryable(failureClass: FailureClass): Boolean =
        failureClass == FailureClass.NO_RESPONSE ||
            failureClass == FailureClass.TRUNCATED_BODY

    /** True while the retryable [failureClass] has attempts left after [attempt] failures. */
    fun allowsAnotherAttempt(
        failureClass: FailureClass,
        attempt: Int,
    ): Boolean = isRetryable(failureClass) && attempt < MAX_SEGMENT_ATTEMPTS

    /**
     * The byte count a single response promises to deliver. A declared `Content-Length`
     * wins; a partial response with no length header otherwise owes the range total minus
     * [positionBytes]. -1 when the response declares nothing a truncation can be checked
     * against.
     */
    fun expectedSegmentBytes(
        contentLengthBytes: Long,
        rangeTotalBytes: Long,
        positionBytes: Long,
    ): Long =
        when {
            contentLengthBytes > 0L -> contentLengthBytes
            rangeTotalBytes > positionBytes && positionBytes >= 0L -> rangeTotalBytes - positionBytes
            else -> UNKNOWN_LENGTH
        }

    const val UNKNOWN_LENGTH = -1L
}
