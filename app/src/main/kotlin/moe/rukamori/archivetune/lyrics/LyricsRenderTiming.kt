package moe.rukamori.archivetune.lyrics

/**
 * How an Android renderer should time a document, from its TIMING SHAPE rather than from its
 * container.
 *
 * The container flag (`isTtml`) was used to pick the sync lead, but enhanced LRC also carries
 * per-word stamps. Those rows were treated as line-only and pushed forward by [LRC_LEAD_MS] plus
 * the visual tuning, so the sweep ran ~450 ms ahead and a short word was already fully lit when it
 * was sung. Word-timed rows already line up with the audio and need no lead; line-only rows are
 * written early and do. This mirrors the shared `LyricsFeedTiming.leadMsFor` rule.
 */
object LyricsRenderTiming {
    const val LRC_LEAD_MS = 300L
    const val TTML_LEAD_MS = 0L
    const val VISUAL_TUNING_OFFSET_MS = 150L

    /** True when at least one row carries real per-word spans that drive a karaoke sweep. */
    fun hasWordTiming(entries: List<LyricsEntry>): Boolean = entries.any {
        LyricsUtils.hasTrueWordSync(it)
    }

    /**
     * The lead for the whole document: word-timed rows take no lead, line-only rows take the LRC
     * lead.
     */
    fun leadMsFor(entries: List<LyricsEntry>): Long =
        if (hasWordTiming(entries)) TTML_LEAD_MS else LRC_LEAD_MS
}
