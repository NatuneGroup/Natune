package moe.rukamori.archivetune.shared.domain.playback

/**
 * Why a playback failure happened, in terms a recovery policy can act on. The categories mirror the
 * host taxonomy: a carried HTTP status is the strongest signal, engine cause names the next, and the
 * reported [PlaybackFailureReason] the last fallback.
 */
enum class PlaybackFailureCategory {
    /** The CDN refused an otherwise valid signed URL, typically 403. */
    CDN_FORBIDDEN,

    /** The stream token or account credential behind the URL is no longer accepted. */
    TOKEN,

    /** The URL expired or no longer names the media. */
    URL_EXPIRED,

    /** The opened stream could not be decoded. */
    DECODER,

    /** The SABR conversation failed at the protocol level. */
    SABR_PROTOCOL,

    /** SABR asked for an attestation it did not receive. */
    SABR_ATTESTATION,

    /** The request or stream took too long. */
    TIMEOUT,

    /** The provider refused the request by rate limit. */
    RATE_LIMIT,

    /** A transport problem below HTTP: connect, resolve or route. */
    NETWORK,

    /** The renderer or output stage failed. */
    RENDERER,

    /** The provider refused the item as not playable in this context. */
    PLAYABILITY,

    /** The item cannot be played through any source. */
    UNAVAILABLE,

    /** Nothing more specific applies. */
    UNKNOWN,
}

/** What one failure is worth doing about, in the host's recovery vocabulary. */
enum class PlaybackRecoveryAction {
    /** Give up on this failure. */
    NONE,

    /** Load the same locator again: the rejection was timing, not the URL itself. */
    RETRY_SAME_URL,

    /** Resolve a fresh URL from the same source. */
    REFRESH_URL,

    /** Discard transport state and retry with the resource already held. */
    INVALIDATE_NETWORK,

    /** Re-resolve through the account-backed path, refreshing what it holds. */
    REFRESH_TOKEN,

    /** Resolve through another client or the next source in the established order. */
    RETRY_CLIENT,

    /** Re-resolve after reloading the provider's player configuration. */
    RELOAD_PLAYER_CONFIG,

    /** Retry through a fresh decode or output stage with the same resource. */
    REBUILD_RENDERER,
}

/**
 * How much recovery one failed stretch of a song may still spend. A fresh budget allows [REQUESTS]
 * provider re-resolves and [ATTEMPTS] load attempts inside [WINDOW_MS]. A failure reported at least
 * that far beyond the position of the last one means playback moved on, so the budget starts over.
 * Re-resolve requests are the scarce resource; a same-URL retry costs an attempt but no request.
 */
data class PlaybackRecoveryBudget(
    val requestsRemaining: Int,
    val attemptsRemaining: Int,
    val deadlineAtMs: Long,
    val lastFailurePositionMs: Long? = null,
) {
    fun canStart(nowMs: Long): Boolean =
        requestsRemaining > 0 && attemptsRemaining > 0 && nowMs < deadlineAtMs

    /** This budget, or a fresh one when [positionMs] advanced at least [WINDOW_MS] past the last failure. */
    fun forPlaybackFailure(positionMs: Long, nowMs: Long): PlaybackRecoveryBudget {
        val previous = lastFailurePositionMs
        val next = if (previous != null && positionMs - previous >= WINDOW_MS) starting(nowMs) else this
        return next.copy(lastFailurePositionMs = positionMs)
    }

    fun consumeRequest(): PlaybackRecoveryBudget =
        copy(requestsRemaining = (requestsRemaining - 1).coerceAtLeast(0))

    fun consumeAttempt(): PlaybackRecoveryBudget =
        copy(attemptsRemaining = (attemptsRemaining - 1).coerceAtLeast(0))

    companion object {
        const val REQUESTS = 2
        const val ATTEMPTS = 3
        const val WINDOW_MS = 30_000L

        fun starting(nowMs: Long): PlaybackRecoveryBudget =
            PlaybackRecoveryBudget(REQUESTS, ATTEMPTS, nowMs + WINDOW_MS)
    }
}

/** The action [PlaybackRecoveryPolicy.decide] chose, with one attempt already spent from the budget. */
data class PlaybackRecoveryDecision(
    val action: PlaybackRecoveryAction,
    val budget: PlaybackRecoveryBudget,
)

/**
 * The category-to-action table for stream recovery. Every decision spends one attempt of the budget,
 * including the NONE answers an exhausted budget earns. The engine-side actions — same-URL retry,
 * network invalidation, renderer rebuild — keep the resource already resolved; the resolve-side
 * actions go back through the provider chain, so a rejected attempt never reuses its cached URL.
 */
object PlaybackRecoveryPolicy {
    fun decide(
        category: PlaybackFailureCategory,
        budget: PlaybackRecoveryBudget,
        nowMs: Long,
    ): PlaybackRecoveryDecision {
        if (!budget.canStart(nowMs)) {
            return PlaybackRecoveryDecision(PlaybackRecoveryAction.NONE, budget)
        }
        val action = when (category) {
            PlaybackFailureCategory.CDN_FORBIDDEN,
            PlaybackFailureCategory.URL_EXPIRED -> PlaybackRecoveryAction.REFRESH_URL
            PlaybackFailureCategory.TOKEN,
            PlaybackFailureCategory.SABR_ATTESTATION -> PlaybackRecoveryAction.REFRESH_TOKEN
            PlaybackFailureCategory.SABR_PROTOCOL,
            PlaybackFailureCategory.PLAYABILITY,
            PlaybackFailureCategory.UNKNOWN -> PlaybackRecoveryAction.RETRY_CLIENT
            PlaybackFailureCategory.TIMEOUT -> PlaybackRecoveryAction.RETRY_SAME_URL
            PlaybackFailureCategory.NETWORK -> PlaybackRecoveryAction.INVALIDATE_NETWORK
            PlaybackFailureCategory.DECODER,
            PlaybackFailureCategory.RENDERER -> PlaybackRecoveryAction.REBUILD_RENDERER
            PlaybackFailureCategory.RATE_LIMIT,
            PlaybackFailureCategory.UNAVAILABLE -> PlaybackRecoveryAction.NONE
        }
        return PlaybackRecoveryDecision(action, budget.consumeAttempt())
    }
}

/**
 * Maps the flattened failure evidence onto the recovery categories. A carried HTTP status decides
 * first, then each cause name in order, and the reported reason only when nothing else applies.
 * Native engines collapse transport, lease and expiry failures into one reason, which is why the
 * cause names carry the taxonomy the status cannot.
 */
object PlaybackFailureClassifier {
    fun categorize(failure: PlaybackFailure): PlaybackFailureCategory {
        failure.httpStatus?.let { return fromHttpStatus(it) }
        for (cause in failure.causes) {
            val name = cause.substringBefore(':').trim()
            when {
                name.contains("Sabr", ignoreCase = true) -> return
                    if (name.contains("Attestation", ignoreCase = true)) {
                        PlaybackFailureCategory.SABR_ATTESTATION
                    } else {
                        PlaybackFailureCategory.SABR_PROTOCOL
                    }
                name.contains("Timeout", ignoreCase = true) -> return PlaybackFailureCategory.TIMEOUT
                name.contains("Decoder", ignoreCase = true) -> return PlaybackFailureCategory.DECODER
                name.contains("Renderer", ignoreCase = true) -> return PlaybackFailureCategory.RENDERER
                name.contains("Network", ignoreCase = true) || name in NETWORK_CAUSES ->
                    return PlaybackFailureCategory.NETWORK
            }
        }
        return when (failure.reason) {
            PlaybackFailureReason.DECODE_FAILED -> PlaybackFailureCategory.DECODER
            PlaybackFailureReason.OUTPUT_FAILED -> PlaybackFailureCategory.RENDERER
            PlaybackFailureReason.SOURCE_UNAVAILABLE -> PlaybackFailureCategory.UNAVAILABLE
            else -> PlaybackFailureCategory.UNKNOWN
        }
    }

    fun fromHttpStatus(status: Int): PlaybackFailureCategory = when {
        status == 401 || status == 407 -> PlaybackFailureCategory.TOKEN
        status == 403 -> PlaybackFailureCategory.CDN_FORBIDDEN
        status == 404 || status == 410 -> PlaybackFailureCategory.URL_EXPIRED
        status == 408 -> PlaybackFailureCategory.TIMEOUT
        status == 425 -> PlaybackFailureCategory.NETWORK
        status == 429 -> PlaybackFailureCategory.RATE_LIMIT
        status in 500..599 -> PlaybackFailureCategory.NETWORK
        else -> PlaybackFailureCategory.UNKNOWN
    }

    /** Transport failure names the JVM and Darwin engines surface as cause classes. */
    private val NETWORK_CAUSES = setOf(
        "ConnectException",
        "SocketException",
        "UnknownHostException",
        "NoRouteToHostException",
        "UnresolvedAddressException",
        "PortUnreachableException",
    )
}
