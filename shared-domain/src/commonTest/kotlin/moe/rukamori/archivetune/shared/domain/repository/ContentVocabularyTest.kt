package moe.rukamori.archivetune.shared.domain.repository

import kotlin.test.*

class ContentVocabularyTest {
    @Test fun fourLocalQueriesRemainExactlyRepresentable() {
        for (id in listOf("library_songs", "library_liked", "library_playlists", "library_playlist_items")) {
            val query = ContentQuery(id)
            assertEquals(query, ContentRequest.fromLegacy(query, ContentProvider.LOCAL).legacyQuery())
        }
    }
    @Test fun neverDropsFiltersSectionOrForeignParent() {
        assertFailsWith<IllegalArgumentException> {
            ContentRequest(ContentProvider.YOUTUBE, ContentSurface.SEARCH, "song", filter = ContentSearchFilter.ALBUMS).legacyQuery()
        }
        assertFailsWith<IllegalArgumentException> {
            ContentRequest(ContentProvider.YOUTUBE, ContentSurface.EXPLORE, section = ContentSection("browse", "params")).legacyQuery()
        }
        assertFailsWith<IllegalArgumentException> {
            ContentRequest(ContentProvider.TIDAL, ContentSurface.PLAYLIST, parent = ContentRef("youtube", ContentKind.PLAYLIST, "PLx"))
        }
        assertFailsWith<IllegalArgumentException> { ContentSurface.fromId("guessed_surface") }
        assertFailsWith<IllegalArgumentException> { ContentProvider.fromId("spotify_catalog_account_cookie") }
    }
    @Test fun finiteAndRadioCannotBeConfusedAndDuplicateSelectionStaysPrecise() {
        fun collection(id: String) = ContentRequest(ContentProvider.YOUTUBE, ContentSurface.PLAYLIST,
            parent = ContentRef("youtube", ContentKind.PLAYLIST, id))
        assertFailsWith<IllegalArgumentException> { QueueLoadRequest(collection("RDmix"), QueueCollectionKind.FINITE) }
        assertFailsWith<IllegalArgumentException> { QueueLoadRequest(collection("RDmix"), QueueCollectionKind.RADIO, shuffle = true) }
        val selection = QueueStart(ContentRef("youtube", ContentKind.SONG, "same-id"), "occurrence-b", "set-b", 44)
        val request = QueueLoadRequest(collection("VLPLfinite"), QueueCollectionKind.FINITE, selection, positionMs = 12345)
        assertEquals(selection, request.start)
        assertEquals(12345, request.positionMs)
    }
}
