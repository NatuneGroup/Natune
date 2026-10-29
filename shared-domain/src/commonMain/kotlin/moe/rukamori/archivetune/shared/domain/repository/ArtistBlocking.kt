package moe.rukamori.archivetune.shared.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * Artist blocking, keyed by the provider's artist identity. The baseline kept a block as a timestamp on the artist row,
 * and every list that hid a blocked artist's content read that row. Only an artist with a provider id can be blocked,
 * so a credit the provider did not identify can never hide content by mistake.
 */
interface ArtistBlockRepository {
    /** Emits whether [artist] is blocked now, and again after each change to that state. */
    fun observeBlocked(artist: ContentRef): Flow<Boolean>

    /** Emits every blocked artist. Lists read it to hide the songs and albums of those artists. */
    fun observeBlockedArtists(): Flow<Set<ContentRef>>

    /**
     * Blocks or unblocks [artist]. Completion means the change was committed. Blocking an artist that is already blocked
     * keeps the original block time, as the baseline did.
     */
    suspend fun setBlocked(artist: ContentRef, name: String, blocked: Boolean)
}

/** Whether any artist credit of this item is blocked. A credit without a provider id is never blocked. */
fun ContentItem.hasBlockedArtist(blocked: Set<ContentRef>): Boolean =
    (canonicalSongMetadata?.artists ?: artists).any { credit -> credit.ref(ref.provider)?.let { it in blocked } == true }
