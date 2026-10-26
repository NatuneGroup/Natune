/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.lyrics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.math.abs

/**
 * Paxsenix takes durations as seconds, but some callers pass milliseconds. Anything above six
 * minutes in seconds is read as milliseconds, as the Android client did; zero or less is unknown.
 */
internal fun resolveDurationMs(durationSec: Int): Long =
    when {
        durationSec <= 0 -> 0L
        durationSec > 360_000 -> durationSec.toLong()
        else -> durationSec * 1000L
    }

/** A candidate whose length is within ten seconds of the request; an unknown request length accepts any. */
internal fun withinDurationTolerance(
    durationMs: Long,
    candidateMs: Long,
): Boolean = durationMs <= 0 || abs(candidateMs - durationMs) < 10_000L

/** The candidate closest to [durationMs], or the first one when the request length is unknown. */
internal inline fun <T> List<T>.closestTo(
    durationMs: Long,
    durationOf: (T) -> Long,
): T? = if (durationMs > 0) minByOrNull { abs(durationOf(it) - durationMs) } else firstOrNull()

/**
 * Reads a lyric body. A body that is not JSON is already the lyric text. JSON is searched for the
 * lyric field, unwrapping nested JSON strings. Blank or error-shaped results read as no lyrics.
 */
internal fun cleanJsonLyrics(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val payload = runCatching { PaxsenixJson.parseToJsonElement(trimmed) }.getOrNull() ?: return trimmed
    return extractLyrics(payload)
}

/** Keys that hold lyric text, in the order the Android client tried them. */
private val LYRIC_KEYS = listOf("lyrics", "lrc", "content", "text", "plainLyrics", "syncedLyrics", "line", "lyric")

private fun extractLyrics(element: JsonElement): String? =
    when (element) {
        is JsonNull -> null
        is JsonPrimitive -> extractPrimitive(element)
        is JsonArray -> element.mapNotNull(::extractLyrics).joinToString("\n").trim().takeIf { it.isNotEmpty() }
        is JsonObject -> extractObject(element)
    }

private fun extractPrimitive(element: JsonPrimitive): String? {
    if (!element.isString) return null
    val value = element.content.trim()
    if (value.isEmpty()) return null
    // A string holding structured JSON is a second layer of the same payload; plain text stays as it is.
    val nested = runCatching { PaxsenixJson.parseToJsonElement(value) }.getOrNull()
    return if (nested != null && nested !is JsonPrimitive) extractLyrics(nested) else value
}

private fun extractObject(element: JsonObject): String? {
    if (element.isErrorPayload()) return null
    return LYRIC_KEYS.firstNotNullOfOrNull { key -> element[key]?.let(::extractLyrics) }
        ?: (element["metadata"] as? JsonObject)?.let { metadata ->
            LYRIC_KEYS.firstNotNullOfOrNull { key -> metadata[key]?.let(::extractLyrics) }
        }
        ?: element["words"]?.let(::extractWords)
}

private fun extractWords(words: JsonElement): String? =
    when (words) {
        is JsonArray -> words.mapNotNull(::extractLyrics).joinToString(" ").trim().takeIf { it.isNotEmpty() }
        else -> extractLyrics(words)
    }

private fun JsonObject.isErrorPayload(): Boolean {
    if ((this["isError"] as? JsonPrimitive)?.booleanOrNull == true) return true
    return when (val error = this["error"]) {
        null, JsonNull -> false
        is JsonPrimitive -> error.booleanOrNull ?: error.content.trim().isNotEmpty()
        is JsonArray -> error.isNotEmpty()
        is JsonObject -> error.isNotEmpty()
    }
}

/** Renders Apple's timed lines as LRC: one `[mm:ss.cc]` stamp per line, words joined by single spaces. */
internal fun convertAppleMusicToLrc(response: AppleMusicLyricsResponse): String =
    response.content.joinToString("\n") { line ->
        val totalSeconds = line.timestamp / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val hundredths = (line.timestamp % 1000) / 10
        "[${minutes.twoDigits()}:${seconds.twoDigits()}.${hundredths.twoDigits()}]" +
            line.text.joinToString(" ") { it.text.trim() }
    }

// Matches %02d for the non-negative values Apple sends, without depending on java.util.Locale.
private fun Long.twoDigits(): String = toString().padStart(2, '0')
