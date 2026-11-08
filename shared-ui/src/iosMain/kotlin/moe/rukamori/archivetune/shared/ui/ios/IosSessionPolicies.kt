@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.ui.ios

import kotlinx.coroutines.CancellationException
import moe.rukamori.archivetune.shared.domain.queue.QueueRepeatMode
import moe.rukamori.archivetune.shared.domain.repository.ContinuationQueueController
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.RepeatModeKey
import platform.Foundation.NSTimeZone
import platform.Foundation.NSDate
import platform.Foundation.NSString
import platform.Foundation.create

internal fun iosRepeatMode(preferences: PreferenceSnapshot?): QueueRepeatMode =
    preferences?.get(RepeatModeKey)?.let { QueueRepeatMode.fromStoredValue(it) } ?: QueueRepeatMode.OFF

/** The local time zone offset at [epochMs], for the history day boundaries and the My Top windows. */
internal fun iosOffsetMsAt(epochMs: Long): Long =
    NSTimeZone.localTimeZone.secondsFromGMTForDate(NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0)) * 1000L

/**
 * The text order the portable library sorts with: the user's locale, case-insensitive, so one artist never splits into
 * two groups by case.
 */
internal val iosTextOrder: Comparator<String> = Comparator { first, second ->
    NSString.create(string = first).localizedCaseInsensitiveCompare(second).toInt()
}

/** One queue checkpoint. A failure is logged and never escapes into playback or shutdown. */
internal suspend fun checkpointNow(queue: ContinuationQueueController, report: (String) -> Unit) {
    try {
        queue.checkpoint()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        report("queue checkpoint failed: ${failure::class.simpleName}")
    }
}
