package moe.rukamori.archivetune.shared.domain.lyrics.romanization

/**
 * Collapses each run of the characters that `\s` matches in Java regular expressions into one space.
 * The explicit set keeps the result identical on every target; common regex `\s` is not specified the
 * same way everywhere, and it does not match no-break space, which `trim()` does remove.
 */
internal fun collapseAsciiWhitespace(text: String): String {
    val out = StringBuilder(text.length)
    var inRun = false
    for (ch in text) {
        if (ch.isJavaRegexWhitespace()) {
            if (!inRun) out.append(' ')
            inRun = true
        } else {
            out.append(ch)
            inRun = false
        }
    }
    return out.toString()
}

private fun Char.isJavaRegexWhitespace(): Boolean =
    this == ' ' || this == '\t' || this == '\n' || this == '\u000B' || this == '\u000C' || this == '\r'

/**
 * A romanisation is shown only when it is non-blank and differs from the source text, ignoring case and
 * the outer whitespace. Otherwise there is nothing new to show.
 */
internal fun normalizeRomanizedText(
    original: String,
    romanized: String?,
): String? {
    val normalized =
        romanized
            ?.let { collapseAsciiWhitespace(it).trim() }
            ?.takeIf { it.isNotEmpty() }
            ?: return null

    return normalized.takeUnless { it.equals(original.trim(), ignoreCase = true) }
}
