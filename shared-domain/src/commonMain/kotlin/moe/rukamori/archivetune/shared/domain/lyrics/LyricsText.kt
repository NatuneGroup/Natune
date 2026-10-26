/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.lyrics

/** Non-breaking space, which the lyrics readers trim alongside ordinary whitespace. */
internal const val LYRICS_NBSP: Char = '\u00A0'

/** Java `\s+`, the run that LRC word spacing and display text collapse to one space. */
internal val LYRICS_WHITESPACE_RUN = Regex("\\s+")

internal const val MINUTE_MS: Long = 60_000L
internal const val SECOND_MS: Long = 1_000L

internal fun String.trimLyricsSpace(): String = trim { it.isWhitespace() || it == LYRICS_NBSP }

internal fun String.collapseLyricsWhitespace(): String = replace(LYRICS_WHITESPACE_RUN, " ")

/** Encodes one Unicode scalar value as UTF-16 using only common Kotlin primitives. */
internal fun codePointToString(codePoint: Int): String =
    if (codePoint < 0x10000) {
        codePoint.toChar().toString()
    } else {
        val offset = codePoint - 0x10000
        buildString(2) {
            append((0xD800 + (offset shr 10)).toChar())
            append((0xDC00 + (offset and 0x3FF)).toChar())
        }
    }

/**
 * Renders a millisecond count as seconds with three decimals, matching `%.3f` of `ms / 1000.0`
 * for integer millisecond input, using plain integer arithmetic rather than a locale-based formatter.
 */
internal fun formatSecondsFromMs(ms: Long): String {
    val magnitude = if (ms < 0) ms.unaryMinus().toULong() else ms.toULong()
    val whole = magnitude / 1000uL
    val fraction = (magnitude % 1000uL).toString().padStart(3, '0')
    return (if (ms < 0) "-" else "") + whole.toString() + "." + fraction
}
