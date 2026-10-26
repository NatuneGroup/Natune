/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.lyrics

/**
 * How playback position becomes the position that lyric rows are matched against. Line-only (LRC) timing is
 * written early relative to the sung audio, so it gets a lead. Word-timed rows already line up. The lead and the
 * user's sync offset move the feed only, so row times stay canonical for seeking.
 */
object LyricsFeedTiming {
    const val LRC_LEAD_MS = 300L
    const val TTML_LEAD_MS = 0L

    /** A fixed visual tuning applied on top of the lead, as the Android renderers do. */
    const val VISUAL_TUNING_OFFSET_MS = 150L

    /**
     * The lyric model does not record its container, so the lead follows the timing shape: rows with no word
     * timings are line-only and take the LRC lead.
     */
    fun leadMsFor(rows: List<LyricsRenderRow>): Long =
        if (rows.isNotEmpty() && rows.all { it.words == null }) LRC_LEAD_MS else TTML_LEAD_MS

    fun feedMs(positionMs: Long, syncOffsetMs: Long, leadMs: Long): Long =
        positionMs + syncOffsetMs + leadMs + VISUAL_TUNING_OFFSET_MS
}
