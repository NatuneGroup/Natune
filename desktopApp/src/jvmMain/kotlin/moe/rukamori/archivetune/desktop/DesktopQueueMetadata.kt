package moe.rukamori.archivetune.desktop

import moe.rukamori.archivetune.shared.domain.queue.QueueAdvancePolicy
import moe.rukamori.archivetune.shared.domain.queue.QueueRepeatMode
import moe.rukamori.archivetune.shared.domain.repository.QueueState
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.RepeatModeKey

internal fun repeatModeOf(preferences: PreferenceSnapshot?): QueueRepeatMode =
    preferences?.get(RepeatModeKey)?.let { QueueRepeatMode.fromStoredValue(it) } ?: QueueRepeatMode.OFF

internal fun QueueState.metadataFor(occurrenceId: String?, repeat: QueueRepeatMode): DesktopMediaMetadata? {
    val id = occurrenceId ?: currentOccurrenceId ?: return null
    val occurrence = entries.firstOrNull { it.occurrenceId == id } ?: return null
    val item = occurrence.item
    return DesktopMediaMetadata(
        occurrenceId = id,
        title = item.title,
        artists = item.artists.map { it.name },
        album = item.album.orEmpty(),
        artworkUri = item.artworkUri,
        canNext = QueueAdvancePolicy.successor(repeat, playOrder, id) != null,
        canPrevious = playOrder.indexOf(id) > 0,
    )
}
