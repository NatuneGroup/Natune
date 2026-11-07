package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*

/**
 * The queue rows. The Android playback page keeps them inline; the desktop settings tree gives them a page of their
 * own under Playback, so this is the one list both render and an anchor cannot drift between the two.
 */
fun queueSettingsRows(): List<SettingsRow> = listOf(
    SettingsRow.Toggle("persistent_queue", Res.string.m6_persistent_queue, PersistentQueueKey, true),
    SettingsRow.Toggle("infinite_queue", Res.string.queue_infinite, AutoLoadMoreKey, true,
        description = Res.string.queue_infinite_description),
    SettingsRow.Toggle("permanent_shuffle", Res.string.m6_permanent_shuffle, PermanentShuffleKey, false),
    SettingsRow.Toggle("queue_after_manual", Res.string.m6_queue_manual, QueueAddAfterManualKey, true),
)

/**
 * The desktop queue page. It adds the duplicate guard, which this host's queue owner already reads and the Android list
 * does not offer.
 */
fun desktopQueueSettingsRows(): List<SettingsRow> = queueSettingsRows() +
    SettingsRow.Toggle("prevent_duplicates", Res.string.m6_queue_prevent_duplicates, PreventDuplicateTracksInQueueKey, false)
