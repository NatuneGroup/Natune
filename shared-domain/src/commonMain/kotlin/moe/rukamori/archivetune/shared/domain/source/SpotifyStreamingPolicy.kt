/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.source

/** Why a streaming sign-in was refused. Nothing is stored when any of these applies. */
enum class SpotifySignInRefusal {
    /** The streaming session is the catalog account, so the two would be one account. */
    SAME_AS_CATALOG,

    /** The cookie did not mint a signed-in session. */
    INVALID_SESSION,

    /** The streaming account could not be identified, so separation cannot be checked. */
    PROFILE_UNAVAILABLE,

    /** The catalog is signed in but its account could not be read, so the two cannot be compared. */
    CATALOG_UNVERIFIABLE,
}

sealed interface SpotifySignInDecision {
    data object Accept : SpotifySignInDecision

    data class Refuse(val reason: SpotifySignInRefusal) : SpotifySignInDecision
}

/**
 * The Spotify catalog and streaming accounts are two accounts. The streaming source must stand down
 * when both sign in as the same person, and the catalog token is used only for that comparison.
 */
object SpotifyStreamingPolicy {
    /**
     * Whether streaming must stand down because the catalog is signed in as the streaming account.
     *
     * The same cookie is a collision at once. Otherwise the user ids decide. [catalogUserId] is read only
     * when a comparison is still possible, and it returns null when the catalog token or profile is
     * unavailable. An unknown catalog account fails open here, so streaming keeps working. The sign-in
     * step is the one that refuses unverifiable pairs.
     */
    fun streamingStandsDown(
        catalogSpDc: String,
        streamingSpDc: String,
        streamingUserId: String,
        catalogUserId: () -> String?,
    ): Boolean {
        if (catalogSpDc.isBlank()) return false
        if (catalogSpDc == streamingSpDc) return true
        if (streamingUserId.isBlank()) return false
        val catalog = catalogUserId() ?: return false
        return catalog == streamingUserId
    }

    /** A bearer token may be handed out only for a signed-in streaming account that is not standing down. */
    fun accessTokenAllowed(streamingSpDc: String, standsDown: Boolean): Boolean =
        streamingSpDc.isNotBlank() && !standsDown

    /**
     * Decides whether a freshly captured streaming session may be stored. The checks run in a fixed
     * order, and the first failing one is the refusal reported.
     *
     * @param tokenMinted Whether the cookie produced a signed-in, non-anonymous token.
     * @param streamingUserId The streaming profile's user id, or null when the profile is unreadable.
     * @param catalogUserId Reads the catalog account's user id. It runs only when the catalog is signed in.
     */
    fun decideSignIn(
        catalogSpDc: String,
        candidateSpDc: String,
        tokenMinted: Boolean,
        streamingUserId: String?,
        catalogUserId: () -> String?,
    ): SpotifySignInDecision {
        if (catalogSpDc.isNotBlank() && catalogSpDc == candidateSpDc) {
            return SpotifySignInDecision.Refuse(SpotifySignInRefusal.SAME_AS_CATALOG)
        }
        if (!tokenMinted) return SpotifySignInDecision.Refuse(SpotifySignInRefusal.INVALID_SESSION)
        val streaming = streamingUserId ?: return SpotifySignInDecision.Refuse(SpotifySignInRefusal.PROFILE_UNAVAILABLE)
        if (catalogSpDc.isNotBlank()) {
            val catalog = catalogUserId() ?: return SpotifySignInDecision.Refuse(SpotifySignInRefusal.CATALOG_UNVERIFIABLE)
            if (catalog == streaming) return SpotifySignInDecision.Refuse(SpotifySignInRefusal.SAME_AS_CATALOG)
        }
        return SpotifySignInDecision.Accept
    }
}
