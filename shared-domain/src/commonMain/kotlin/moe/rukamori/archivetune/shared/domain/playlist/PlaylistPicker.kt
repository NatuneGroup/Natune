package moe.rukamori.archivetune.shared.domain.playlist

import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentRef

/**
 * The order of the playlist picker. [NAME] sorts by title under the device collation, [CREATE_DATE] by the stored
 * creation sequence, [LAST_UPDATED] by the newest song-addition sequence and [SONG_COUNT] by the stored count.
 */
enum class PlaylistPickerSortType {
    NAME,
    CREATE_DATE,
    LAST_UPDATED,
    SONG_COUNT,
}

/**
 * One editable playlist as the add-to-playlist picker rows it. [memberRefs] are the item refs of the batch already
 * stored here, so the row can carry the membership hint. [memberOccurrenceIds] are the stored occurrence ids of the
 * batch, which the store refuses a second time, so they are filtered out of the write instead of failing it.
 */
data class PlaylistPickerCandidate(
    val item: ContentItem,
    val songCount: Int,
    val createdSeq: Long,
    val updatedSeq: Long,
    val memberRefs: Set<ContentRef> = emptySet(),
    val memberOccurrenceIds: Set<String> = emptySet(),
) {
    /** The playlist already holds every item of the batch. */
    fun holdsAll(refs: List<ContentRef>): Boolean = refs.isNotEmpty() && memberRefs.containsAll(refs)
}

/** The ordering the add-to-playlist picker applies to its candidates. */
object PlaylistPickerOrder {
    /**
     * The candidates in [sort]'s order. [collation] orders names as the device collator does and defaults to a
     * case-insensitive order; every other key comes from the stored values. Descending reverses the ascending
     * order, as the baseline picker does.
     */
    fun sorted(
        candidates: List<PlaylistPickerCandidate>,
        sort: PlaylistPickerSortType,
        descending: Boolean,
        collation: Comparator<String> =
            Comparator { left, right -> left.compareTo(right, ignoreCase = true) },
    ): List<PlaylistPickerCandidate> {
        val ascending =
            when (sort) {
                PlaylistPickerSortType.NAME ->
                    candidates.sortedWith { left, right ->
                        collation.compare(left.item.title, right.item.title)
                            .takeIf { it != 0 } ?: left.item.ref.id.compareTo(right.item.ref.id)
                    }
                PlaylistPickerSortType.CREATE_DATE ->
                    candidates.sortedWith(compareBy({ it.createdSeq }, { it.item.ref.id }))
                PlaylistPickerSortType.LAST_UPDATED ->
                    candidates.sortedWith(compareBy({ it.updatedSeq }, { it.item.ref.id }))
                PlaylistPickerSortType.SONG_COUNT ->
                    candidates.sortedWith(compareBy({ it.songCount }, { it.item.ref.id }))
            }
        return if (descending) ascending.asReversed() else ascending
    }
}

/** How one playlist of a batch add finished. */
enum class PlaylistAddOutcome {
    /** Every requested song is committed. */
    COMPLETED,

    /** The playlist already held every requested song, so nothing had to be written. */
    ALREADY_PRESENT,

    /** The write was refused and the store kept nothing of it. */
    FAILED,

    /**
     * The request's result never arrived, so the store may or may not hold the songs. The user checks the playlist
     * before retrying, because a blind retry can duplicate a committed add.
     */
    UNCERTAIN,
}

/** One refused playlist add: the playlist title and the sanitized reason class name. */
data class PlaylistAddFailure(val name: String, val reason: String)

/**
 * The running report of adding one batch of songs to several playlists. The picker renders [processed] of [total]
 * tracks as its progress text and lists [failed] and [uncertain] playlist names when the batch stops early.
 */
data class PlaylistAddProgress(
    val processed: Int = 0,
    val total: Int = 0,
    /** The tracks whose writes committed. */
    val completed: Int = 0,
    /** The tracks the playlists already held. */
    val alreadyPresent: Int = 0,
    val failed: List<PlaylistAddFailure> = emptyList(),
    val uncertain: List<String> = emptyList(),
    val stopped: Boolean = false,
    /** The titles of the playlists whose writes committed, for the completion notice of the calling surface. */
    val completedNames: List<String> = emptyList(),
) {
    /** The tracks the user can trust: committed adds plus the ones already stored. */
    val settled: Int get() = completed + alreadyPresent

    /** True while nothing failed and nothing is unconfirmed. */
    val clean: Boolean get() = failed.isEmpty() && uncertain.isEmpty() && !stopped

    /** One target finished: [name] is its playlist title, [tracks] the songs it covered, [outcome] how it ended. */
    fun record(name: String, tracks: Int, outcome: PlaylistAddOutcome, reason: String = ""): PlaylistAddProgress =
        copy(
            processed = processed + tracks,
            completed = completed + if (outcome == PlaylistAddOutcome.COMPLETED) tracks else 0,
            alreadyPresent = alreadyPresent + if (outcome == PlaylistAddOutcome.ALREADY_PRESENT) tracks else 0,
            failed = failed + if (outcome == PlaylistAddOutcome.FAILED) listOf(PlaylistAddFailure(name, reason)) else emptyList(),
            uncertain = uncertain + if (outcome == PlaylistAddOutcome.UNCERTAIN) listOf(name) else emptyList(),
            completedNames = completedNames + if (outcome == PlaylistAddOutcome.COMPLETED) listOf(name) else emptyList(),
        )

    /** The batch was stopped mid-run: the current playlist's result never arrived, so it counts as unconfirmed. */
    fun stoppedAt(name: String, tracks: Int): PlaylistAddProgress =
        copy(uncertain = uncertain + name, stopped = true)

    /** The tracks still unconfirmed: everything not processed when the run stopped. */
    val unconfirmed: Int get() = (total - processed).coerceAtLeast(0)
}
