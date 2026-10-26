package moe.rukamori.archivetune.shared.domain.lyrics.document

import moe.rukamori.archivetune.shared.domain.lyrics.LyricsPayload
import moe.rukamori.archivetune.shared.domain.lyrics.QrcParser

data class LyricsDocumentSegment(val id: Int, val text: String)

/** Both ordinary translation menus and AI use this timing-preserving document boundary. */
interface LyricsDocument {
    val formatName: String
    val segments: List<LyricsDocumentSegment>
    fun rebuild(translations: Map<Int, String>): String

    companion object {
        const val MAX_CHARS = 2_000_000
        fun parse(raw: String): LyricsDocument {
            require(raw.length <= MAX_CHARS) { "Lyrics exceed document limit" }
            val trimmed = raw.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
            return when {
                trimmed.startsWith("<!DOCTYPE", true) || LyricsPayload.isTtmlDocument(trimmed) ->
                    TtmlLyricsDocument(raw)
                QrcParser.isQrc(raw) -> LineLyricsDocument.qrc(raw)
                else -> LineLyricsDocument(raw)
            }
        }
    }
}

private class LineLyricsDocument(
    private val raw: String,
    private val qrc: Boolean = false,
    private val content: String = raw,
    private val wrap: (String) -> String = { it },
) : LyricsDocument {
    private data class Row(val raw: String, val prefix: String, val whitespace: String, val suffix: String, val id: Int?)
    private val parsedSegments = mutableListOf<LyricsDocumentSegment>()
    private val rows = buildList {
        val seen = mutableMapOf<String, Boolean>()
        for (line in content.split('\n')) {
            val match = (if (qrc) QRC_ROW.matchEntire(line) else null) ?: LRC_ROW.matchEntire(line)
            val prefix = match?.groupValues?.get(1).orEmpty()
            val body = match?.groupValues?.get(2) ?: line
            val timingKey = prefix.filterNot(Char::isWhitespace)
            if (timingKey.isNotEmpty() && seen[timingKey] == true) {
                // A previous translated row is replaced, not translated a second time.
                continue
            }
            val clean = if (qrc) body.replace(QRC_WORD, "") else body.replace(ENHANCED_WORD, "")
            val text = clean.trim()
            if (timingKey.isNotEmpty()) seen[timingKey] = text.isNotEmpty()
            val id = if (text.isEmpty() || METADATA.matches(line.trim())) null else parsedSegments.size.also {
                parsedSegments.add(LyricsDocumentSegment(it, text))
            }
            add(Row(line, prefix, body.takeWhile(Char::isWhitespace), body.takeLastWhile(Char::isWhitespace), id))
        }
    }

    override val segments: List<LyricsDocumentSegment> = parsedSegments.toList()
    override val formatName: String = when {
        qrc -> "QRC"
        rows.any { it.prefix.isNotEmpty() } -> "synced LRC"
        else -> "plain text"
    }

    override fun rebuild(translations: Map<Int, String>): String {
        val changed = segments.associate { it.id to translations[it.id]?.trim()?.takeIf { candidate ->
            candidate.isNotEmpty() && !candidate.equals(it.text, ignoreCase = true)
        } }
        if (changed.values.none { it != null }) return raw
        return wrap(rows.joinToString("\n") { row ->
            val translated = changed[row.id] ?: return@joinToString row.raw
            "${row.raw}\n${row.prefix}${row.whitespace}$translated${row.suffix}"
        })
    }

    companion object {
        private val LRC_ROW = Regex("""^(\s*(?:\[\d{1,3}:\d{2}(?:[.:]\d+)?])+(?:\s*))(.+|\s*)$""")
        private val QRC_ROW = Regex("""^(\s*\[\d{1,8},\d{1,8}])(.+|\s*)$""")
        private val QRC_WORD = Regex("""\(\d{1,8},\d{1,8}(?:,\d{1,8})?\)""")
        private val ENHANCED_WORD = Regex("""<\d{1,3}:\d{2}(?:[.:]\d+)?>""")
        private val METADATA = Regex("""^\[[A-Za-z]+:.*]$""")
        private val CONTENT_ATTRIBUTE = Regex("""LyricContent\s*=\s*(["'])([\s\S]*?)\1""")

        fun qrc(raw: String): LyricsDocument {
            val attribute = CONTENT_ATTRIBUTE.find(raw) ?: return LineLyricsDocument(raw, qrc = true)
            val content = attribute.groupValues[2]
            val contentStart = attribute.range.first + attribute.value.indexOf(attribute.groupValues[1]) + 1
            return LineLyricsDocument(
                raw = raw,
                qrc = true,
                content = LyricsPayload.decodeEntities(content),
                wrap = { replacement -> raw.replaceRange(contentStart, contentStart + content.length, escapeLyricsXml(replacement)) },
            )
        }
    }
}

internal fun escapeLyricsXml(value: String): String =
    // The legacy normalizer interprets &lt; anywhere in namespaced TTML as an escaped
    // transport wrapper. Numeric XML references keep literal lyric punctuation literal.
    value.replace("&", "&amp;").replace("<", "&#60;").replace(">", "&#62;")
        .replace("\"", "&quot;").replace("'", "&apos;")
