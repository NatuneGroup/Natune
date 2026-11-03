package moe.rukamori.archivetune.shared.data.repository

import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence

/**
 * The hidden-content rules of `Queue.Status.filterPlaybackContent`: explicit, video and blocked
 * artists. A load passes the owner's current flags, so the filter follows the live settings rather
 * than a snapshot taken when the queue was built.
 */
internal fun List<ContentOccurrence>.filterPlaybackContent(
    hideExplicit: Boolean,
    hideVideo: Boolean,
    blockedArtistIds: Set<String>,
): List<ContentOccurrence> {
    if (!hideExplicit && !hideVideo && blockedArtistIds.isEmpty()) return this
    return filter { occurrence ->
        (!hideExplicit || !occurrence.item.explicit) &&
            (!hideVideo || !occurrence.item.isVideo) &&
            occurrence.item.canonicalSong().artists.none { it.id != null && it.id in blockedArtistIds }
    }
}
