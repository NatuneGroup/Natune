package moe.rukamori.archivetune.shared.data.lyrics

/**
 * The provider order of the Android `LyricsHelper.baseProviders` list, restricted to the providers
 * ported into this module, with the built-in Lyprio server first as in Metrolist's default order. The app-only providers (LRC.red, Bini, Megalobiz, Apple Music account,
 * Tidal, Deezer, YouTube, Musixmatch experimental, Genius) keep their Android positions in the app.
 * Relative order is what matters: a host that merges app providers keeps this subsequence intact.
 */
object LyricsProviderOrder {
    val CANONICAL_NAMES: List<String> =
        listOf(
            "Lyprio",
            "BetterLyrics",
            "BetterLyrics Portato",
            "YouLyPlus",
            "LrcLib",
            "Kugou",
            "SimpMusic",
            "Unison",
            "Paxsenix: Apple Music",
            "Paxsenix: NetEase",
            "Paxsenix: Spotify",
            "Paxsenix: Musixmatch",
            "Paxsenix: YouTube",
        )

    /**
     * The providers the Android priority sweep queries, in its fixed order. The sweep ignores the
     * user's priority list, so the word-synced result does not depend on it.
     */
    internal val WORD_SYNC_NAMES: List<String> = listOf("BetterLyrics", "YouLyPlus", "Unison")

    /** Stable sort into [CANONICAL_NAMES]. Providers with unknown names keep their input order at the end. */
    fun sortCanonical(providers: List<LyricsProvider>): List<LyricsProvider> =
        providers.sortedBy { provider ->
            CANONICAL_NAMES.indexOf(provider.name).let { if (it < 0) Int.MAX_VALUE else it }
        }
}
