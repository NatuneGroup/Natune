package moe.rukamori.archivetune.shared.ui.menu.playerqueue

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt

// Limits and the slider curve mirror the baseline TempoPitchDialog so that the dialog and the menu's supporting text
// describe the same ranges.
internal const val PLAYER_QUEUE_TEMPO_MIN = 0.25f
internal const val PLAYER_QUEUE_TEMPO_MAX = 2f
internal const val PLAYER_QUEUE_PITCH_MIN = 0.25f
internal const val PLAYER_QUEUE_PITCH_MAX = 2f
internal const val PLAYER_QUEUE_SEMITONE_LIMIT = 12

private const val SLIDER_CURVE = 2.2f

/** Falls back to [fallback] for non-finite input before clamping, matching the baseline `safeCoerceIn`. */
internal fun Float.safeCoerceIn(min: Float, max: Float, fallback: Float): Float =
    (if (isFinite()) this else fallback).coerceIn(min, max)

/** Snaps to [step] with round-half-even, never below zero. A non-positive step leaves the value unchanged. */
internal fun Float.quantizeTo(step: Float): Float {
    if (step <= 0f) return this
    return (round(this / step) * step).coerceAtLeast(0f)
}

/** Nearest semitone offset of a pitch ratio, limited to the slider's one-octave range. */
internal fun pitchToSemitones(pitch: Float): Int {
    val safe =
        pitch
            .safeCoerceIn(PLAYER_QUEUE_PITCH_MIN, PLAYER_QUEUE_PITCH_MAX, fallback = 1f)
            .coerceAtLeast(0.0001f)
    return (12f * log2(safe)).roundToInt().coerceIn(-PLAYER_QUEUE_SEMITONE_LIMIT, PLAYER_QUEUE_SEMITONE_LIMIT)
}

internal fun semitonesToPitch(semitones: Int): Float =
    2f.pow(semitones.toFloat() / 12f).coerceIn(PLAYER_QUEUE_PITCH_MIN, PLAYER_QUEUE_PITCH_MAX)

/**
 * True when the pitch sits on an equal-temperament semitone, so the semitone control can represent it exactly. The
 * log input is clamped as the baseline did, but the comparison uses the caller's value, so an out-of-range pitch is
 * never reported as aligned.
 */
internal fun isPitchSemitoneAligned(pitch: Float): Boolean {
    val safe =
        pitch
            .safeCoerceIn(PLAYER_QUEUE_PITCH_MIN, PLAYER_QUEUE_PITCH_MAX, fallback = 1f)
            .coerceAtLeast(0.0001f)
    val semitones = (12f * log2(safe)).roundToInt()
    val reconstructed = 2f.pow(semitones.toFloat() / 12f)
    return abs(reconstructed - pitch) < 0.0015f
}

/** Maps a slider position in 0..1 to a tempo or pitch multiplier. The curve is exponential on both sides of 1. */
internal fun sliderToMultiplier(slider: Float): Float {
    val position = slider.coerceIn(0f, 1f)
    val y = (position - 0.5f) * 2f
    val magnitude = abs(y).pow(SLIDER_CURVE)
    val shaped =
        when {
            y > 0f -> magnitude
            y < 0f -> -magnitude
            else -> 0f
        }
    val exponent = if (y < 0f) 2f * shaped else shaped
    return 2f.pow(exponent).coerceIn(PLAYER_QUEUE_TEMPO_MIN, PLAYER_QUEUE_TEMPO_MAX)
}

/** Inverse of [sliderToMultiplier]: places a multiplier on the 0..1 slider track. */
internal fun multiplierToSlider(multiplier: Float): Float {
    val clamped = multiplier.coerceIn(PLAYER_QUEUE_TEMPO_MIN, PLAYER_QUEUE_TEMPO_MAX)
    val log = log2(clamped)
    val shaped = if (clamped < 1f) log / 2f else log
    val magnitude = abs(shaped).pow(1f / SLIDER_CURVE)
    val y =
        when {
            shaped > 0f -> magnitude
            shaped < 0f -> -magnitude
            else -> 0f
        }
    return (0.5f + y / 2f).coerceIn(0f, 1f)
}

/**
 * Two-decimal text with half-up rounding. It replaces the JVM `String.format("%.2f")` that the baseline used, which is
 * not available in common code.
 */
internal fun formatMultiplier(value: Float): String {
    val finite = if (value.isFinite()) value.toDouble() else 0.0
    val scaled = floor(abs(finite) * 100.0 + 0.5).toLong()
    val sign = if (finite < 0.0 && scaled != 0L) "-" else ""
    val whole = scaled / 100L
    val fraction = (scaled % 100L).toString().padStart(2, '0')
    return "$sign$whole.$fraction"
}

/** A non-negative duration in whole units. The caller picks the localized text, so this file stays free of resources. */
internal data class PlayerQueueDurationParts(
    val days: Int,
    val hours: Int,
    val minutes: Int,
    val seconds: Int,
)

/**
 * Splits [durationMs] like the baseline `makeTimeString`. The baseline's epoch-date branch is omitted because the sleep
 * countdown and song durations never reach it; a value above that heuristic would be a host bug, not a time.
 */
internal fun splitPlayerQueueDuration(durationMs: Long): PlayerQueueDurationParts {
    var seconds = durationMs / 1000L
    val days = seconds / 86_400L
    seconds %= 86_400L
    val hours = seconds / 3_600L
    seconds %= 3_600L
    val minutes = seconds / 60L
    seconds %= 60L
    return PlayerQueueDurationParts(
        // A day count beyond the Int range is not a countdown anyone can show, so clamping keeps the conversion total.
        days = days.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        hours = hours.toInt(),
        minutes = minutes.toInt(),
        seconds = seconds.toInt(),
    )
}

/**
 * Total-minutes `m:ss` text for a source-search row, matching the baseline's `"%d:%02d"`. Unlike the sleep countdown,
 * which is built from [splitPlayerQueueDuration], it never switches to hours, because the baseline search rows never did.
 */
internal fun formatPlayerQueueMinutesSeconds(durationMs: Long): String {
    val totalSeconds = durationMs / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
