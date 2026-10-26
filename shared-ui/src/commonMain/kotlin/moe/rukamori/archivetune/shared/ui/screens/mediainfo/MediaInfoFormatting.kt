/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * Portions © 4nx3b — github.com/4nx3b
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.ui.screens.mediainfo

import kotlin.math.abs
import kotlin.math.floor

// Ported from the baseline TrackInfoFormatting.kt. Java String.format(Locale.ROOT, ...) is
// replaced by common arithmetic so the same rounding (half away from zero) runs on every target.

internal fun formatTrackDuration(durationMs: Long?): String? {
    if (durationMs == null || durationMs <= 0L) return null
    val totalSeconds = durationMs / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "$hours:${twoDigits(minutes)}:${twoDigits(seconds)}"
    } else {
        "$minutes:${twoDigits(seconds)}"
    }
}

/** "M min SSs" from the track duration, or from the playback position when the duration is unknown. */
internal fun formatDuration(durationMs: Long, playbackMs: Long): String {
    val total = if (durationMs > 0L) durationMs else playbackMs
    if (total <= 0L) return "0 min 00s"
    val minutes = total / 60_000L
    val seconds = (total % 60_000L) / 1_000L
    return "$minutes min ${twoDigits(seconds)}s"
}

internal fun formatReportedBitrate(bitrateBitsPerSecond: Int?): String? {
    if (bitrateBitsPerSecond == null || bitrateBitsPerSecond <= 0) return null
    if (bitrateBitsPerSecond < 1_000) return "$bitrateBitsPerSecond b/s"
    val tenths = (bitrateBitsPerSecond.toLong() + 50L) / 100L
    return "${formatTenths(tenths)} kb/s"
}

internal fun formatReportedSampleRate(sampleRateHz: Int?): String? {
    if (sampleRateHz == null || sampleRateHz <= 0) return null
    val tenths = (sampleRateHz.toLong() + 50L) / 100L
    return "${formatTenths(tenths)} kHz"
}

internal fun formatReportedByteCount(byteCount: Long?): String? {
    if (byteCount == null || byteCount <= 0L) return null
    val units = listOf("B", "KiB", "MiB", "GiB")
    var unitIndex = 0
    var scaled = byteCount.toDouble()
    while (scaled >= 1_024.0 && unitIndex < units.lastIndex) {
        scaled /= 1_024.0
        unitIndex += 1
    }
    val amount = if (unitIndex == 0) byteCount.toString() else formatFixedDecimal(scaled, 1)
    return "$amount ${units[unitIndex]}"
}

internal fun formatReportedLoudness(loudnessDb: Double?): String? {
    if (loudnessDb == null || !loudnessDb.isFinite()) return null
    return "${formatFixedDecimal(loudnessDb, 1)} dB"
}

internal fun isYouTubeVideoId(value: String): Boolean = YOUTUBE_VIDEO_ID.matches(value)

internal fun reportedAudioSampleRateHz(reported: ReportedAudioFormatUi?, storedSampleRateHz: Int?): Int? =
    if (reported != null) reported.sampleRateHz else storedSampleRateHz

/** Fixed-point rendering with half-up rounding of the magnitude, matching `%.Nf` for the values shown here. */
internal fun formatFixedDecimal(value: Double, decimals: Int): String {
    var scale = 1L
    repeat(decimals) { scale *= 10L }
    val negative = value < 0.0 || (value == 0.0 && 1.0 / value < 0.0)
    val scaled = floor(abs(value) * scale.toDouble() + 0.5).toLong()
    val whole = scaled / scale
    val fraction = scaled % scale
    val fractionText = if (decimals == 0) "" else "." + fraction.toString().padStart(decimals, '0')
    return (if (negative) "-" else "") + whole.toString() + fractionText
}

/** Thousands grouping with "." separators, as the media-info numbers tab has always displayed counts. */
internal fun formatGroupedCount(value: Long): String {
    val digits = abs(value).toString()
    val grouped = digits.reversed().chunked(3).joinToString(".").reversed()
    return if (value < 0L) "-$grouped" else grouped
}

/** Zero-padded two-digit text for clock-style values (seconds, minutes). */
internal fun twoDigits(value: Long): String = if (value < 10L) "0$value" else value.toString()

/** Hertz as MHz, kHz (one decimal unless whole) or Hz, matching the baseline Hardware clock row. */
internal fun formatHz(hz: Int): String =
    when {
        hz >= 1_000_000 -> "${formatFixedDecimal(hz / 1_000_000.0, 1)} MHz"
        hz >= 1_000 -> {
            val value = hz / 1_000.0
            if (value == floor(value)) {
                "${value.toInt()} kHz"
            } else {
                "${formatFixedDecimal(value, 1)} kHz"
            }
        }
        else -> "$hz Hz"
    }

/** Upper-case hex USB identifier, for example 0x1234:0x00AB. */
internal fun formatUsbHardwareId(vendorId: Int, productId: Int): String =
    "0x${hex4(vendorId)}:0x${hex4(productId)}"

private fun hex4(value: Int): String = value.toString(16).uppercase().padStart(4, '0')

/**
 * Route rate the Android output reports, used to label a conversion. Device rates win (highest
 * wins); otherwise the mix rate; null when nothing was observed.
 */
internal fun outputRouteRateHz(stats: MediaInfoOutputStats): Int? =
    when {
        stats.deviceSampleRates.isNotEmpty() -> stats.deviceSampleRates.maxOrNull() ?: stats.mixSampleRateHz
        stats.mixSampleRateHz > 0 -> stats.mixSampleRateHz
        else -> null
    }

private fun formatTenths(tenths: Long): String =
    if (tenths % 10L == 0L) {
        (tenths / 10L).toString()
    } else {
        "${tenths / 10L}.${tenths % 10L}"
    }

private val YOUTUBE_VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
