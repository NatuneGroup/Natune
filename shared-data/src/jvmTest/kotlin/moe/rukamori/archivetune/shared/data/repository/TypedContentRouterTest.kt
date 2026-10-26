package moe.rukamori.archivetune.shared.data.repository

import kotlinx.coroutines.test.runTest
import kotlin.test.*
import moe.rukamori.archivetune.shared.data.persistence.*
import moe.rukamori.archivetune.shared.domain.repository.*

class TypedContentRouterTest {
    @Test fun actualRoomBridgeRetainsDuplicatesAndLegacyQueriesWithoutDroppingUnsupportedFields() = runTest {
        val db = openPortableStateDatabase(":memory:")
        try {
            val local = RoomLibraryRepository(db.library())
            val item = ContentItem(ContentRef("youtube", ContentKind.SONG, "same"), "Song")
            local.createPlaylist("p", "Playlist")
            local.saveItems(listOf(item))
            val bridge = TypedRoomLibraryRepository(local)
            bridge.mutate(LibraryMutation.AddToPlaylist("p",
                listOf(ContentOccurrence("a", item), ContentOccurrence("b", item))))
            val router = ContentRepositoryRouter(listOf(bridge))
            val request = ContentRequest(ContentProvider.LOCAL, ContentSurface.LIBRARY_PLAYLIST_ITEMS,
                parent = ContentRef("local", ContentKind.PLAYLIST, "p"))
            assertEquals(listOf("a", "b"), router.page(request).items.map { it.occurrenceId })
            assertEquals(router.page(request), bridge.page(request.legacyQuery()))
            assertFailsWith<IllegalArgumentException> { router.page(request.copy(section = ContentSection("unsupported"))) }
            assertFailsWith<UnsupportedContentOperation> {
                router.page(ContentRequest(ContentProvider.TIDAL, ContentSurface.SEARCH, text = "Song"))
            }
            assertFailsWith<IllegalArgumentException> { ContentRepositoryRouter(listOf(bridge, bridge)) }
        } finally { db.close() }
    }
}
