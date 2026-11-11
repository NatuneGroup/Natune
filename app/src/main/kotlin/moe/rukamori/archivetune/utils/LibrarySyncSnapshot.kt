package moe.rukamori.archivetune.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.YTItem
import moe.rukamori.archivetune.innertube.pages.LibraryPage
import moe.rukamori.archivetune.innertube.pages.AlbumPage
import kotlin.coroutines.coroutineContext

internal interface LibrarySyncSource {
    suspend fun initial(browseId: String): Result<LibraryPage>
    suspend fun album(browseId: String): Result<AlbumPage> = YouTube.album(browseId)
    suspend fun continuation(token: String): LibraryPage
}

internal object YouTubeLibrarySyncSource : LibrarySyncSource {
    override suspend fun initial(browseId: String) = YouTube.library(browseId)
    override suspend fun continuation(token: String): LibraryPage =
        YouTube.libraryContinuation(token).getOrThrow().let { LibraryPage(it.items, it.continuation) }
}

/** [complete] is true only when the remote listing was read to its end; only then may absence imply removal. */
internal data class LibrarySnapshot(
    val items: List<YTItem>,
    val complete: Boolean,
)

private const val LIBRARY_SNAPSHOT_MAX_REQUESTS = 500
private const val LIBRARY_SNAPSHOT_TIMEOUT_MILLIS = 120_000L

/**
 * Core's completed() silently drops unfinished continuations, so its result cannot justify deletions.
 * A broken continuation here still yields the items read so far, marked incomplete, so additions keep working.
 */
internal suspend fun Result<LibraryPage>.completedForSync(
    source: LibrarySyncSource = YouTubeLibrarySyncSource,
): Result<LibrarySnapshot> {
    val first = getOrElse { return Result.failure(it) }
    val items = first.items.toMutableList()
    val exhausted = withTimeoutOrNull(LIBRARY_SNAPSHOT_TIMEOUT_MILLIS) {
        var token = first.continuation
        val consumed = mutableSetOf<String>()
        while (token != null) {
            coroutineContext.ensureActive()
            if (token.isBlank() || consumed.size >= LIBRARY_SNAPSHOT_MAX_REQUESTS || !consumed.add(token)) return@withTimeoutOrNull false
            val page = try {
                source.continuation(token)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                return@withTimeoutOrNull false
            }
            // The parser maps an unrecognized envelope to empty/null; that cannot prove completion.
            if (page.items.isEmpty() && page.continuation == null) return@withTimeoutOrNull false
            items += page.items
            token = page.continuation
        }
        true
    } ?: false
    // An empty listing carries no validity marker either, so it never justifies removals.
    return Result.success(LibrarySnapshot(items, complete = exhausted && items.isNotEmpty()))
}

/** Soft sync is additive; even an authoritative remote snapshot cannot own local-file content. */
internal fun mayRemoveMissingLibraryEntry(authoritative: Boolean, isLocal: Boolean): Boolean =
    authoritative && !isLocal
