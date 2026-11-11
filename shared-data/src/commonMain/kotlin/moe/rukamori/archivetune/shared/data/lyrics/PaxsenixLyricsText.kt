package moe.rukamori.archivetune.shared.data.lyrics

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
