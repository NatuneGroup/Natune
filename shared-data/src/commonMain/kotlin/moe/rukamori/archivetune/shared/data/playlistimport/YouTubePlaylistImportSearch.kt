/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.playlistimport

import moe.rukamori.archivetune.shared.domain.playlistimport.PlaylistImportSearch
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentSearchFilter
import moe.rukamori.archivetune.shared.domain.repository.ContentSurface
import moe.rukamori.archivetune.shared.domain.repository.TypedContentRepository

/**
 * The import's online fallback over the typed YouTube repository that the host supplies. It is the baseline search: a
 * song-filtered YouTube search whose first distinct song result is the match. The filter is what makes the first result
 * a song, so no other kind can be returned. A search with no song gives null.
 */
class YouTubePlaylistImportSearch(private val content: TypedContentRepository) : PlaylistImportSearch {
    init {
        require(content.provider == ContentProvider.YOUTUBE) { "The import search needs the YouTube repository" }
    }

    override suspend fun firstSong(query: String): ContentItem? {
        val page = content.page(
            ContentRequest(ContentProvider.YOUTUBE, ContentSurface.SEARCH, query, filter = ContentSearchFilter.SONGS),
        )
        return page.items.map { it.item }
            .filter { it.ref.kind == ContentKind.SONG }
            .distinctBy { it.ref.id }
            .firstOrNull()
    }
}
