package moe.rukamori.archivetune.lyrics

/**
 * Marks the words a line wraps in parentheses as background vocals ([WordTimestamp.isBackground]).
 *
 * TTML states the backing voice explicitly with `ttm:role="x-bg"`, and the renderers draw those words as
 * the smaller line under the lead voice. The enhanced-LRC providers have no such attribute: YouLyPlus's
 * word endpoint, BetterLyrics' LRC fallback and the enhanced-LRC files they mirror all write the backing
 * vocal inline instead, in round brackets — "hold on <stamp>(gimme <stamp>some <stamp>day)". Without
 * this the words arrived as lead words, so the backing vocal either ran the line's sweep with the lead or
 * took the line over as if it were the lead voice.
 *
 * Only a run that opens at a word's own start and closes at a word's own end counts: a bracket in the
 * middle of a word is text, not a voice. An unbalanced open bracket leaves the rest of the line marked,
 * which is what such a line means — the provider truncated it. A whole line in brackets (the featured
 * artist answering on their own timestamp) is marked too; the renderer already publishes a row that is
 * only background as a secondary line rather than a lead one.
 */
internal fun List<WordTimestamp>.withBackgroundRuns(): List<WordTimestamp> {
    if (isEmpty()) return this
    var depth = 0
    var changed = false
    val marked =
        map { word ->
            val opens = word.text.trimStart().startsWith('(')
            val closes = word.text.trimEnd().endsWith(')')
            val inside = depth > 0 || opens
            if (opens) depth++
            if (closes && depth > 0) depth--
            if (inside && !word.isBackground) {
                changed = true
                word.copy(isBackground = true)
            } else {
                word
            }
        }
    return if (changed) marked else this
}
