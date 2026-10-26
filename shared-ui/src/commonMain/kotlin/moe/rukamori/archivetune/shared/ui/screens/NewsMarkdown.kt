/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Article Markdown with the baseline Markwon plugin set: CommonMark blocks (nested lists and quotes, code,
 * headings, rules, standalone images), GFM tables, task lists, strikethrough, autolinks and bare URLs.
 * Raw HTML is shown as text and never interpreted; links and images go through the injected host policies.
 */
@Composable
internal fun NewsMarkdown(markdown: String, openLink: (String) -> Unit, image: NewsImage, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { parseArticleMarkdown(markdown) }
    // The baseline article scales the body line height, not the font size, so the leading matches its layout.
    val bodyStyle = MaterialTheme.typography.bodyLarge.let { it.copy(lineHeight = it.lineHeight * 1.2f) }
    val env = ArticleEnv(
        textColor = MaterialTheme.colorScheme.onSurfaceVariant,
        linkColor = MaterialTheme.colorScheme.primary,
        bodyStyle = bodyStyle,
        openLink = openLink,
        image = image,
    )
    SelectionContainer(modifier) {
        ArticleBlocks(blocks, env, 16.dp, Modifier.fillMaxWidth())
    }
}

private sealed interface ArticleBlock {
    data class Heading(val level: Int, val text: String) : ArticleBlock
    data class Paragraph(val text: String) : ArticleBlock
    data class Quote(val blocks: List<ArticleBlock>) : ArticleBlock
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<ArticleItem>) : ArticleBlock
    data class Code(val text: String) : ArticleBlock
    data class Table(val alignments: List<ArticleAlign>, val header: List<String>, val rows: List<List<String>>) : ArticleBlock
    data class Image(val uri: String) : ArticleBlock
    object Rule : ArticleBlock
}

/** [checked] is null for ordinary items and carries the state of a GFM task item. */
private class ArticleItem(val checked: Boolean?, val blocks: List<ArticleBlock>)

private enum class ArticleAlign { Start, Center, End }

private class ArticleEnv(
    val textColor: Color,
    val linkColor: Color,
    val bodyStyle: TextStyle,
    val openLink: (String) -> Unit,
    val image: NewsImage,
)

private class ArticleFence(val marker: Char, val length: Int)

private class ArticleListMarker(val ordered: Boolean, val number: Int, val delimiter: Char, val width: Int)

private fun parseArticleMarkdown(markdown: String): List<ArticleBlock> =
    articleBlocks(markdown.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ").split('\n'))

private fun articleBlocks(lines: List<String>): List<ArticleBlock> {
    val blocks = ArrayList<ArticleBlock>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (line.isBlank()) {
            i++
            continue
        }
        val body = line.trimStart(' ')
        val indent = line.length - body.length
        val fence = articleFenceOpener(body)
        val heading = articleAtxHeading(body)
        val marker = articleListMarker(body)
        val tableAlignments = articleTableStartsAt(lines, i)
        when {
            indent >= 4 -> {
                val code = ArrayList<String>()
                while (i < lines.size && (lines[i].isBlank() || articleIndent(lines[i]) >= 4)) {
                    code.add(if (lines[i].isBlank()) "" else lines[i].substring(4))
                    i++
                }
                blocks.add(ArticleBlock.Code(code.dropLastWhile { it.isEmpty() }.joinToString("\n")))
            }
            fence != null -> {
                val code = ArrayList<String>()
                i++
                while (i < lines.size && !articleIsFenceClose(lines[i], fence)) {
                    code.add(articleDropIndent(lines[i], indent))
                    i++
                }
                if (i < lines.size) i++
                blocks.add(ArticleBlock.Code(code.joinToString("\n")))
            }
            heading != null -> {
                blocks.add(ArticleBlock.Heading(heading.first, heading.second))
                i++
            }
            articleIsThematicBreak(body) -> {
                blocks.add(ArticleBlock.Rule)
                i++
            }
            body.startsWith(">") -> {
                val quoted = ArrayList<String>()
                while (i < lines.size && lines[i].isNotBlank()) {
                    val current = lines[i]
                    val currentBody = current.trimStart(' ')
                    if (articleIndent(current) < 4 && currentBody.startsWith(">")) {
                        quoted.add(currentBody.substring(1).removePrefix(" "))
                    } else if (quoted.isNotEmpty() && quoted.last().isNotBlank() && !articleStartsBlock(current, true)) {
                        quoted.add(currentBody)
                    } else {
                        break
                    }
                    i++
                }
                blocks.add(ArticleBlock.Quote(articleBlocks(quoted)))
            }
            marker != null -> {
                val items = ArrayList<ArticleItem>()
                var current: ArticleListMarker = marker
                while (true) {
                    val (item, next) = articleListItem(lines, i, current)
                    items.add(item)
                    i = next
                    var probe = i
                    while (probe < lines.size && lines[probe].isBlank()) probe++
                    val candidate = if (probe < lines.size) lines[probe] else ""
                    val candidateBody = candidate.trimStart(' ')
                    val sibling = if (articleIndent(candidate) < 4 && !articleIsThematicBreak(candidateBody)) {
                        articleListMarker(candidateBody)
                    } else {
                        null
                    }
                    if (sibling == null || sibling.ordered != marker.ordered || sibling.delimiter != marker.delimiter) break
                    current = sibling
                    i = probe
                }
                blocks.add(ArticleBlock.ListBlock(marker.ordered, marker.number, items))
            }
            tableAlignments != null -> {
                val header = articleCells(line, tableAlignments.size)
                val rows = ArrayList<List<String>>()
                i += 2
                while (i < lines.size && lines[i].isNotBlank() && !articleStartsBlock(lines[i], true)) {
                    rows.add(articleCells(lines[i], tableAlignments.size))
                    i++
                }
                blocks.add(ArticleBlock.Table(tableAlignments, header, rows))
            }
            else -> {
                val parts = ArrayList<String>()
                var setext = 0
                while (i < lines.size && lines[i].isNotBlank()) {
                    val current = lines[i]
                    if (parts.isNotEmpty()) {
                        val underline = if (articleIndent(current) < 4) articleSetextLevel(current.trim()) else 0
                        if (underline > 0) {
                            setext = underline
                            i++
                            break
                        }
                        if (articleStartsBlock(current, true) || articleTableStartsAt(lines, i) != null) break
                    }
                    parts.add(current)
                    i++
                }
                val text = articleJoinParagraph(parts)
                val uri = if (setext == 0) articleStandaloneImage(text) else null
                blocks.add(
                    when {
                        setext > 0 -> ArticleBlock.Heading(setext, text)
                        uri != null -> ArticleBlock.Image(uri)
                        else -> ArticleBlock.Paragraph(text)
                    },
                )
            }
        }
    }
    return blocks
}

/** Parses one list item starting at [start]; returns the item and the index of the first line after it. */
private fun articleListItem(lines: List<String>, start: Int, marker: ArticleListMarker): Pair<ArticleItem, Int> {
    val line = lines[start]
    val indent = articleIndent(line)
    val afterMarker = line.trimStart(' ').substring(marker.width)
    val spaces = afterMarker.length - afterMarker.trimStart(' ').length
    val gap = if (afterMarker.isBlank() || spaces > 4) 1 else spaces
    val base = indent + marker.width + gap
    val content = when {
        afterMarker.isBlank() -> ""
        spaces > 4 -> afterMarker.substring(1)
        else -> afterMarker.substring(spaces)
    }
    val itemLines = arrayListOf(content)
    var i = start + 1
    var afterBlank = false
    while (i < lines.size) {
        val next = lines[i]
        if (next.isBlank()) {
            itemLines.add("")
            afterBlank = true
        } else if (articleIndent(next) >= base) {
            itemLines.add(articleDropIndent(next, base))
            afterBlank = false
        } else if (!afterBlank && itemLines.last().isNotBlank() && !articleStartsBlock(next, true)) {
            itemLines.add(next.trimStart(' '))
        } else {
            break
        }
        i++
    }
    var checked: Boolean? = null
    val first = itemLines[0]
    if (first.length >= 3 && first[0] == '[' && first[2] == ']' && (first[1] == ' ' || first[1] == 'x' || first[1] == 'X') &&
        (first.length == 3 || first[3] == ' ')
    ) {
        checked = first[1] != ' '
        itemLines[0] = first.substring(3).trimStart(' ')
    }
    return Pair(ArticleItem(checked, articleBlocks(itemLines)), i)
}

private fun articleIndent(line: String): Int = line.length - line.trimStart(' ').length

private fun articleDropIndent(line: String, max: Int): String {
    var count = 0
    while (count < max && count < line.length && line[count] == ' ') count++
    return line.substring(count)
}

/** Whether [line] opens a block that interrupts a paragraph or ends a lazy container continuation. */
private fun articleStartsBlock(line: String, inParagraph: Boolean): Boolean {
    if (articleIndent(line) >= 4) return false
    val body = line.trimStart(' ')
    if (articleFenceOpener(body) != null || articleAtxHeading(body) != null || articleIsThematicBreak(body) || body.startsWith(">")) {
        return true
    }
    val marker = articleListMarker(body) ?: return false
    return !inParagraph || ((!marker.ordered || marker.number == 1) && body.substring(marker.width).isNotBlank())
}

private fun articleFenceOpener(body: String): ArticleFence? {
    val marker = body.firstOrNull() ?: return null
    if (marker != '`' && marker != '~') return null
    val length = body.takeWhile { it == marker }.length
    if (length < 3) return null
    if (marker == '`' && body.substring(length).contains('`')) return null
    return ArticleFence(marker, length)
}

private fun articleIsFenceClose(line: String, fence: ArticleFence): Boolean {
    if (articleIndent(line) >= 4) return false
    val closing = line.trim()
    return closing.length >= fence.length && closing.all { it == fence.marker }
}

private fun articleAtxHeading(body: String): Pair<Int, String>? {
    val level = body.takeWhile { it == '#' }.length
    if (level !in 1..6) return null
    if (body.length > level && body[level] != ' ') return null
    val content = body.substring(level).trim()
    val withoutClosing = content.trimEnd('#')
    val text = if (withoutClosing.isEmpty() || withoutClosing.endsWith(' ')) withoutClosing.trimEnd() else content
    return Pair(level, text)
}

private fun articleIsThematicBreak(body: String): Boolean {
    val marks = body.filter { it != ' ' }
    return marks.length >= 3 && (marks.all { it == '-' } || marks.all { it == '*' } || marks.all { it == '_' })
}

private fun articleSetextLevel(underline: String): Int = when {
    underline.isNotEmpty() && underline.all { it == '=' } -> 1
    underline.isNotEmpty() && underline.all { it == '-' } -> 2
    else -> 0
}

private fun articleListMarker(body: String): ArticleListMarker? {
    val first = body.firstOrNull() ?: return null
    if (first == '-' || first == '+' || first == '*') {
        return if (body.length == 1 || body[1] == ' ') ArticleListMarker(false, 0, first, 1) else null
    }
    val digits = body.takeWhile { it in '0'..'9' }
    if (digits.isEmpty() || digits.length > 9 || digits.length == body.length) return null
    val delimiter = body[digits.length]
    if (delimiter != '.' && delimiter != ')') return null
    val width = digits.length + 1
    return if (body.length == width || body[width] == ' ') {
        ArticleListMarker(true, digits.toInt(), delimiter, width)
    } else {
        null
    }
}

/** Column alignments when a GFM table header and delimiter row start at [index]. */
private fun articleTableStartsAt(lines: List<String>, index: Int): List<ArticleAlign>? {
    if (index + 1 >= lines.size || articleIndent(lines[index]) >= 4) return null
    val header = lines[index]
    val delimiter = lines[index + 1]
    if (!header.contains('|') || !delimiter.contains('|')) return null
    val alignments = articleDelimiterCells(delimiter) ?: return null
    return if (articleSplitRow(header).size == alignments.size) alignments else null
}

private fun articleDelimiterCells(line: String): List<ArticleAlign>? {
    val alignments = ArrayList<ArticleAlign>()
    for (cell in articleSplitRow(line)) {
        val core = cell.removePrefix(":").removeSuffix(":")
        if (core.isEmpty() || core.any { it != '-' }) return null
        alignments.add(
            when {
                cell.startsWith(":") && cell.endsWith(":") -> ArticleAlign.Center
                cell.endsWith(":") -> ArticleAlign.End
                else -> ArticleAlign.Start
            },
        )
    }
    return alignments
}

/** Splits a table row on unescaped pipes; escaped pipes stay in the cell text for the inline pass. */
private fun articleSplitRow(line: String): List<String> {
    var row = line.trim()
    if (row.startsWith("|")) row = row.substring(1)
    if (row.endsWith("|") && !row.endsWith("\\|")) row = row.dropLast(1)
    val cells = ArrayList<String>()
    var current = ""
    var escaped = false
    for (ch in row) {
        if (ch == '|' && !escaped) {
            cells.add(current.trim())
            current = ""
        } else {
            current += ch
        }
        escaped = ch == '\\' && !escaped
    }
    cells.add(current.trim())
    return cells
}

private fun articleCells(line: String, count: Int): List<String> {
    val cells = articleSplitRow(line)
    return List(count) { cells.getOrElse(it) { "" } }
}

private fun articleJoinParagraph(parts: List<String>): String {
    val text = StringBuilder()
    parts.forEachIndexed { index, raw ->
        val content = raw.trim()
        val last = index == parts.lastIndex
        val hardBreak = !last && (raw.endsWith("  ") || content.endsWith("\\"))
        text.append(if (hardBreak && content.endsWith("\\")) content.dropLast(1) else content)
        if (!last) text.append(if (hardBreak) '\n' else ' ')
    }
    return text.toString()
}

private fun articleStandaloneImage(text: String): String? {
    if (!text.startsWith("![") || !text.endsWith(")")) return null
    val split = text.indexOf("](", 2)
    if (split < 0) return null
    val destination = text.substring(split + 2, text.length - 1)
    if (destination.contains('(') || destination.contains(')')) return null
    return destination.trim().removePrefix("<").substringBefore(' ').removeSuffix(">").takeIf { it.isNotEmpty() }
}

private fun AnnotatedString.Builder.appendArticleInline(text: String, style: SpanStyle, link: String?, env: ArticleEnv) {
    var plain = StringBuilder()
    fun flush() {
        if (plain.isNotEmpty()) appendArticleRun(plain.toString(), style, link, env)
        plain = StringBuilder()
    }
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '\\' && i + 1 < text.length && articleIsAsciiPunctuation(text[i + 1]) -> {
                plain.append(text[i + 1])
                i += 2
            }
            c == '`' -> {
                val runLength = articleRunLength(text, i, '`')
                val close = articleFindRun(text, i + runLength, '`', runLength)
                if (close < 0) {
                    plain.append("`".repeat(runLength))
                    i += runLength
                } else {
                    flush()
                    val code = articleCodeSpanText(text.substring(i + runLength, close))
                    appendArticleRun(code, style + SpanStyle(fontFamily = FontFamily.Monospace), link, env)
                    i = close + runLength
                }
            }
            c == '<' -> {
                val end = text.indexOf('>', i + 1)
                if (end > 0 && articleIsAutolink(text.substring(i + 1, end))) {
                    val target = text.substring(i + 1, end)
                    flush()
                    val href = if (target.contains('@') && !target.contains(':')) "mailto:$target" else target
                    appendArticleRun(target, style, link ?: href, env)
                    i = end + 1
                } else {
                    plain.append(c)
                    i++
                }
            }
            c == '!' && text.startsWith("![", i) -> {
                val close = articleClosing(text, i + 1, '[', ']')
                val end = if (close > 0 && text.getOrNull(close + 1) == '(') articleClosing(text, close + 1, '(', ')') else -1
                if (end > 0) {
                    flush()
                    appendArticleInline(text.substring(i + 2, close), style + SpanStyle(fontStyle = FontStyle.Italic), link, env)
                    i = end + 1
                } else {
                    plain.append(c)
                    i++
                }
            }
            c == '[' -> {
                val close = articleClosing(text, i, '[', ']')
                val end = if (close > 0 && text.getOrNull(close + 1) == '(') articleClosing(text, close + 1, '(', ')') else -1
                if (end > 0) {
                    flush()
                    val target = articleLinkTarget(text.substring(close + 2, end))
                    appendArticleInline(text.substring(i + 1, close), style, link ?: target.takeIf { it.isNotEmpty() }, env)
                    i = end + 1
                } else {
                    plain.append(c)
                    i++
                }
            }
            c == '*' || c == '_' || c == '~' -> {
                val runLength = articleRunLength(text, i, c)
                val before = text.getOrNull(i - 1)
                val after = text.getOrNull(i + runLength)
                val canOpen = after != null && !after.isWhitespace() && (c != '_' || before == null || !articleIsAsciiAlnum(before))
                val maxRun = if (c == '~') 2 else 3
                val close = if (runLength <= maxRun && canOpen) articleFindCloser(text, i + runLength, c, runLength) else -1
                if (close < 0) {
                    plain.append(text.substring(i, i + runLength))
                    i += runLength
                } else {
                    flush()
                    val emphasis = when {
                        c == '~' -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                        runLength == 1 -> SpanStyle(fontStyle = FontStyle.Italic)
                        runLength == 2 -> SpanStyle(fontWeight = FontWeight.Bold)
                        else -> SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
                    }
                    appendArticleInline(text.substring(i + runLength, close), style + emphasis, link, env)
                    i = close + runLength
                }
            }
            (c == 'h' || c == 'w') && link == null && articleStartsUrl(text, i) && (i == 0 || !articleIsAsciiAlnum(text[i - 1])) -> {
                val url = articleTrimUrl(text.substring(i).takeWhile { !it.isWhitespace() && it != '<' && it != '>' })
                val bare = url.startsWith("www.")
                if (url.length > (if (bare) 4 else 8)) {
                    flush()
                    appendArticleRun(url, style, if (bare) "http://$url" else url, env)
                    i += url.length
                } else {
                    plain.append(c)
                    i++
                }
            }
            c == '&' -> {
                val semi = text.indexOf(';', i + 1)
                val decoded = if (semi in (i + 2)..(i + 33)) articleDecodeEntity(text.substring(i + 1, semi)) else null
                if (decoded != null) {
                    plain.append(decoded)
                    i = semi + 1
                } else {
                    plain.append(c)
                    i++
                }
            }
            else -> {
                plain.append(c)
                i++
            }
        }
    }
    flush()
}

private fun AnnotatedString.Builder.appendArticleRun(text: String, style: SpanStyle, link: String?, env: ArticleEnv) {
    if (link == null) {
        withStyle(style) { append(text) }
    } else {
        withLink(LinkAnnotation.Clickable(link, TextLinkStyles(SpanStyle(color = env.linkColor))) { env.openLink(link) }) {
            withStyle(style) { append(text) }
        }
    }
}

private fun articleAnnotated(text: String, env: ArticleEnv): AnnotatedString =
    buildAnnotatedString { appendArticleInline(text, SpanStyle(), null, env) }

private fun articleRunLength(text: String, start: Int, ch: Char): Int {
    var end = start
    while (end < text.length && text[end] == ch) end++
    return end - start
}

/** Index of the next backtick run of exactly [runLength] at or after [from], or -1. */
private fun articleFindRun(text: String, from: Int, ch: Char, runLength: Int): Int {
    var k = from
    while (k < text.length) {
        if (text[k] != ch) {
            k++
            continue
        }
        val length = articleRunLength(text, k, ch)
        if (length == runLength) return k
        k += length
    }
    return -1
}

/** Closing delimiter run for an emphasis or strikethrough opener; code spans and escapes are skipped. */
private fun articleFindCloser(text: String, from: Int, ch: Char, runLength: Int): Int {
    var k = from
    while (k < text.length) {
        val c = text[k]
        if (c == '\\') {
            k += 2
        } else if (c == '`') {
            val length = articleRunLength(text, k, '`')
            val close = articleFindRun(text, k + length, '`', length)
            k = if (close >= 0) close + length else k + length
        } else if (c == ch) {
            val length = articleRunLength(text, k, ch)
            val before = text.getOrNull(k - 1)
            val after = text.getOrNull(k + length)
            val canClose = before != null && !before.isWhitespace() && (ch != '_' || after == null || !articleIsAsciiAlnum(after))
            if (length == runLength && canClose) return k
            k += length
        } else {
            k++
        }
    }
    return -1
}

/** Index of the bracket that closes the one at [open], honouring nesting and backslash escapes, or -1. */
private fun articleClosing(text: String, open: Int, openChar: Char, closeChar: Char): Int {
    var depth = 0
    var k = open
    while (k < text.length) {
        val c = text[k]
        if (c == '\\') {
            k += 2
            continue
        }
        if (c == openChar) {
            depth++
        } else if (c == closeChar) {
            depth--
            if (depth == 0) return k
        }
        k++
    }
    return -1
}

private fun articleLinkTarget(raw: String): String {
    val destination = raw.trim()
    if (destination.startsWith("<")) {
        val end = destination.indexOf('>')
        return if (end > 0) destination.substring(1, end) else ""
    }
    return destination.takeWhile { !it.isWhitespace() }
}

private fun articleCodeSpanText(raw: String): String =
    if (raw.length >= 2 && raw.first() == ' ' && raw.last() == ' ' && raw.isNotBlank()) raw.substring(1, raw.length - 1) else raw

private fun articleStartsUrl(text: String, index: Int): Boolean =
    text.startsWith("http://", index) || text.startsWith("https://", index) || text.startsWith("www.", index)

/** Drops sentence punctuation and unbalanced closing parentheses that trail a bare URL. */
private fun articleTrimUrl(url: String): String {
    var end = url.length
    while (end > 0) {
        val ch = url[end - 1]
        val unbalancedParen = ch == ')' && url.substring(0, end).count { it == ')' } > url.substring(0, end).count { it == '(' }
        if (ch in ".,:;!?*_~'\"" || unbalancedParen) end-- else break
    }
    return url.substring(0, end)
}

private fun articleIsAutolink(target: String): Boolean {
    if (target.isEmpty() || target.any { it.isWhitespace() || it == '<' || it == '>' }) return false
    val colon = target.indexOf(':')
    if (colon in 2..32 && articleIsAsciiLetter(target[0]) &&
        target.substring(0, colon).all { articleIsAsciiAlnum(it) || it == '+' || it == '.' || it == '-' }
    ) {
        return true
    }
    val at = target.indexOf('@')
    return at > 0 && at < target.length - 1
}

private val articleNamedEntities: Map<String, String> = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0",
    "copy" to "\u00A9", "reg" to "\u00AE", "ndash" to "\u2013", "mdash" to "\u2014", "hellip" to "\u2026",
)

private fun articleDecodeEntity(name: String): String? {
    articleNamedEntities[name]?.let { return it }
    val code = when {
        name.startsWith("#x") || name.startsWith("#X") -> name.substring(2).toIntOrNull(16)
        name.startsWith("#") -> name.substring(1).toIntOrNull()
        else -> null
    } ?: return null
    if (code <= 0 || code > 0x10FFFF || code in 0xD800..0xDFFF) return "\uFFFD"
    if (code <= 0xFFFF) return code.toChar().toString()
    val offset = code - 0x10000
    return (0xD800 + (offset shr 10)).toChar().toString() + (0xDC00 + (offset and 0x3FF)).toChar().toString()
}

private fun articleIsAsciiLetter(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z'

private fun articleIsAsciiAlnum(c: Char): Boolean = articleIsAsciiLetter(c) || c in '0'..'9'

private fun articleIsAsciiPunctuation(c: Char): Boolean =
    c.code in 33..47 || c.code in 58..64 || c.code in 91..96 || c.code in 123..126

@Composable
private fun ArticleBlocks(blocks: List<ArticleBlock>, env: ArticleEnv, spacing: Dp, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
        blocks.forEach { ArticleBlockView(it, env) }
    }
}

@Composable
private fun ArticleBlockView(block: ArticleBlock, env: ArticleEnv) {
    when (block) {
        is ArticleBlock.Heading -> ArticleText(block.text, articleHeadingStyle(block.level), env)
        is ArticleBlock.Paragraph -> ArticleText(block.text, env.bodyStyle, env)
        is ArticleBlock.Quote -> ArticleQuote(block.blocks, env)
        is ArticleBlock.ListBlock -> ArticleList(block, env)
        is ArticleBlock.Code -> Text(
            block.text,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.small)
                .padding(12.dp),
            color = env.textColor,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )
        is ArticleBlock.Table -> ArticleTable(block, env)
        is ArticleBlock.Image -> env.image(
            block.uri,
            Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(MaterialTheme.shapes.medium),
        )
        ArticleBlock.Rule -> HorizontalDivider()
    }
}

@Composable
private fun articleHeadingStyle(level: Int): TextStyle {
    val typography = MaterialTheme.typography
    return when (level) {
        1 -> typography.headlineLarge
        2 -> typography.headlineMedium
        3 -> typography.titleLarge
        else -> typography.titleMedium
    }
}

@Composable
private fun ArticleText(
    text: String,
    style: TextStyle,
    env: ArticleEnv,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Unspecified,
) {
    val annotated = remember(text, env.linkColor, env.openLink) { articleAnnotated(text, env) }
    Text(annotated, modifier = modifier, color = env.textColor, style = style, textAlign = textAlign)
}

@Composable
private fun ArticleQuote(blocks: List<ArticleBlock>, env: ArticleEnv) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraSmall))
        Spacer(Modifier.width(12.dp))
        ArticleBlocks(blocks, env, 8.dp, Modifier.weight(1f))
    }
}

@Composable
private fun ArticleList(block: ArticleBlock.ListBlock, env: ArticleEnv) {
    val lastNumber = block.start + block.items.size - 1
    val markerWidth = if (block.ordered) (14 + 9 * lastNumber.toString().length).dp else 20.dp
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        block.items.forEachIndexed { index, item ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                ArticleItemMarker(block.ordered, block.start + index, item.checked, markerWidth, env)
                ArticleBlocks(item.blocks, env, 8.dp, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ArticleItemMarker(ordered: Boolean, number: Int, checked: Boolean?, width: Dp, env: ArticleEnv) {
    Box(Modifier.width(width)) {
        if (checked != null) {
            val accent = MaterialTheme.colorScheme.primary
            val checkShape = RoundedCornerShape(3.dp)
            Box(
                Modifier
                    .padding(top = 4.dp)
                    .size(16.dp)
                    .border(1.5f.dp, accent, checkShape)
                    .background(if (checked) accent else Color.Transparent, checkShape),
                contentAlignment = Alignment.Center,
            ) {
                if (checked) Text("✓", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall)
            }
        } else {
            Text(if (ordered) "$number." else "•", color = env.textColor, style = env.bodyStyle)
        }
    }
}

@Composable
private fun ArticleTable(table: ArticleBlock.Table, env: ArticleEnv) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val cellStyle = MaterialTheme.typography.bodyMedium
    val headerStyle = cellStyle.copy(fontWeight = FontWeight.Bold)
    Column(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .border(1.dp, gridColor, MaterialTheme.shapes.small),
    ) {
        (listOf(table.header) + table.rows).forEachIndexed { rowIndex, cells ->
            val isHeader = rowIndex == 0
            Row(
                Modifier
                    .height(IntrinsicSize.Min)
                    .background(if (isHeader) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent),
            ) {
                cells.forEachIndexed { column, cell ->
                    val align = when (table.alignments.getOrElse(column) { ArticleAlign.Start }) {
                        ArticleAlign.Start -> TextAlign.Start
                        ArticleAlign.Center -> TextAlign.Center
                        ArticleAlign.End -> TextAlign.End
                    }
                    Box(
                        Modifier
                            .widthIn(min = 96.dp, max = 240.dp)
                            .fillMaxHeight()
                            .border(0.5f.dp, gridColor)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        ArticleText(cell, if (isHeader) headerStyle else cellStyle, env, Modifier.fillMaxWidth(), align)
                    }
                }
            }
        }
    }
}
