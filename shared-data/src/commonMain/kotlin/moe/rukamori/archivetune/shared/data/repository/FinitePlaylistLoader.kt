/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.shared.data.persistence.AlbumCompletionIndex
import moe.rukamori.archivetune.shared.domain.queue.FinitePlaylistCollector
import moe.rukamori.archivetune.shared.domain.queue.FinitePlaylistCompleteList
import moe.rukamori.archivetune.shared.domain.queue.FinitePlaylistCompletion
import moe.rukamori.archivetune.shared.domain.queue.FinitePlaylistPage
import moe.rukamori.archivetune.shared.domain.queue.FinitePlaylistSong
import moe.rukamori.archivetune.shared.domain.queue.IncompleteFinitePlaylistException
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentPage
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentSurface

/**
 * How one whole-list load ended. A host branches on this instead of catching exceptions. Cancellation is never an
 * outcome: it propagates, so a cancelled load cannot be mistaken for a finished one.
 */
sealed interface FinitePlaylistLoadOutcome {
    /** Every page was read, and the walk is provably complete. */
    data class Complete(val list: FinitePlaylistCompleteList<ContentOccurrence>) : FinitePlaylistLoadOutcome

    /**
     * The walk could not be proven complete: a timeout, a repeated or blank continuation, or an empty first page
     * without a confirmed zero count. Retry or reload from the beginning may succeed.
     */
    data class Incomplete(val reason: String) : FinitePlaylistLoadOutcome

    /** The source failed while a page was read. Nothing partial is returned, and a retry may succeed. */
    data class Failed(val cause: Throwable) : FinitePlaylistLoadOutcome
}

/**
 * The whole-list walk of a finite remote playlist. The queue and the YouTube playlist menu share this one walk, so the
 * completeness rules cannot drift apart. A short, failed or timed-out walk never returns a partial list, because
 * [FinitePlaylistCollector] refuses to complete while a continuation is still pending. The one write is the record
 * of a whole album walk, which [albumCompletion] receives only after the walk has completed.
 */
class FinitePlaylistLoader(
    private val content: ContentRepositoryRouter,
    private val albumCompletion: AlbumCompletionIndex? = null,
) {
    /** The complete list, or [IncompleteFinitePlaylistException] when the walk cannot be proven complete. */
    suspend fun loadComplete(playlist: ContentRef): FinitePlaylistCompleteList<ContentOccurrence> =
        loadComplete(
            ContentRequest(ContentProvider.fromId(playlist.provider), ContentSurface.PLAYLIST, parent = playlist),
        )

    /** The complete list of [collection], bounded by [FinitePlaylistCompletion.TIMEOUT_MS]. */
    suspend fun loadComplete(collection: ContentRequest): FinitePlaylistCompleteList<ContentOccurrence> =
        withTimeoutOrNull(FinitePlaylistCompletion.TIMEOUT_MS) { walk(collection) }
            ?: throw IncompleteFinitePlaylistException("Finite playlist loading timed out")

    /** The same walk as [loadComplete], reported as an outcome. Cancellation is rethrown, never reported. */
    suspend fun load(playlist: ContentRef): FinitePlaylistLoadOutcome =
        outcomeOf { loadComplete(playlist) }

    /** The walk as an outcome, for a caller that already holds the collection request. */
    suspend fun load(collection: ContentRequest): FinitePlaylistLoadOutcome =
        outcomeOf { loadComplete(collection) }

    /**
     * The walk itself, without a time bound. The queue keeps its own timeout around it, as it always did. Internal,
     * so the queue and the loader share exactly one set of completeness rules.
     */
    internal suspend fun walk(collection: ContentRequest): FinitePlaylistCompleteList<ContentOccurrence> {
        val collector = FinitePlaylistCollector(content.page(collection, null).toFinitePage())
        while (true) {
            val token = collector.nextRequestToken() ?: break
            collector.accept(content.page(collection, token).toFinitePage())
        }
        val complete = collector.complete()
        recordWholeAlbum(collection, complete)
        return complete
    }

    /**
     * Records a plain whole-album walk as that album's complete track list. Every track must name the album, because
     * the library keys an album by the album id its songs carry. A track that names another album, or none, would put
     * the album on the full list on a claim the walk does not support. A failed write leaves the album unrecorded and
     * does not fail the walk, which has already completed.
     */
    private suspend fun recordWholeAlbum(
        collection: ContentRequest,
        complete: FinitePlaylistCompleteList<ContentOccurrence>,
    ) {
        val index = albumCompletion ?: return
        val album = collection.parent?.takeIf { it.kind == ContentKind.ALBUM } ?: return
        if (collection.surface != ContentSurface.ALBUM || collection.text != null || collection.section != null) return
        val tracks = complete.songs.map { song -> song.item.item }
        if (tracks.isEmpty() || tracks.any { it.ref.kind != ContentKind.SONG || it.albumRef() != album }) return
        try {
            index.recordComplete(album, tracks.map { it.ref })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // The album stays unrecorded, so it is not shown as full. Nothing else depends on this write.
        }
    }

    private suspend fun outcomeOf(
        load: suspend () -> FinitePlaylistCompleteList<ContentOccurrence>,
    ): FinitePlaylistLoadOutcome =
        try {
            FinitePlaylistLoadOutcome.Complete(load())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (incomplete: IncompleteFinitePlaylistException) {
            FinitePlaylistLoadOutcome.Incomplete(incomplete.message ?: "Finite playlist loading is incomplete")
        } catch (failure: Exception) {
            FinitePlaylistLoadOutcome.Failed(failure)
        }
}

/**
 * A page as the finite collector reads it. The playlist title and the header count are not part of [ContentPage], so
 * they stay null here, and the collector then refuses an empty first page unless the source states a zero count.
 */
internal fun ContentPage.toFinitePage(): FinitePlaylistPage<ContentOccurrence> = FinitePlaylistPage(
    title = null,
    songs = items.map { FinitePlaylistSong(it, it.item.ref.id, it.setVideoId, originalIndex = null) },
    continuation = continuation,
    songCountText = null,
)
