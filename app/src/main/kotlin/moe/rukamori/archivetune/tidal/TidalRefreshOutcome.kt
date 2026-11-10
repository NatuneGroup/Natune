package moe.rukamori.archivetune.tidal

/** Whether a failed token refresh means the grant is gone, or only that this attempt did not get through. */
internal object TidalRefreshOutcome {
    /** Tidal answers a revoked, expired or foreign refresh token with 400/401; anything else may succeed later. */
    fun isRejection(httpCode: Int): Boolean = httpCode == 400 || httpCode == 401

    /** Classifies one failed exchange; the result travels back with its own attempt, never through shared state. */
    fun failed(httpCode: Int): TidalAccountManager.RefreshResult =
        if (isRejection(httpCode)) TidalAccountManager.RefreshResult.Refused else TidalAccountManager.RefreshResult.Unavailable
}
