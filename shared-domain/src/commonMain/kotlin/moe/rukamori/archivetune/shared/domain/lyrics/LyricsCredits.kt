package moe.rukamori.archivetune.shared.domain.lyrics

/**
 * Document-level credits a lyric source declares once for the whole document, not per line.
 * [songwriters] are the credited writers in document order. The two flags mark content the source
 * produced automatically, so a surface can badge it rather than presenting it as human work.
 */
data class LyricsCredits(
    val songwriters: List<String> = emptyList(),
    val translationAutoCreated: Boolean = false,
    val transliterationAutoCreated: Boolean = false,
) {
    val isEmpty: Boolean
        get() = songwriters.isEmpty() && !translationAutoCreated && !transliterationAutoCreated
}
