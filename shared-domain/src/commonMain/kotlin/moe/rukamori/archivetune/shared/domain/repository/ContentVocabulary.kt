/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.repository

enum class ContentProvider(val id: String) {
    LOCAL("local"), YOUTUBE("youtube"), SPOTIFY_CATALOG("spotify"), SPOTIFY_STREAMING("spotify_streaming"),
    TIDAL("tidal"), QOBUZ("qobuz"), QOBUZ_BACKUP("qobuz_backup"), DEEZER("deezer"),
    APPLE("apple"), JIOSAAVN("jiosaavn"), AMAZON("amazon"), QQ("qq"), TELEGRAM("telegram"),
    LASTFM("lastfm"), DISCORD("discord");
    companion object {
        fun fromId(id: String): ContentProvider = entries.singleOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unsupported content provider")
    }
}

/** Stable vocabulary, NOT a claim that every provider supports every surface/filter. */
enum class ContentSurface(val id: String) {
    HOME("home"), EXPLORE("explore"), DISCOVERY("discovery"), SEARCH("search"),
    ALBUM("album"), ARTIST("artist"), PLAYLIST("playlist"), PODCAST("podcast"),
    LIBRARY_SONGS("library_songs"), LIBRARY_LIKED("library_liked"), LIBRARY_ALBUMS("library_albums"),
    LIBRARY_ARTISTS("library_artists"), LIBRARY_PLAYLISTS("library_playlists"),
    LIBRARY_PLAYLIST_ITEMS("library_playlist_items"), LIBRARY_PODCASTS("library_podcasts"),
    LIBRARY_MIX("library_mix"),
    DOWNLOADS("downloads"), HISTORY("history"), STATISTICS("statistics"), RECAP("recap"),
    NEWS("news"), SOCIAL("social"), ACCOUNT("account");
    companion object {
        fun fromId(id: String): ContentSurface = entries.singleOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unsupported content surface")
    }
}

enum class ContentSearchFilter { ALL, SONGS, VIDEOS, ALBUMS, ARTISTS, PLAYLISTS, FEATURED_PLAYLISTS, COMMUNITY_PLAYLISTS, PODCASTS, EPISODES }

/**
 * A section is a provider's opaque browse id/params pair, not a localized display title.
 * Continuations must be returned to the SAME request/provider; adapters reject foreign tokens.
 */
data class ContentSection(val id: String, val params: String? = null) {
    init { require(id.isNotBlank()) }
}

data class ContentRequest(
    val provider: ContentProvider,
    val surface: ContentSurface,
    val text: String? = null,
    val parent: ContentRef? = null,
    val section: ContentSection? = null,
    val filter: ContentSearchFilter = ContentSearchFilter.ALL,
) {
    init {
        require(parent == null || parent.provider == provider.id) { "Foreign content parent" }
        require(surface == ContentSurface.SEARCH || filter == ContentSearchFilter.ALL)
    }
    /** Only the old, representable subset may cross this bridge. Never silently drop a filter. */
    fun legacyQuery(): ContentQuery {
        require(section == null && filter == ContentSearchFilter.ALL)
        return ContentQuery(surface.id, text, parent)
    }
    companion object {
        fun fromLegacy(query: ContentQuery, provider: ContentProvider): ContentRequest =
            ContentRequest(provider, ContentSurface.fromId(query.surface), query.text, query.parent)
    }
}

interface TypedContentRepository : ContentRepository {
    val provider: ContentProvider
    suspend fun page(request: ContentRequest, continuation: String? = null): ContentPage
    override suspend fun page(query: ContentQuery, continuation: String?): ContentPage =
        page(ContentRequest.fromLegacy(query, provider), continuation)
}
