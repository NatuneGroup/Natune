@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.natune.shared.ui.ios

import kotlinx.coroutines.CancellationException
import app.natune.shared.domain.queue.QueueRepeatMode
import app.natune.shared.domain.repository.ContinuationQueueController
import app.natune.shared.domain.settings.PreferenceSnapshot
import app.natune.shared.domain.settings.RepeatModeKey
import platform.Foundation.*

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
