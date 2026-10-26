package moe.rukamori.archivetune.shared.domain.queue

/** Where a finite (non-radio) playlist stands in its paging. */
enum class FinitePlaylistPhase { INITIALIZING, UNKNOWN, PAGEABLE, EXHAUSTED, FAILED }

/**
 * Thrown when a finite playlist cannot be proven complete. Whole-list callers must commit nothing
 * after it, and radio must never be seeded from the unconfirmed tail.
 */
class IncompleteFinitePlaylistException(message: String = "Finite playlist loading is incomplete") :
    Exception(message)

/**
 * One song as the playlist source reported it, before it receives an occurrence key.
 *
 * @property originalIndex The playlist position from the song's own endpoint, or null when the
 *   endpoint belongs to another playlist or is missing. It locates a duplicate; it is never a key.
 */
data class FinitePlaylistSong<T>(
    val item: T,
    val videoId: String,
    val setVideoId: String?,
    val originalIndex: Int?,
)

/**
 * One occurrence in a finite playlist. [key] names the occurrence, not the song, so two copies of
 * the same video stay two entries whenever their set video ids differ.
 */
data class FinitePlaylistEntry<T>(
    val item: T,
    val videoId: String,
    val setVideoId: String?,
    val key: String,
    val originalIndex: Int?,
)

/**
 * One page as the browse transport returned it. [continuation] already folds in a songs
 * continuation, because only its presence matters to completion.
 */
data class FinitePlaylistPage<T>(
    val title: String?,
    val songs: List<FinitePlaylistSong<T>>,
    val continuation: String?,
    val songCountText: String?,
)

/** The paging state a finite queue keeps between page loads. */
data class FinitePlaylistSnapshot<T>(
    val title: String? = null,
    val entries: List<FinitePlaylistEntry<T>> = emptyList(),
    val continuation: String? = null,
    val phase: FinitePlaylistPhase = FinitePlaylistPhase.INITIALIZING,
    val consumedTokens: Set<String> = emptySet(),
)

object FinitePlaylistIds {
    /**
     * Strips the browse prefix. The Android code applies the strip twice, and this copy does the
     * same so the two normalize every id identically.
     */
    fun normalize(playlistId: String): String = playlistId.removePrefix("VL").removePrefix("VL")

    /** Radio (RD) mixes are endless, so they never count as a finite playlist. */
    fun isFinite(playlistId: String?): Boolean =
        !playlistId.isNullOrBlank() && !normalize(playlistId).startsWith("RD")
}

object FinitePlaylistOccurrences {
    /**
     * A set video id proves one playlist entry, so a repeat of it collapses. An ordinal key never
     * collapses, because a repeated video with no set id may still be two real entries.
     */
    fun key(playlistId: String, setVideoId: String?, ordinal: Int): String =
        setVideoId?.takeIf(String::isNotBlank)?.let { "$playlistId:set:$it" }
            ?: "$playlistId:ordinal:$ordinal"

    /** Appends [songs] to [existing], dropping only songs whose set video id is already present. */
    fun <T> append(
        playlistId: String,
        existing: List<FinitePlaylistEntry<T>>,
        songs: List<FinitePlaylistSong<T>>,
    ): List<FinitePlaylistEntry<T>> {
        val keys = existing.mapTo(mutableSetOf()) { it.key }
        val result = existing.toMutableList()
        songs.forEach { song ->
            val setId = song.setVideoId?.takeIf(String::isNotBlank)
            val key = key(playlistId, setId, result.size)
            if (setId == null || keys.add(key)) {
                result += FinitePlaylistEntry(song.item, song.videoId, setId, key, song.originalIndex)
            }
        }
        return result
    }
}

/** The result of applying one page after the snapshot's continuation. */
data class FinitePlaylistPaging<T>(
    val snapshot: FinitePlaylistSnapshot<T>,
    val added: List<FinitePlaylistEntry<T>>,
)

object FinitePlaylistPagingPolicy {
    /**
     * Applies the page that follows the snapshot's continuation. The token is consumed before the
     * page is judged, so a page that repeats it fails. The phase is EXHAUSTED only when the page
     * carries no continuation, and PAGEABLE otherwise.
     */
    fun <T> applyNextPage(
        playlistId: String,
        snapshot: FinitePlaylistSnapshot<T>,
        page: FinitePlaylistPage<T>,
    ): FinitePlaylistPaging<T> {
        val token = snapshot.continuation ?: throw IncompleteFinitePlaylistException()
        val consumed = snapshot.consumedTokens + token
        FinitePlaylistCompletion.validateNext(page.continuation, consumed, page.songs.size)
        val entries = FinitePlaylistOccurrences.append(playlistId, snapshot.entries, page.songs)
        val next = snapshot.copy(
            entries = entries,
            continuation = page.continuation,
            phase = if (page.continuation == null) FinitePlaylistPhase.EXHAUSTED else FinitePlaylistPhase.PAGEABLE,
            consumedTokens = consumed,
        )
        return FinitePlaylistPaging(next, entries.drop(snapshot.entries.size))
    }

    /** A failed load keeps its entries but stops paging, so nothing may seed radio from it. */
    fun <T> fail(snapshot: FinitePlaylistSnapshot<T>): FinitePlaylistSnapshot<T> =
        snapshot.copy(phase = FinitePlaylistPhase.FAILED)
}
