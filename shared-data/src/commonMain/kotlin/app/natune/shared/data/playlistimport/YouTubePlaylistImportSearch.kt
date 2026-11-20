package app.natune.shared.data.playlistimport

import app.natune.shared.domain.playlistimport.PlaylistImportSearch
import app.natune.shared.domain.repository.ContentItem
import app.natune.shared.domain.repository.ContentKind
import app.natune.shared.domain.repository.ContentProvider
import app.natune.shared.domain.repository.ContentRequest
import app.natune.shared.domain.repository.ContentSearchFilter
import app.natune.shared.domain.repository.ContentSurface
import app.natune.shared.domain.repository.TypedContentRepository

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
