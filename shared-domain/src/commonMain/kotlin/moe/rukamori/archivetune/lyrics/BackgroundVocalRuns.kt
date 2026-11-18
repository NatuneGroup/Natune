package moe.rukamori.archivetune.lyrics

/**
 * Marks the words a line wraps in parentheses as background vocals ([WordTimestamp.isBackground]),
 * inside an enhanced-LRC line and nowhere else.
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
 * which is what such a line means — the provider truncated it.
 *
 * A line whose words all sit inside brackets is not a backing voice under a lead one: it is the source's
 * own phrasing, a sung lead line such as "(I know)", and it stays lead. The convention only marks a run
 * with lead words outside it, and it only runs here, on the enhanced-LRC shape these providers emit; a
 * plain LRC line's parentheses are lyric text, and an explicit provider role (TTML's `x-bg`) has already
 * said what it means before this point.
 */
internal fun List<WordTimestamp>.withBackgroundRuns(): List<WordTimestamp> {
    if (isEmpty()) return this
    val bracketed = bracketDepth()
    // Every word inside a bracket run: the whole line is one parenthesised phrase, so nothing here is
    // a backing voice and the line keeps its lead words.
    if (bracketed.all { it }) return this
    var changed = false
    val marked =
        mapIndexed { index, word ->
            if (bracketed[index] && !word.isBackground) {
                changed = true
                word.copy(isBackground = true)
            } else {
                word
            }
        }
    return if (changed) marked else this
}

/**
 * Which words sit inside a bracket run, by the same rule the marking uses: a word opens a run when its
 * own text starts with `(`, and closes it when its own text ends with `)`.
 */
private fun List<WordTimestamp>.bracketDepth(): List<Boolean> {
    var depth = 0
    return map { word ->
        val opens = word.text.trimStart().startsWith('(')
        val closes = word.text.trimEnd().endsWith(')')
        val inside = depth > 0 || opens
        if (opens) depth++
        if (closes && depth > 0) depth--
        inside
    }
}
