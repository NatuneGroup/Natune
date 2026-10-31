package moe.rukamori.archivetune.desktop

import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.QueueCollectionKind
import moe.rukamori.archivetune.shared.domain.repository.QueueLoadRequest
import moe.rukamori.archivetune.shared.domain.repository.QueueStart
import moe.rukamori.archivetune.shared.domain.repository.TypedQueueOwnerPort
import moe.rukamori.archivetune.shared.domain.repository.radioQueueRequest

internal fun desktopRadioSeedSupported(seed: ContentRef): Boolean =
    seed.provider == ContentProvider.YOUTUBE.id &&
        seed.kind == ContentKind.SONG && !seed.id.startsWith("telegram://")

/** Uses the existing queue lock so an automatic advance cannot change the seamless-radio seed mid-command. */
internal class DesktopRadioOwner(private val queue: TypedQueueOwnerPort) {
    suspend fun start(seed: ContentRef, playlistSetVideoId: String? = null) {
        require(desktopRadioSeedSupported(seed)) { "Only a YouTube song can seed desktop radio" }
        queue.withQueueAccess {
            val state = queue.state.value
            val current = state.entries.firstOrNull { it.occurrenceId == state.currentOccurrenceId }
            if (current?.item?.ref == seed) {
                check(queue.startRadioSeamlessly()) { "The current song could not seed radio" }
            } else {
                // The queue owner loads the first page before committing; a failed radio leaves playback alone.
                queue.loadCollection(
                    QueueLoadRequest(
                        collection = radioQueueRequest(seed, playlistSetVideoId),
                        kind = QueueCollectionKind.RADIO,
                        start = QueueStart(seed, setVideoId = playlistSetVideoId),
                        playWhenReady = true,
                    ),
                )
            }
        }
    }
}
