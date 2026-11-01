package moe.rukamori.archivetune.shared.ui.lyrics

/**
 * How much earlier than its timed start a row lights up. The lead follows the silence before the
 * line: a tight hand-off (a gap inside [GAP_MIN_MS]..[GAP_MAX_MS]) gets a lead that grows with the
 * gap, and every other line gets a small fixed lead. The word sweep itself is untouched — a word
 * still fills across its real timed span — so a seek lands on the same frame either way.
 */
object LyricsActivation {
    /** Inter-line silences inside this band get the scaled lead. */
    const val GAP_MIN_MS = 200L
    const val GAP_MAX_MS = 750L

    /** The lead at the two ends of the band. */
    const val LEAD_SHORT_GAP_MS = 480L
    const val LEAD_LONG_GAP_MS = 750L

    /** The lead outside the band: the first line, overlapping lines and lines after long silences. */
    const val LEAD_DEFAULT_MS = 250L

    /**
     * The activation lead for a line whose timed start sits [gapMs] after the furthest point the
     * earlier lines reach, or null for the first line. Inside the band the lead lerps from
     * [LEAD_SHORT_GAP_MS] to [LEAD_LONG_GAP_MS]; outside it is [LEAD_DEFAULT_MS].
     */
    fun leadMs(gapMs: Long?): Long =
        if (gapMs != null && gapMs in GAP_MIN_MS..GAP_MAX_MS) {
            LEAD_SHORT_GAP_MS +
                (gapMs - GAP_MIN_MS) * (LEAD_LONG_GAP_MS - LEAD_SHORT_GAP_MS) / (GAP_MAX_MS - GAP_MIN_MS)
        } else {
            LEAD_DEFAULT_MS
        }
}
