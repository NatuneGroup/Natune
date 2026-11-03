package moe.rukamori.archivetune.shared.domain.repository

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuiltInCollectionFinitePolicyTest {
    private fun local(surface: ContentSurface, playlistId: String? = null) = ContentRequest(
        provider = ContentProvider.LOCAL,
        surface = surface,
        parent = playlistId?.let { ContentRef(ContentProvider.LOCAL.id, ContentKind.PLAYLIST, it) },
    )

    @Test fun libraryListsAndLocalAutoPlaylistsStayFinite() {
        assertTrue(BuiltInCollectionFinitePolicy.staysFinite(local(ContentSurface.LIBRARY_SONGS)))
        assertTrue(BuiltInCollectionFinitePolicy.staysFinite(local(ContentSurface.DOWNLOADS)))
        assertTrue(BuiltInCollectionFinitePolicy.staysFinite(local(ContentSurface.STATISTICS)))
        assertTrue(
            BuiltInCollectionFinitePolicy.staysFinite(local(ContentSurface.LIBRARY_PLAYLIST_ITEMS, "auto_playlist/liked")),
        )
    }

    @Test fun userPlaylistsAndCatalogueCollectionsKeepTheirContinuation() {
        assertFalse(BuiltInCollectionFinitePolicy.staysFinite(local(ContentSurface.LIBRARY_PLAYLIST_ITEMS, "user-1")))
        assertFalse(
            BuiltInCollectionFinitePolicy.staysFinite(
                ContentRequest(
                    ContentProvider.YOUTUBE,
                    ContentSurface.PLAYLIST,
                    parent = ContentRef(ContentProvider.YOUTUBE.id, ContentKind.PLAYLIST, "auto_playlist/liked"),
                ),
            ),
        )
        assertFalse(BuiltInCollectionFinitePolicy.staysFinite(ContentRequest(ContentProvider.YOUTUBE, ContentSurface.ALBUM)))
    }
}
