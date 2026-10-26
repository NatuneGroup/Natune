package moe.rukamori.archivetune.shared.ui.settings

/**
 * One block of published release notes. Only the subset GitHub release bodies use is recognised; anything
 * else stays readable as a paragraph, so an unusual body never disappears.
 */
internal sealed interface ReleaseNoteBlock {
    val spans: List<ReleaseNoteSpan>

    data class Heading(val level: Int, override val spans: List<ReleaseNoteSpan>) : ReleaseNoteBlock
    data class Bullet(override val spans: List<ReleaseNoteSpan>) : ReleaseNoteBlock
    data class Numbered(val number: String, override val spans: List<ReleaseNoteSpan>) : ReleaseNoteBlock
    data class Paragraph(override val spans: List<ReleaseNoteSpan>) : ReleaseNoteBlock
}

/** A run of text with at most one inline treatment. A non-null [url] makes the run a link. */
internal data class ReleaseNoteSpan(
    val text: String,
    val bold: Boolean = false,
    val code: Boolean = false,
    val url: String? = null,
)

private val headingLine = Regex("""^(#{1,6})\s+(.*)$""")
private val bulletLine = Regex("""^\s*[-*+]\s+(.*)$""")
private val numberedLine = Regex("""^\s*(\d{1,3})[.)]\s+(.*)$""")
private val separatorLine = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
private const val URL_TRAILING_PUNCTUATION = ".,;:!?'\""

/**
 * Splits a release body into blocks line by line, as the baseline changelog did. Blank lines and horizontal
 * rules separate nothing and are dropped. Only http and https URLs become links.
 */
internal fun parseReleaseNotes(markdown: String): List<ReleaseNoteBlock> {
    val blocks = ArrayList<ReleaseNoteBlock>()
    for (raw in markdown.lines()) {
        val line = raw.trimEnd()
        if (line.isBlank() || separatorLine.matches(line)) continue
        val heading = headingLine.find(line.trimStart())
        val bullet = bulletLine.find(line)
        val numbered = numberedLine.find(line)
        blocks += when {
            heading != null -> ReleaseNoteBlock.Heading(
                level = heading.groupValues[1].length,
                spans = parseInlineSpans(heading.groupValues[2]),
            )
            bullet != null -> ReleaseNoteBlock.Bullet(parseInlineSpans(bullet.groupValues[1]))
            numbered != null -> ReleaseNoteBlock.Numbered(
                number = numbered.groupValues[1],
                spans = parseInlineSpans(numbered.groupValues[2]),
            )
            else -> ReleaseNoteBlock.Paragraph(parseInlineSpans(line.trim()))
        }
    }
    return blocks
}

/** Inline bold (`**` or `__`), code (backticks), `[label](url)` links and bare http(s) URLs. Unclosed markers stay literal. */
internal fun parseInlineSpans(text: String): List<ReleaseNoteSpan> {
    val spans = ArrayList<ReleaseNoteSpan>()
    val plain = StringBuilder()
    var index = 0
    while (index < text.length) {
        val match = inlineSpanAt(text, index)
        if (match == null) {
            plain.append(text[index])
            index++
        } else {
            if (plain.isNotEmpty()) {
                spans += ReleaseNoteSpan(plain.toString())
                plain.clear()
            }
            spans += match.span
            index = match.nextIndex
        }
    }
    if (plain.isNotEmpty()) spans += ReleaseNoteSpan(plain.toString())
    return spans
}

private class InlineMatch(val span: ReleaseNoteSpan, val nextIndex: Int)

private fun inlineSpanAt(text: String, index: Int): InlineMatch? {
    val c = text[index]
    if ((c == '*' || c == '_') && text.startsWith("$c$c", index)) {
        val end = text.indexOf("$c$c", startIndex = index + 2)
        if (end > index + 2) {
            return InlineMatch(ReleaseNoteSpan(text.substring(index + 2, end), bold = true), end + 2)
        }
        return null
    }
    if (c == '`') {
        val end = text.indexOf('`', startIndex = index + 1)
        if (end > index + 1) {
            return InlineMatch(ReleaseNoteSpan(text.substring(index + 1, end), code = true), end + 1)
        }
        return null
    }
    if (c == '[') {
        val labelEnd = text.indexOf("](", startIndex = index + 1)
        val urlEnd = if (labelEnd > index) text.indexOf(')', startIndex = labelEnd + 2) else -1
        if (labelEnd > index + 1 && urlEnd > labelEnd + 2) {
            val url = text.substring(labelEnd + 2, urlEnd).trim()
            if (url.startsWith("https://") || url.startsWith("http://")) {
                val label = text.substring(index + 1, labelEnd)
                return InlineMatch(ReleaseNoteSpan(label, url = url), urlEnd + 1)
            }
        }
        return null
    }
    if (text.startsWith("https://", index) || text.startsWith("http://", index)) {
        var end = index
        while (end < text.length && !text[end].isWhitespace() && text[end] !in "<>()[]") end++
        while (end > index && text[end - 1] in URL_TRAILING_PUNCTUATION) end--
        if (end >= index + "https://".length + 1) {
            val url = text.substring(index, end)
            return InlineMatch(ReleaseNoteSpan(url, url = url), end)
        }
    }
    return null
}
