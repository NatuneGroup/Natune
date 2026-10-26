/*
 * Adapted from BitChord (GPL-3.0) https://github.com/kushagrasinghx/BitChord
 * (shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/
 *  LyricsHttp.kt, ProviderLyrics.kt, EnhancedLrc.kt, LyricsQuery.kt) —
 * original re-implementation for ArchiveTune.
 */
package moe.rukamori.archivetune.shared.domain.lyrics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

internal fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

internal fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

/**
 * Reads provider response bodies: JSON envelopes, Apple-style timed JSON, escaped TTML and plain
 * text. Every reader is pure and never throws on hostile input; unrecognised shapes return null.
 */
object LyricsPayload {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    fun parseJson(raw: String): JsonElement? = runCatching { json.parseToJsonElement(raw) }.getOrNull()

    fun unwrap(raw: String): String? {
        var value = raw.replace("\uFEFF", "").trim()
        if (value.startsWith("```")) {
            value =
                value
                    .lineSequence()
                    .drop(1)
                    .toList()
                    .let { if (it.lastOrNull()?.trim() == "```") it.dropLast(1) else it }
                    .joinToString("\n")
                    .trim()
        }
        if (value.isBlank()) return null
        val element = parseJson(value) ?: return value
        // The lenient parser also accepts an unquoted plain lyric as a primitive.
        // Only real JSON scalars should be discarded, not single-token Unicode/entity text.
        if (element is JsonPrimitive && element !is JsonNull && !element.isString &&
            element.content !in setOf("true", "false") && element.content.toDoubleOrNull() == null
        ) {
            return value
        }
        return extract(element)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun toLyrics(raw: String): String? {
        val body = raw.replace("\uFEFF", "").trim()
        if (body.isEmpty()) return null

        if (isTtmlDocument(body)) return body
        timedAppleToTtml(body)?.let { return it }

        val unescaped = unescapeTtml(body)
        if (isTtmlDocument(unescaped)) return unescaped

        val inner = unwrap(body) ?: return null
        if (isTtmlDocument(inner)) return inner
        val unescapedInner = unescapeTtml(inner)
        if (isTtmlDocument(unescapedInner)) return unescapedInner

        if (inner.trimStart().startsWith("<")) return null

        val text = decodeEntities(inner)
        if (NOT_FOUND_HINT.containsMatchIn(text)) return null
        return text.trim().takeIf { it.isNotEmpty() }
    }

    fun isTtmlDocument(value: String): Boolean {
        val trimmed = value.trimStart()
        if (trimmed.startsWith("<tt", ignoreCase = true)) return true
        // A JSON envelope can quote the TTML namespace too; only XML-leading text is a document.
        return trimmed.startsWith("<") &&
            trimmed.contains("http://www.w3.org/ns/ttml", ignoreCase = true)
    }

    fun decodeEntities(text: String): String {
        if ('&' !in text) return text
        return text
            .replace(HEX_ENTITY) { match ->
                decodeScalarEntity(match.groupValues[1], 16) ?: match.value
            }.replace(DEC_ENTITY) { match ->
                decodeScalarEntity(match.groupValues[1], 10) ?: match.value
            }.replace("&apos;", "'")
            .replace("&quot;", "\"")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
    }

    private fun extract(element: JsonElement): String? =
        when (element) {
            JsonNull -> null
            is JsonPrimitive ->
                if (element.isString) {
                    val text = element.content.trim()
                    val nested = parseJson(text)
                    if (nested != null && nested !is JsonPrimitive) extract(nested) else text
                } else {
                    null
                }
            is JsonArray -> element.mapNotNull(::extract).joinToString("\n").takeIf { it.isNotBlank() }
            is JsonObject -> {
                if (isError(element)) return null
                CONTENT_KEYS.asSequence().mapNotNull { element[it]?.let(::extract) }.firstOrNull()
                    ?: (element["metadata"] as? JsonObject)?.let(::extract)
                    ?: element["words"]?.let(::extract)
            }
        }

    private fun isError(element: JsonObject): Boolean {
        if (element["isError"]?.toString() == "true") return true
        if (element["ok"]?.toString() == "false") return true
        val error = element["error"]
        if (error != null && error !is JsonNull && error.toString() !in BENIGN_ERRORS) return true
        return false
    }

    private fun timedAppleToTtml(raw: String): String? {
        val root = parseJson(raw) ?: return null
        val content = root.findTimedContent() ?: return null
        val rows = content.mapNotNull { it as? JsonObject }
        if (rows.isEmpty()) return null

        val builder = StringBuilder(TTML_HEAD)
        var wroteLine = false
        rows.forEachIndexed { index, row ->
            val start = row.long("timestamp") ?: return@forEachIndexed
            val wordRows = row.array("text") ?: return@forEachIndexed
            if (wordRows.isEmpty()) return@forEachIndexed
            val nextLine = rows.getOrNull(index + 1)?.long("timestamp")

            val timed = wordRows.mapNotNull { it.asObject() }.filter { it.long("timestamp") != null }
            val lineEnd = nextLine ?: (start + DEFAULT_WORD_MS)

            if (timed.size != wordRows.size) {
                val text = wordRows.mapNotNull { it.asObject()?.string("text") }.joinToString(" ") { it.trim() }
                if (text.isBlank()) return@forEachIndexed
                builder.appendParagraphStart(start, maxOf(lineEnd, start))
                builder.append(escapeXml(text))
                builder.append("</p>")
                wroteLine = true
                return@forEachIndexed
            }

            var lastEnd = start
            val spans = StringBuilder()
            timed.forEachIndexed { wordIndex, word ->
                val text = word.string("text") ?: return@forEachIndexed
                val wordStart = word.long("timestamp") ?: return@forEachIndexed
                val wordEnd =
                    word.long("endtime")
                        ?: timed.getOrNull(wordIndex + 1)?.long("timestamp")
                        ?: nextLine
                        ?: (wordStart + DEFAULT_WORD_MS)
                val joinsNext = word.boolean("part") == true
                spans.appendSpan(wordStart, maxOf(wordEnd, wordStart), if (joinsNext) text else "$text ")
                lastEnd = maxOf(lastEnd, wordEnd)
            }

            builder.appendParagraphStart(start, nextLine ?: lastEnd)
            builder.append(spans)
            builder.append("</p>")
            wroteLine = true
        }
        if (!wroteLine) return null
        builder.append(TTML_TAIL)
        return builder.toString()
    }

    private fun unescapeTtml(value: String): String =
        if (value.contains("&lt;tt", ignoreCase = true)) {
            value
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&amp;", "&")
        } else {
            value
        }

    private fun decodeScalarEntity(digits: String, radix: Int): String? {
        val codePoint = digits.toIntOrNull(radix) ?: return null
        if (codePoint !in 0..0x10FFFF || codePoint in 0xD800..0xDFFF) return null
        return codePointToString(codePoint)
    }

    private fun escapeXml(text: String): String =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private fun JsonElement.findTimedContent(): JsonArray? =
        when (this) {
            is JsonObject ->
                (this["content"] as? JsonArray)?.takeIf { array ->
                    array.any { (it as? JsonObject)?.get("timestamp") != null }
                } ?: values.firstNotNullOfOrNull { it.findTimedContent() }
            is JsonArray -> firstNotNullOfOrNull { it.findTimedContent() }
            else -> null
        }

    private fun StringBuilder.appendParagraphStart(startMs: Long, endMs: Long) {
        append("<p begin=\"")
        append(formatSecondsFromMs(startMs))
        append("\" end=\"")
        append(formatSecondsFromMs(endMs))
        append("\">")
    }

    private fun StringBuilder.appendSpan(startMs: Long, endMs: Long, text: String) {
        append("<span begin=\"")
        append(formatSecondsFromMs(startMs))
        append("\" end=\"")
        append(formatSecondsFromMs(endMs))
        append("\">")
        append(escapeXml(text))
        append("</span>")
    }

    private const val DEFAULT_WORD_MS = 800L

    private const val TTML_HEAD = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div>"""
    private const val TTML_TAIL = """</div></body></tt>"""

    private val CONTENT_KEYS =
        listOf(
            "ttml", "ttmlContent", "lyrics", "lrc", "content", "text",
            "plainLyrics", "syncedLyrics", "line", "lines", "lyric",
            "data", "result", "response",
        )

    private val BENIGN_ERRORS = setOf("false", "\"\"")

    private val NOT_FOUND_HINT =
        Regex("""(?i)\b(?:lyrics?\s+(?:not\s+found|unavailable|not\s+available))\b""")

    private val HEX_ENTITY = Regex("""&#x([0-9a-fA-F]+);""")
    private val DEC_ENTITY = Regex("""&#([0-9]+);""")
}
