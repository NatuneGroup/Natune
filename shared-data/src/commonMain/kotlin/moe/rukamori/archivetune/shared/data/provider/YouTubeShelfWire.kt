package moe.rukamori.archivetune.shared.data.provider

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import moe.rukamori.archivetune.shared.domain.repository.ContentCredit
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentSearchFilter
import moe.rukamori.archivetune.shared.domain.repository.ContentSection

/**
 * The section-list renderers the YouTube shelf feeds answer with — the home feed, charts, moods & genres, new
 * releases and the unfiltered search summary. The shapes live in commonMain because nothing here is platform
 * specific: a host only has to hand over the renderer JSON its InnerTube client returned, and the mapping to
 * [YouTubeShelfPage], [YouTubeExplore], [YouTubeRelease] and [YouTubeEntry] happens once for every host.
 *
 * Only the fields the hosts read are declared; `ignoreUnknownKeys` drops the rest. A renderer the provider changed
 * shape on yields no entry rather than a made-up one, and a feed that returned nothing stays an empty page that the
 * host shows as such.
 */

/** The id the explore page's new-release carousel points its "more" button at. */
private const val BROWSE_ID_NEW_RELEASE_ALBUMS = "FEmusic_new_releases_albums"

/** The id the explore page's mood & genre carousel points its "more" button at. */
private const val BROWSE_ID_MOODS_AND_GENRES = "FEmusic_moods_and_genres"

/**
 * The `params` value of a filtered search, or null when the filter has no verified one. The six values are the Android
 * `SearchFilter` constants verbatim, so a filtered iOS search asks the same surface the Android search screen asks.
 * ALL is the unfiltered summary (no `params` at all); PLAYLISTS, PODCASTS and EPISODES have no verified value in the
 * Android app either, so they stay a refusal rather than a different surface's results.
 */
internal fun youTubeSearchFilterParam(filter: ContentSearchFilter): String? =
    when (filter) {
        ContentSearchFilter.SONGS -> "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
        ContentSearchFilter.VIDEOS -> "EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D"
        ContentSearchFilter.ALBUMS -> "EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D"
        ContentSearchFilter.ARTISTS -> "EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D"
        ContentSearchFilter.FEATURED_PLAYLISTS -> "EgeKAQQoADgBagwQDhAKEAMQBRAJEAQ%3D"
        ContentSearchFilter.COMMUNITY_PLAYLISTS -> "EgeKAQQoAEABagoQAxAEEAoQCRAF"
        ContentSearchFilter.ALL,
        ContentSearchFilter.PLAYLISTS,
        ContentSearchFilter.PODCASTS,
        ContentSearchFilter.EPISODES,
        -> null
    }

private val SHELF_JSON = Json { ignoreUnknownKeys = true }

private val DURATION_REGEX = Regex("^\\d{1,2}:\\d{2}(?::\\d{2})?\$")

private const val EXPLICIT_BADGE = "MUSIC_EXPLICIT_BADGE"

/** The section-list renderer of a browse response's contents, whichever wrapper carries it. */
internal fun youTubeSectionListRenderer(
    singleColumn: JsonObject?,
    sectionList: JsonObject?,
    twoColumn: JsonObject?,
): JsonObject? {
    sectionList?.let { return it }
    val tabs = (singleColumn?.get("tabs") as? JsonArray).orEmpty()
    val fromTab =
        tabs.firstOrNull()?.objectOrNull()
            ?.get("tabRenderer")?.objectOrNull()
            ?.get("content")?.objectOrNull()
            ?.get("sectionListRenderer")?.objectOrNull()
    if (fromTab != null) return fromTab
    return twoColumn?.get("secondaryContents")?.objectOrNull()?.get("sectionListRenderer")?.objectOrNull()
}

/** The section-list renderer of a search response's tabbed results: the first tab is the result page. */
internal fun youTubeTabbedSectionList(tabbedSearchResultsRenderer: JsonObject?): JsonObject? {
    val tabs = (tabbedSearchResultsRenderer?.get("tabs") as? JsonArray).orEmpty()
    return tabs.firstOrNull()?.objectOrNull()
        ?.get("tabRenderer")?.objectOrNull()
        ?.get("content")?.objectOrNull()
        ?.get("sectionListRenderer")?.objectOrNull()
}

/** The home feed. A continuation page answers with a section list too; it simply carries no chips. */
internal fun youTubeHomeShelfPage(sectionList: JsonObject?): YouTubeShelfPage {
    val list = sectionList?.decodeSectionList() ?: return YouTubeShelfPage(emptyList())
    return YouTubeShelfPage(
        shelves = list.contents.orEmpty().mapIndexedNotNull { index, item -> item.musicCarouselShelfRenderer?.toShelf(index) },
        continuation = list.continuations.firstToken(),
        chips = list.header?.chipCloudRenderer?.chips.orEmpty().mapNotNull { it.toChip() },
    )
}

/**
 * The charts sheet. Ranked top songs come first, then the genre and new-release shelves. The provider answers
 * continuation pages with the sections in the continuation payload, so those are read as well; a host that does not
 * page the sheet simply never asks.
 */
internal fun youTubeChartsShelfPage(sectionList: JsonObject?, continuationSectionList: JsonObject?): YouTubeShelfPage {
    val list = (sectionList ?: continuationSectionList)?.decodeSectionList() ?: return YouTubeShelfPage(emptyList())
    val shelves =
        list.contents.orEmpty().mapIndexedNotNull { index, item ->
            item.musicCarouselShelfRenderer?.toChartShelf(index) ?: item.gridRenderer?.toReleaseShelf(index)
        }
    return YouTubeShelfPage(
        shelves = shelves,
        continuation = continuationSectionList?.decodeSectionList()?.continuations.firstToken() ?: list.continuations.firstToken(),
    )
}

/** The explore page: the new-release carousel and the mood/genre tiles, found by their "more" targets. */
internal fun youTubeExplorePage(sectionList: JsonObject?): YouTubeExplore {
    val list = sectionList?.decodeSectionList() ?: return YouTubeExplore(emptyList(), emptyList())
    val carousels = list.contents.orEmpty().mapNotNull { it.musicCarouselShelfRenderer }
    val releases = carousels.firstOrNull { it.moreBrowseId() == BROWSE_ID_NEW_RELEASE_ALBUMS }
    val moods = carousels.firstOrNull { it.moreBrowseId() == BROWSE_ID_MOODS_AND_GENRES }
    return YouTubeExplore(
        releases = releases?.contents.orEmpty().mapNotNull { it.musicTwoRowItemRenderer?.toRelease() },
        moods = moods?.contents.orEmpty().mapNotNull { it.musicNavigationButtonRenderer?.toMood() },
    )
}

/** The new-releases page: every album the page carries, in the provider's order. */
internal fun youTubeNewReleasesPage(sectionList: JsonObject?): List<YouTubeRelease> {
    val list = sectionList?.decodeSectionList() ?: return emptyList()
    return list.contents.orEmpty().flatMap { item ->
        buildList<YouTubeRelease> {
            item.gridRenderer?.items.orEmpty().forEach { cell -> cell.musicTwoRowItemRenderer?.toRelease()?.let(::add) }
            item.musicCarouselShelfRenderer?.contents.orEmpty()
                .forEach { cell -> cell.musicTwoRowItemRenderer?.toRelease()?.let(::add) }
            item.itemSectionRenderer?.contents.orEmpty().forEach { inner ->
                inner.gridRenderer?.items.orEmpty()
                    .forEach { cell -> cell.musicTwoRowItemRenderer?.toRelease()?.let(::add) }
            }
        }
    }
}

/**
 * The unfiltered search summary, which is the page YouTube answers an `ALL` search with: a top-result card, the
 * inline snippet lists and the type shelves. Filters keep their own `params` request and are not read here.
 */
internal fun youTubeSearchSummaryPage(tabbedSearchResultsRenderer: JsonObject?, sectionList: JsonObject?): List<YouTubeEntry> {
    val list = (youTubeTabbedSectionList(tabbedSearchResultsRenderer) ?: sectionList)?.decodeSectionList()
        ?: return emptyList()
    val entries = mutableListOf<YouTubeEntry>()
    list.contents.orEmpty().forEach { item ->
        item.musicCardShelfRenderer?.let { card ->
            card.toEntry()?.let(entries::add)
            card.contents.orEmpty().forEach { inline -> inline.musicResponsiveListItemRenderer?.toEntry()?.let(entries::add) }
        }
        item.musicShelfRenderer?.let { shelf -> shelf.contents.orEmpty().forEach { cell -> cell.toEntry()?.let(entries::add) } }
        item.itemSectionRenderer?.let { section ->
            section.contents.orEmpty().forEach { inner ->
                inner.musicResponsiveListItemRenderer?.toEntry()?.let(entries::add)
                inner.musicShelfRenderer?.let { shelf -> shelf.contents.orEmpty().forEach { cell -> cell.toEntry()?.let(entries::add) } }
            }
        }
    }
    return entries.distinctBy { it.id }
}

/** The remote artist page: header facts, ordered sections and the watch endpoints the provider gave. */
internal fun youTubeArtistDetailPage(
    browseId: String,
    immersiveHeader: JsonObject?,
    visualHeader: JsonObject?,
    detailHeader: JsonObject?,
    responsiveHeader: JsonObject?,
    sectionList: JsonObject?,
): YouTubeArtistDetail {
    val headerJson = immersiveHeader ?: visualHeader ?: detailHeader ?: responsiveHeader
    val header = headerJson?.let { SHELF_JSON.decodeFromJsonElement<WireArtistHeader>(it) }
    val artistTitle = header?.title?.text()?.takeIf(String::isNotBlank) ?: browseId
    val subscribersText =
        header?.subscriptionButton?.subscribeButtonRenderer?.subscriberCountText?.text()?.takeIf(String::isNotBlank)
    val monthlyListenersText = header?.monthlyListenerCount?.text()?.takeIf(String::isNotBlank)
    val description = header?.description?.text()?.takeIf(String::isNotBlank)
    val artworkUri = (header?.thumbnail ?: header?.foregroundThumbnail)?.bestUrl()
    val playTarget = header?.playButton?.buttonRenderer?.navigationEndpoint.toWatchTarget()
    val shuffleTarget = header?.shuffleButton?.buttonRenderer?.navigationEndpoint.toWatchTarget()
    val radioTarget = header?.startRadioButton?.buttonRenderer?.navigationEndpoint.toWatchTarget()

    val list = sectionList?.decodeSectionList()
    val shelves = mutableListOf<YouTubeArtistSection>()
    val releases = mutableListOf<YouTubeRelease>()
    list?.contents.orEmpty().forEach { item ->
        item.musicCarouselShelfRenderer?.let { carousel ->
            val shelfHeader = carousel.header?.musicCarouselShelfBasicHeaderRenderer ?: return@let
            val title = shelfHeader.title?.text()?.takeIf(String::isNotBlank) ?: return@let
            val entries = carousel.contents.orEmpty().mapNotNull { it.toEntry() }
            if (entries.isNotEmpty()) {
                shelves +=
                    YouTubeArtistSection(
                        title = title,
                        entries = entries,
                        more = shelfHeader.moreContentButton?.buttonRenderer?.navigationEndpoint?.toSection(),
                        grid = true,
                    )
            }
            carousel.contents.orEmpty().forEach { cell -> cell.musicTwoRowItemRenderer?.toRelease()?.let(releases::add) }
        }
        item.musicShelfRenderer?.let { shelf ->
            val title = shelf.title?.text()?.takeIf(String::isNotBlank) ?: return@let
            val entries = shelf.contents.orEmpty().mapNotNull { it.toEntry() }
            if (entries.isNotEmpty()) {
                shelves +=
                    YouTubeArtistSection(
                        title = title,
                        entries = entries,
                        more = shelf.bottomEndpoint?.toSection(),
                        grid = false,
                    )
            }
        }
    }
    return YouTubeArtistDetail(
        artist =
            YouTubeEntry(
                kind = ContentKind.ARTIST,
                id = browseId,
                title = artistTitle,
                subtitle = subscribersText ?: monthlyListenersText.orEmpty(),
                artworkUri = artworkUri,
            ),
        subscribersText = subscribersText,
        monthlyListenersText = monthlyListenersText,
        description = description,
        shelves = shelves,
        releases = releases.distinctBy { it.entry.id },
        shareLink = "https://music.youtube.com/channel/$browseId",
        play = playTarget,
        shuffle = shuffleTarget,
        radio = radioTarget,
    )
}

private fun WireEndpoint?.toWatchTarget(): YouTubeWatchTarget? {
    this ?: return null
    watchEndpoint?.let { watch ->
        if (watch.videoId != null || watch.playlistId != null) {
            return YouTubeWatchTarget(watch.videoId, watch.playlistId, watch.params)
        }
    }
    watchPlaylistEndpoint?.let { wp ->
        if (wp.playlistId != null) {
            return YouTubeWatchTarget(null, wp.playlistId, wp.params)
        }
    }
    return null
}

private fun JsonObject?.decodeSectionList(): WireSectionList? =
    this?.let { SHELF_JSON.decodeFromJsonElement<WireSectionList>(it) }

private fun JsonElement?.objectOrNull(): JsonObject? = this as? JsonObject

private fun WireCarouselShelf.toShelf(index: Int): YouTubeShelf? {
    val header = header?.musicCarouselShelfBasicHeaderRenderer ?: return null
    val title = header.title?.text()?.takeIf(String::isNotBlank) ?: return null
    val entries = contents.orEmpty().mapNotNull { it.toEntry() }
    if (entries.isEmpty()) return null
    return YouTubeShelf(
        key = "$title#$index",
        title = title,
        label = header.strapline?.text()?.takeIf(String::isNotBlank),
        thumbnail = header.thumbnail?.bestUrl(),
        entries = entries,
        more = header.moreContentButton?.buttonRenderer?.navigationEndpoint?.toSection(),
        numItemsPerColumn = numItemsPerColumn,
    )
}

private fun WireCarouselShelf.moreBrowseId(): String? =
    header?.musicCarouselShelfBasicHeaderRenderer
        ?.moreContentButton?.buttonRenderer?.navigationEndpoint?.browseEndpoint?.browseId

/**
 * A chart shelf: the top section is a ranked list of songs, the genre and new-release sections are tile grids.
 * The Android charts screen decides the same way from the section title.
 */
private fun WireCarouselShelf.toChartShelf(index: Int): YouTubeShelf? {
    val header = header?.musicCarouselShelfBasicHeaderRenderer ?: return null
    val title = header.title?.text()?.takeIf(String::isNotBlank) ?: return null
    val entries = contents.orEmpty().mapNotNull { it.toEntry() }
    if (entries.isEmpty()) return null
    val top = title.contains("Top", ignoreCase = true)
    return YouTubeShelf(
        key = "$title#$index",
        title = title,
        entries = entries,
        numbered = top,
        grid = !top,
    )
}

private fun WireGrid.toReleaseShelf(index: Int): YouTubeShelf? {
    val title = header?.gridHeaderRenderer?.title?.text()?.takeIf(String::isNotBlank) ?: return null
    val entries = items.orEmpty().mapNotNull { it.musicTwoRowItemRenderer?.toEntry() }
    if (entries.isEmpty()) return null
    return YouTubeShelf(key = "$title#$index", title = title, entries = entries, grid = true)
}

private fun WireChip.toChip(): YouTubeFeedChip? {
    val renderer = chipCloudChipRenderer ?: return null
    val title = renderer.text?.text()?.takeIf(String::isNotBlank) ?: return null
    return YouTubeFeedChip(
        title = title,
        params = renderer.navigationEndpoint?.browseEndpoint?.params,
        deselectParams = renderer.onDeselectedCommand?.browseEndpoint?.params,
    )
}

private fun WireShelfCell.toEntry(): YouTubeEntry? =
    musicResponsiveListItemRenderer?.toEntry() ?: musicTwoRowItemRenderer?.toEntry()

private fun WireResponsiveItem.toEntry(): YouTubeEntry? {
    val titleRuns = flexColumns?.getOrNull(0)?.musicResponsiveListItemFlexColumnRenderer?.text?.runs ?: return null
    val title = titleRuns.joinToString("") { it.text.orEmpty() }.trim().takeIf(String::isNotBlank) ?: return null
    val endpoint = titleRuns.firstNotNullOfOrNull { it.navigationEndpoint } ?: navigationEndpoint
    val videoId = playlistItemData?.videoId?.takeIf(String::isNotBlank)
    val endpointKind = endpoint.toKind()
    val id = videoId ?: endpoint.toId() ?: return null
    // A chart row has no endpoint on its title, so a playlist item id is the only proof it is playable.
    val kind = endpointKind ?: if (videoId != null) ContentKind.SONG else return null
    val groups = subtitleGroups()
    // A search row labels its own type in the first subtitle group ("Song", "Album", ...). The endpoint stays
    // authoritative whenever it named a surface of its own; the label only refines a row proven playable.
    // A linked run in that group is the artist or album credit itself, so only plain text can carry the label.
    val typeLabel = groups.firstOrNull()?.takeIf { group -> group.none { it.navigationEndpoint != null } }
    val labeled = typeLabel?.joinToString("") { it.text.orEmpty() }?.trim()?.kindOfLabel()
    val resolved = if (kind == ContentKind.SONG && labeled != null) labeled else kind
    val artists = groups.artistCredits()
    val album = groups.firstNotNullOfOrNull { group -> group.firstNotNullOfOrNull { it.albumCredit() } }
    val duration = groups.lastOrNull()?.joinToString("") { it.text.orEmpty() }?.takeIf { DURATION_REGEX.matches(it) }
    return YouTubeEntry(
        kind = resolved,
        id = id,
        title = title,
        subtitle = youTubeArtistSubtitle(artists, groups.firstOrNull()?.joinToString("") { it.text.orEmpty() }.orEmpty()),
        artworkUri = thumbnail?.bestUrl(),
        durationSeconds = duration?.toSeconds(),
        explicit = badges.orEmpty().any { it.musicInlineBadgeRenderer?.iconType == EXPLICIT_BADGE },
        setVideoId = playlistItemData?.playlistSetVideoId,
        album = album?.second,
        artists = artists,
        albumId = album?.first,
    )
}

private fun WireResponsiveItem.subtitleGroups(): List<List<WireRun>> {
    val groups = mutableListOf<List<WireRun>>()
    flexColumns.orEmpty().drop(1).forEach { column ->
        column.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.splitBySeparator()?.let { groups += it }
    }
    return groups.filter { group -> group.any { !it.text.isNullOrBlank() } }
}

private fun List<WireRun>.splitBySeparator(): List<List<WireRun>> {
    val groups = mutableListOf<List<WireRun>>()
    var current = mutableListOf<WireRun>()
    forEach { run ->
        if (run.text == " • ") {
            groups += current
            current = mutableListOf()
        } else {
            current += run
        }
    }
    groups += current
    return groups
}

/** The artist credits the provider linked, kept in its order. A credit without a name is dropped. */
private fun List<List<WireRun>>.artistCredits(): List<ContentCredit> =
    youTubeArtistCredits(this, { it.text }, { it.navigationEndpoint?.browseEndpoint?.browseId })

/** The (browseId, title) of the album credit, keyed on the album browse id's MPRE prefix. */
private fun WireRun.albumCredit(): Pair<String, String>? {
    val browseId = navigationEndpoint?.browseEndpoint?.browseId?.takeIf { it.startsWith("MPRE") } ?: return null
    return text?.takeIf(String::isNotBlank)?.let { browseId to it }
}

private fun WireTwoRowItem.toEntry(): YouTubeEntry? {
    val kind = navigationEndpoint.toKind() ?: return null
    val id = navigationEndpoint.toId() ?: return null
    val title = title?.text()?.takeIf(String::isNotBlank) ?: return null
    val artists = subtitle?.runs?.splitBySeparator().orEmpty().artistCredits()
    return YouTubeEntry(
        kind = kind,
        id = id,
        title = title,
        subtitle = youTubeArtistSubtitle(artists, subtitle?.text().orEmpty()),
        artists = artists,
        artworkUri = thumbnailRenderer?.bestUrl(),
    )
}

/** A new-release tile. The album's own browse id, the year and the release label are the provider's own facts. */
private fun WireTwoRowItem.toRelease(): YouTubeRelease? {
    val browseId = navigationEndpoint?.browseEndpoint?.browseId?.takeIf { it.startsWith("MPRE") } ?: return null
    val title = title?.text()?.takeIf(String::isNotBlank) ?: return null
    val subtitleRuns = subtitle?.runs?.takeIf { it.isNotEmpty() } ?: return null
    val groups = subtitleRuns.splitBySeparator()
    val artists =
        groups.getOrNull(1)?.filterIndexed { index, _ -> index % 2 == 0 }?.mapNotNull { run ->
            run.text?.takeIf(String::isNotBlank)?.let { name ->
                ContentCredit(name, run.navigationEndpoint?.browseEndpoint?.browseId?.takeIf(String::isNotBlank))
            }
        }.orEmpty()
    return YouTubeRelease(
        entry =
            YouTubeEntry(
                kind = ContentKind.ALBUM,
                id = browseId,
                title = title,
                subtitle = artists.joinToString(", ") { it.name },
                artworkUri = thumbnailRenderer?.bestUrl(),
                explicit = subtitleBadges.orEmpty().any { it.musicInlineBadgeRenderer?.iconType == EXPLICIT_BADGE },
                artists = artists,
            ),
        year = subtitleRuns.lastOrNull()?.text?.trim()?.toIntOrNull(),
        type =
            when (groups.firstOrNull()?.joinToString("") { it.text.orEmpty() }?.trim()?.lowercase()) {
                "single", "singles" -> YouTubeReleaseType.SINGLE
                "ep", "eps" -> YouTubeReleaseType.EP
                else -> YouTubeReleaseType.ALBUM
            },
    )
}

private fun WireCardShelf.toEntry(): YouTubeEntry? {
    val kind = onTap.toKind() ?: return null
    val id = onTap.toId() ?: return null
    val title = (header?.musicCardShelfHeaderBasicRenderer?.title ?: title)?.text()?.takeIf(String::isNotBlank) ?: return null
    return YouTubeEntry(
        kind = kind,
        id = id,
        title = title,
        subtitle = subtitle?.text().orEmpty(),
        artworkUri = thumbnail?.bestUrl(),
    )
}

private fun WireNavigationButton.toMood(): YouTubeMoodGenre? {
    val title = buttonText?.text()?.takeIf(String::isNotBlank) ?: return null
    val color = solid?.leftStripeColor ?: return null
    val browse = clickCommand?.browseEndpoint ?: return null
    val browseId = browse.browseId?.takeIf(String::isNotBlank) ?: return null
    return YouTubeMoodGenre(title, color, ContentSection(browseId, browse.params))
}

private fun WireRuns.text(): String =
    simpleText?.takeIf(String::isNotBlank) ?: runs.orEmpty().joinToString("") { it.text.orEmpty() }.trim()

private fun WireEndpoint?.toKind(): ContentKind? {
    this ?: return null
    if (watchEndpoint != null) return ContentKind.SONG
    if (watchPlaylistEndpoint != null) return ContentKind.PLAYLIST
    val browseId = browseEndpoint?.browseId ?: return null
    return when {
        browseId.startsWith("MPRE") -> ContentKind.ALBUM
        browseId.startsWith("UC") || browseId.startsWith("FEMC") -> ContentKind.ARTIST
        // A podcast show arrives playlist-shaped; its own prefix names the show page, not a playlist.
        isYouTubePodcastShowId(browseId) -> ContentKind.PODCAST
        browseId.startsWith("VL") || browseId.startsWith("PL") || browseId.startsWith("FEmusic") -> ContentKind.PLAYLIST
        else -> null
    }
}

private fun WireEndpoint?.toId(): String? {
    this ?: return null
    watchEndpoint?.videoId?.takeIf(String::isNotBlank)?.let { return it }
    watchPlaylistEndpoint?.playlistId?.takeIf(String::isNotBlank)?.let { return it.removePrefix("VL") }
    browseEndpoint?.browseId?.takeIf(String::isNotBlank)?.let { return it.removePrefix("VL") }
    return null
}

private fun WireEndpoint?.toSection(): ContentSection? {
    val browse = this?.browseEndpoint ?: return null
    val browseId = browse.browseId?.takeIf(String::isNotBlank) ?: return null
    return ContentSection(browseId, browse.params)
}

private fun String.kindOfLabel(): ContentKind? =
    when (this) {
        "Song", // A music video is a playable song in the catalogue; the contract has no separate video kind.
        "Video",
        -> ContentKind.SONG
        "Album" -> ContentKind.ALBUM
        "Artist" -> ContentKind.ARTIST
        "Playlist" -> ContentKind.PLAYLIST
        "Podcast" -> ContentKind.PODCAST
        "Episode" -> ContentKind.EPISODE
        else -> null
    }

private fun String.toSeconds(): Int? {
    val parts = split(":").mapNotNull { it.toIntOrNull() }
    return when (parts.size) {
        2 -> parts[0] * 60 + parts[1]
        3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
        else -> null
    }
}

/** The largest thumbnail, falling back to the last one the provider sent, with a protocol-relative url resolved. */
private fun WireThumbnailRenderer.bestUrl(): String? {
    val thumbnails =
        (musicThumbnailRenderer ?: croppedSquareThumbnailRenderer)?.thumbnail?.thumbnails.orEmpty()
    val best =
        thumbnails.filter { !it.url.isNullOrBlank() && it.width.orZero() > 0 && it.height.orZero() > 0 }
            .maxByOrNull { it.width.orZero().toLong() * it.height.orZero() }
            ?: thumbnails.lastOrNull { !it.url.isNullOrBlank() }
    return best?.url?.let { if (it.startsWith("//")) "https:$it" else it }
}

private fun Int?.orZero(): Int = this ?: 0

private fun List<WireContinuation>?.firstToken(): String? =
    orEmpty().firstNotNullOfOrNull {
        it.nextContinuationData?.continuation
            ?: it.nextRadioContinuationData?.continuation
            ?: it.continuationEndpoint?.continuationCommand?.token
    }

// The renderer payloads the section-list feeds use. Every field is optional: a renderer the provider dropped or
// renamed leaves a null that the mapping skips, never a zero value it would show as data.

@Serializable
private data class WireSectionList(
    val header: WireSectionHeader? = null,
    val contents: List<WireSectionItem>? = null,
    val continuations: List<WireContinuation>? = null,
)

@Serializable
private data class WireSectionHeader(val chipCloudRenderer: WireChipCloud? = null)

@Serializable
private data class WireChipCloud(val chips: List<WireChip>? = null)

@Serializable
private data class WireChip(val chipCloudChipRenderer: WireChipRenderer? = null)

@Serializable
private data class WireChipRenderer(
    val text: WireRuns? = null,
    val navigationEndpoint: WireEndpoint? = null,
    val onDeselectedCommand: WireEndpoint? = null,
)

@Serializable
private data class WireSectionItem(
    val musicCarouselShelfRenderer: WireCarouselShelf? = null,
    val musicShelfRenderer: WireShelf? = null,
    val gridRenderer: WireGrid? = null,
    val itemSectionRenderer: WireItemSection? = null,
    val musicCardShelfRenderer: WireCardShelf? = null,
    val musicResponsiveListItemRenderer: WireResponsiveItem? = null,
)

@Serializable
private data class WireCarouselShelf(
    val header: WireCarouselHeader? = null,
    val contents: List<WireShelfCell>? = null,
    val numItemsPerColumn: Int? = null,
)

@Serializable
private data class WireCarouselHeader(val musicCarouselShelfBasicHeaderRenderer: WireCarouselBasicHeader? = null)

@Serializable
private data class WireCarouselBasicHeader(
    val title: WireRuns? = null,
    val strapline: WireRuns? = null,
    val thumbnail: WireThumbnailRenderer? = null,
    val moreContentButton: WireButton? = null,
)

@Serializable
private data class WireButton(val buttonRenderer: WireButtonRenderer? = null)

@Serializable
private data class WireButtonRenderer(val navigationEndpoint: WireEndpoint? = null)

@Serializable
private data class WireShelf(
    val title: WireRuns? = null,
    val contents: List<WireShelfCell>? = null,
    val bottomEndpoint: WireEndpoint? = null,
)

@Serializable
private data class WireShelfCell(
    val musicResponsiveListItemRenderer: WireResponsiveItem? = null,
    val musicTwoRowItemRenderer: WireTwoRowItem? = null,
    val musicNavigationButtonRenderer: WireNavigationButton? = null,
)

@Serializable
private data class WireResponsiveItem(
    val flexColumns: List<WireFlexColumn>? = null,
    val thumbnail: WireThumbnailRenderer? = null,
    val playlistItemData: WirePlaylistItemData? = null,
    val navigationEndpoint: WireEndpoint? = null,
    val badges: List<WireBadge>? = null,
)

@Serializable
private data class WireFlexColumn(val musicResponsiveListItemFlexColumnRenderer: WireTextColumn? = null)

@Serializable
private data class WireTextColumn(val text: WireRuns? = null)

@Serializable
private data class WirePlaylistItemData(val videoId: String? = null, val playlistSetVideoId: String? = null)

@Serializable
private data class WireTwoRowItem(
    val title: WireRuns? = null,
    val subtitle: WireRuns? = null,
    val navigationEndpoint: WireEndpoint? = null,
    val thumbnailRenderer: WireThumbnailRenderer? = null,
    val subtitleBadges: List<WireBadge>? = null,
)

@Serializable
private data class WireNavigationButton(
    val buttonText: WireRuns? = null,
    val solid: WireSolid? = null,
    val clickCommand: WireEndpoint? = null,
)

@Serializable
private data class WireSolid(val leftStripeColor: Long? = null)

@Serializable
private data class WireGrid(
    val header: WireGridHeader? = null,
    val items: List<WireShelfCell>? = null,
)

@Serializable
private data class WireGridHeader(val gridHeaderRenderer: WireGridHeaderRenderer? = null)

@Serializable
private data class WireGridHeaderRenderer(val title: WireRuns? = null)

@Serializable
private data class WireItemSection(val contents: List<WireSectionItem>? = null)

@Serializable
private data class WireCardShelf(
    val title: WireRuns? = null,
    val subtitle: WireRuns? = null,
    val thumbnail: WireThumbnailRenderer? = null,
    val header: WireCardShelfHeader? = null,
    val contents: List<WireCardShelfContent>? = null,
    val onTap: WireEndpoint? = null,
)

@Serializable
private data class WireCardShelfHeader(val musicCardShelfHeaderBasicRenderer: WireCardShelfBasicHeader? = null)

@Serializable
private data class WireCardShelfBasicHeader(val title: WireRuns? = null)

@Serializable
private data class WireCardShelfContent(val musicResponsiveListItemRenderer: WireResponsiveItem? = null)

@Serializable
private data class WireContinuation(
    val nextContinuationData: WireContinuationData? = null,
    val nextRadioContinuationData: WireContinuationData? = null,
    val continuationEndpoint: WireContinuationEndpoint? = null,
)

@Serializable
private data class WireContinuationData(val continuation: String? = null)

@Serializable
private data class WireContinuationEndpoint(val continuationCommand: WireContinuationCommand? = null)

@Serializable
private data class WireContinuationCommand(val token: String? = null)

@Serializable
private data class WireBadge(val musicInlineBadgeRenderer: WireInlineBadge? = null)

@Serializable
private data class WireInlineBadge(val iconType: String? = null)

@Serializable
private data class WireRuns(val runs: List<WireRun>? = null, val simpleText: String? = null)

@Serializable
private data class WireRun(val text: String? = null, val navigationEndpoint: WireEndpoint? = null)

@Serializable
private data class WireEndpoint(
    val browseEndpoint: WireBrowseEndpoint? = null,
    val watchEndpoint: WireWatchEndpoint? = null,
    val watchPlaylistEndpoint: WireWatchPlaylistEndpoint? = null,
)

@Serializable
private data class WireBrowseEndpoint(val browseId: String? = null, val params: String? = null)

@Serializable
private data class WireWatchEndpoint(
    val videoId: String? = null,
    val playlistId: String? = null,
    val params: String? = null,
)

@Serializable
private data class WireArtistHeader(
    val title: WireRuns? = null,
    val description: WireRuns? = null,
    val thumbnail: WireThumbnailRenderer? = null,
    val foregroundThumbnail: WireThumbnailRenderer? = null,
    val subscriptionButton: WireSubscriptionButton? = null,
    val monthlyListenerCount: WireRuns? = null,
    val playButton: WireButton? = null,
    val startRadioButton: WireButton? = null,
    val shuffleButton: WireButton? = null,
)

@Serializable
private data class WireSubscriptionButton(
    val subscribeButtonRenderer: WireSubscribeButtonRenderer? = null,
)

@Serializable
private data class WireSubscribeButtonRenderer(
    val subscriberCountText: WireRuns? = null,
)

@Serializable
private data class WireWatchPlaylistEndpoint(val playlistId: String? = null, val params: String? = null)

@Serializable
private data class WireThumbnailRenderer(
    val musicThumbnailRenderer: WireThumbnail? = null,
    /** Album and playlist tiles carry their square art under this key instead. */
    val croppedSquareThumbnailRenderer: WireThumbnail? = null,
)

@Serializable
private data class WireThumbnail(val thumbnail: WireThumbnails? = null)

@Serializable
private data class WireThumbnails(val thumbnails: List<WireImage>? = null)

@Serializable
private data class WireImage(val url: String? = null, val width: Int? = null, val height: Int? = null)
