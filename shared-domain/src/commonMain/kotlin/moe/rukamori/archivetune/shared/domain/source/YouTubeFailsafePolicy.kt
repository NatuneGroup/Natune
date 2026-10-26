package moe.rukamori.archivetune.shared.domain.source

import kotlin.coroutines.cancellation.CancellationException

/**
 * The fallback tiers that run after the shipped `YTPlayerUtils` resolver fails, in the order they are
 * tried. Echo is last because it is the most expensive, and it stays last as a deliberate last resort.
 */
enum class YouTubeFallbackTier(val timeoutMs: Long) {
    INNERTUBE_X(YouTubeFailsafePolicy.TIER_TIMEOUT_MS),
    NEWPIPE(YouTubeFailsafePolicy.TIER_TIMEOUT_MS),
    ECHO(YouTubeFailsafePolicy.ECHO_TIMEOUT_MS),
}

/** How the shipped primary resolver failed. Only the kinds the fallback decision depends on are named. */
enum class YouTubeResolveFailure {
    /** The playback login context is invalid. The listener can act on it, so no fallback can fix it. */
    INVALID_LOGIN_CONTEXT,

    /** Playback needs a sign-in. The listener can act on it, so no fallback can fix it. */
    LOGIN_REQUIRED,

    /** The caller was cancelled. A cancelled request must stop, not fall through to another tier. */
    CANCELLED,

    /** Any other failure. The fallback tiers may still recover it. */
    OTHER,
}

/** What the caller should do after a primary failure. */
sealed interface YouTubeRecovery<out T> {
    /** A fallback tier resolved the stream. */
    data class Resolved<T>(val tier: YouTubeFallbackTier, val value: T) : YouTubeRecovery<T>

    /** No tier recovered it, so the caller must rethrow its original primary failure. */
    data object RethrowPrimary : YouTubeRecovery<Nothing>
}

object YouTubeFailsafePolicy {
    /** Per extractor tier. A tier that needs longer than this is not one worth waiting for. */
    const val TIER_TIMEOUT_MS = 12_000L

    /** Echo parses the player script with a real JavaScript parser on first use, so it gets longer. */
    const val ECHO_TIMEOUT_MS = 20_000L

    /**
     * The login and confirmation failures every fallback tier would hit the same way. They skip all of
     * them and surface the actionable message.
     */
    fun isActionable(failure: YouTubeResolveFailure): Boolean =
        failure == YouTubeResolveFailure.INVALID_LOGIN_CONTEXT || failure == YouTubeResolveFailure.LOGIN_REQUIRED

    /** Whether the primary failure must stop before any fallback tier runs. */
    fun stopsFallbacks(failure: YouTubeResolveFailure): Boolean =
        isActionable(failure) || failure == YouTubeResolveFailure.CANCELLED

    /**
     * Walks the fallback tiers after a primary failure. [attempt] resolves one tier within the given
     * timeout and returns null when that tier failed or timed out. A timeout must come back as null
     * (`withTimeoutOrNull`), because a thrown timeout is a cancellation and would end the walk. It throws
     * only when the caller itself was cancelled, and that cancellation propagates. Any other exception
     * from a tier counts as a failed tier, and the walk moves on.
     */
    suspend fun <T : Any> recoverAfterPrimary(
        primaryFailure: YouTubeResolveFailure,
        attempt: suspend (tier: YouTubeFallbackTier, timeoutMs: Long) -> T?,
    ): YouTubeRecovery<T> {
        if (stopsFallbacks(primaryFailure)) return YouTubeRecovery.RethrowPrimary
        for (tier in YouTubeFallbackTier.entries) {
            val value =
                try {
                    attempt(tier, tier.timeoutMs)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    null
                }
            if (value != null) return YouTubeRecovery.Resolved(tier, value)
        }
        return YouTubeRecovery.RethrowPrimary
    }
}
