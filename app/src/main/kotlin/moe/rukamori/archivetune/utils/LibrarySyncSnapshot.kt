package moe.rukamori.archivetune.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.innertube.YouTube
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
    override suspend fun continuation(token: String) = YouTube.libraryContinuation(token).getOrThrow()
}

internal class IncompleteLibrarySyncException : Exception("Remote library loading is incomplete")

/** Core's completed() discards unfinished tokens; sync must establish exhaustion before deletion. */
internal suspend fun Result<LibraryPage>.completedForSync(
    source: LibrarySyncSource = YouTubeLibrarySyncSource,
): Result<LibraryPage> = try {
    Result.success(withTimeout(120_000L) {
        val first = getOrThrow()
        val items = first.items.toMutableList()
        var token = first.continuation
        val consumed = mutableSetOf<String>()
        while (token != null) {
            coroutineContext.ensureActive()
            if (token.isBlank() || consumed.size >= 50 || !consumed.add(token)) throw IncompleteLibrarySyncException()
            val page = source.continuation(token)
            // The parser maps an unrecognized envelope to empty/null; that cannot prove completion.
            if (page.items.isEmpty() && page.continuation == null) throw IncompleteLibrarySyncException()
            items += page.items
            token = page.continuation
        }
        // An empty initial result has no validity/count bit either; retain local data conservatively.
        if (items.isEmpty()) throw IncompleteLibrarySyncException()
        first.copy(items = items, continuation = null)
    })
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    Result.failure(error)
}

/** Soft sync is additive; even an authoritative remote snapshot cannot own local-file content. */
internal fun mayRemoveMissingLibraryEntry(authoritative: Boolean, isLocal: Boolean): Boolean =
    authoritative && !isLocal
