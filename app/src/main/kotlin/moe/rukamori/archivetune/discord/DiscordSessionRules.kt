package moe.rukamori.archivetune.discord

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

/** Decisions about a saved Discord session. No Android types, so JVM unit tests can cover them. */
internal object DiscordSessionRules {
    /** Renew this long before expiry so a token does not lapse while a presence request is in flight. */
    const val EXPIRY_SKEW_MS = 60_000L

    /** 400 is `invalid_grant`, the answer for a revoked or spent refresh token. 401 and 403 reject the client or grant. */
    private val REFUSED_REFRESH_STATUSES = setOf(400, 401, 403)

    /** A token without a recorded expiry is treated as current. */
    fun isRenewalDue(expiresAtMillis: Long, nowMillis: Long): Boolean =
        expiresAtMillis != 0L && nowMillis + EXPIRY_SKEW_MS >= expiresAtMillis

    /**
     * A refresh response without a refresh token keeps the one that was used. Erasing it would leave
     * the session unable to renew at the next expiry.
     */
    fun refreshTokenToKeep(grantedRefreshToken: String?, usedRefreshToken: String?): String? =
        grantedRefreshToken?.takeIf(String::isNotBlank) ?: usedRefreshToken?.takeIf(String::isNotBlank)

    /**
     * Only a refused grant, or no refresh token to try, means the user has to authorize again.
     * Network, timeout and service errors leave the saved session in place.
     */
    fun needsAuthorization(error: Throwable?): Boolean =
        error is DiscordAuthorizationRequiredException ||
            (error is DiscordOAuthHttpException && error.statusCode in REFUSED_REFRESH_STATUSES)
}

/**
 * [runCatching] for token refreshes. A caller's cancellation still propagates. A timeout inside the
 * SDK bridge is a failed refresh, so it must not escape as a cancellation of the caller.
 */
internal inline fun <T> refreshCatching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (error: TimeoutCancellationException) {
        Result.failure(error)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }
