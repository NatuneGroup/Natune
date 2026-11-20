package app.natune.shared.data.repository

import app.natune.shared.domain.repository.ContentKind
import app.natune.shared.domain.repository.ContentOccurrence
import app.natune.shared.domain.settings.BlockedSongRef
import app.natune.shared.domain.songmetadata.canonicalSong

/**
 * The hidden-content rules of `Queue.Status.filterPlaybackContent`: explicit, video, blocked
 * artists and blocked songs. A load passes the owner's current flags, so the filter follows the
 * live settings rather than a snapshot taken when the queue was built.
 */
internal fun List<ContentOccurrence>.filterPlaybackContent(
    hideExplicit: Boolean,
    hideVideo: Boolean,
    blockedArtistIds: Set<String>,
    blockedSongRefs: Set<BlockedSongRef>,
): List<ContentOccurrence> {
    if (!hideExplicit && !hideVideo && blockedArtistIds.isEmpty() && blockedSongRefs.isEmpty()) return this
    return filter { occurrence ->
        (!hideExplicit || !occurrence.item.explicit) &&
            (!hideVideo || !occurrence.item.isVideo) &&
            occurrence.item.canonicalSong().artists.none { it.id != null && it.id in blockedArtistIds } &&
            occurrence.item.ref.let { ref ->
                ref.kind != ContentKind.SONG || BlockedSongRef(ref.provider, ref.id) !in blockedSongRefs
            }
    }
}
