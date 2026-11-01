package moe.rukamori.archivetune.shared.domain.source

/**
 * The Spotify subscription tier, as the Web API `/v1/me` `product` field reports it and as the
 * access-point `type` attribute names it after a librespot-style login. Both use the same values.
 */
enum class SpotifyProduct {
    /** Individual, Family, Duo, Student and trial subscriptions all report "premium". */
    PREMIUM,

    /** Any other observed product, including a lapsed subscription ("free", "open" and the like). */
    FREE,

    /** The product has not been observed yet. An absent read is never a Premium. */
    UNKNOWN,
    ;

    companion object {
        /** Maps the raw product string; a blank or missing read stays [UNKNOWN] rather than guessing a tier. */
        fun of(productField: String?): SpotifyProduct = when {
            productField == null -> UNKNOWN
            productField.equals("premium", ignoreCase = true) -> PREMIUM
            productField.isBlank() -> UNKNOWN
            else -> FREE
        }
    }
}

/**
 * Premium gating for the Librespot engine. The product is observed once per account and cached: a stored
 * observation expires after [PRODUCT_RECHECK_TTL_MS] and is re-read when a fresh access token is minted,
 * so a lapsed subscription is caught on the refresh path without polling.
 */
object SpotifyPremiumPolicy {
    /** How long a non-unknown product observation may serve before a refresh-time re-read. */
    const val PRODUCT_RECHECK_TTL_MS: Long = 12L * 60 * 60 * 1000

    fun isPremium(product: SpotifyProduct): Boolean = product == SpotifyProduct.PREMIUM

    /**
     * Whether the account's product should be read again. Always true before the first observation and while
     * the stored value is unknown; after a token refresh the read is due once the stored observation is older
     * than the TTL.
     */
    fun recheckDue(
        product: SpotifyProduct,
        checkedAtMs: Long,
        nowMs: Long,
        tokenJustMinted: Boolean,
    ): Boolean =
        when {
            product == SpotifyProduct.UNKNOWN -> true
            checkedAtMs <= 0L -> true
            tokenJustMinted && nowMs - checkedAtMs >= PRODUCT_RECHECK_TTL_MS -> true
            else -> false
        }

    /** The Librespot engine is offered only while the bound account is observed Premium. */
    fun librespotAllowed(product: SpotifyProduct): Boolean = product == SpotifyProduct.PREMIUM

    /**
     * The engine that may actually run for a stored choice. Librespot falls back to [SpotifyStreamEngine.UNOFFICIAL]
     * while the bound account is not observed Premium — the graceful subscription-loss path. The stored choice is
     * kept, so the engine returns to Librespot once the plan reads Premium again; this never retries in a loop.
     */
    fun effectiveEngine(
        stored: SpotifyStreamEngine,
        boundAccountProduct: SpotifyProduct,
    ): SpotifyStreamEngine =
        if (stored == SpotifyStreamEngine.LIBRESPOT && !librespotAllowed(boundAccountProduct)) {
            SpotifyStreamEngine.UNOFFICIAL
        } else {
            stored
        }
}
