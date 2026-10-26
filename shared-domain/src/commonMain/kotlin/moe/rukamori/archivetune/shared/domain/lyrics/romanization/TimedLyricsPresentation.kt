/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.lyrics.romanization

import moe.rukamori.archivetune.shared.domain.lyrics.LyricsLine

/**
 * The text to display for one row and, per timed word, the range it occupies in that text. A null range
 * means the word has no place in the displayed text.
 */
data class TimedLyricsPresentation(
    val text: String,
    val ranges: List<IntRange?>,
)

/**
 * Chooses the displayed text for a row and locates each timed word in it. Aligned word readings replace
 * the words, a line-level reading replaces the row without invented word ranges, and otherwise the source
 * text is shown with its own word ranges.
 */
fun timedLyricsPresentation(
    line: LyricsLine,
    phonetics: LyricsPhonetics,
): TimedLyricsPresentation {
    val words = line.words.orEmpty().filter { !it.isBackground }
    val aligned = phonetics.words?.takeIf { it.size == words.size }
    val text =
        if (aligned != null) {
            aligned.mapIndexed { index, reading -> reading ?: words[index].text }.joinToString(" ")
        } else {
            phonetics.line ?: line.text
        }
    var offset = 0
    val ranges =
        words.mapIndexed { index, word ->
            if (aligned == null && phonetics.line != null) return@mapIndexed null
            val fragment = if (aligned != null) aligned[index] ?: word.text else word.text
            val start = text.indexOf(fragment, offset)
            if (start < 0 || fragment.isEmpty()) {
                null
            } else {
                offset = start + fragment.length
                start until offset
            }
        }
    return TimedLyricsPresentation(text, ranges)
}
