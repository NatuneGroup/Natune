package moe.rukamori.archivetune.shared.ui.host

import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.shared.domain.queue.FinitePlaylistCompletion
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentPage
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentSurface
import moe.rukamori.archivetune.shared.domain.repository.TypedContentRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CatalogueListCompletionTest {
    private val request = ContentRequest(ContentProvider.YOUTUBE, ContentSurface.EXPLORE)

    private fun row(id: String, kind: ContentKind = ContentKind.SONG) =
        ContentOccurrence(id, ContentItem(ContentRef("youtube", kind, id), "Song $id"))

    private fun catalogOf(pages: Map<String?, ContentPage>) = object : TypedContentRepository {
        override val provider = ContentProvider.YOUTUBE
        override suspend fun page(request: ContentRequest, continuation: String?): ContentPage =
            pages.getValue(continuation)
        override suspend fun item(ref: ContentRef): ContentItem = error("unused")
    }

    @Test fun heldRowsAndTheirContinuationReadToTheEnd() = runTest {
        val catalog = catalogOf(
            mapOf("next" to ContentPage(listOf(row("c")), null))
        )
        val rows = completeCatalogueList(catalog, request, listOf(row("a"), row("b")), "next")
        assertEquals(listOf("a", "b", "c"), rows?.map { it.occurrenceId })
    }

    @Test fun anEmptyHeldListStartsFromTheFirstPage() = runTest {
        val catalog = catalogOf(
            mapOf(
                null to ContentPage(listOf(row("a")), "next"),
                "next" to ContentPage(listOf(row("b")), null),
            )
        )
        val rows = completeCatalogueList(catalog, request, emptyList(), null)
        assertEquals(listOf("a", "b"), rows?.map { it.occurrenceId })
    }

    @Test fun aRepeatedContinuationIsIncomplete() = runTest {
        val catalog = catalogOf(
            mapOf("loop" to ContentPage(listOf(row("b")), "loop"))
        )
        assertNull(completeCatalogueList(catalog, request, listOf(row("a")), "loop"))
    }

    @Test fun aListBeyondTheRequestBoundIsIncomplete() = runTest {
        val pages = (0..FinitePlaylistCompletion.MAX_REQUESTS).associate { step ->
            val token = if (step == 0) null else "t$step"
            token to ContentPage(listOf(row("r$step")), "t${step + 1}")
        }
        assertNull(completeCatalogueList(catalogOf(pages), request, emptyList(), null))
    }

    @Test fun aRepeatedOccurrenceIdIsIncomplete() = runTest {
        val catalog = catalogOf(
            mapOf("next" to ContentPage(listOf(row("a")), null))
        )
        assertNull(completeCatalogueList(catalog, request, listOf(row("a")), "next"))
    }

    @Test fun onlySongsAndEpisodesArePlayable() {
        val rows = playableRows(
            listOf(
                row("song"),
                row("episode", ContentKind.EPISODE),
                row("album", ContentKind.ALBUM),
                row("artist", ContentKind.ARTIST),
                row("playlist", ContentKind.PLAYLIST),
            )
        )
        assertEquals(listOf("song", "episode"), rows.map { it.occurrenceId })
    }
}
