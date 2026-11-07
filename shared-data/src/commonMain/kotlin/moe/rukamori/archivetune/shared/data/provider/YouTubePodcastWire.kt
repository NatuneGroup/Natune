package moe.rukamori.archivetune.shared.data.provider

import kotlinx.serialization.json.JsonObject
import moe.rukamori.archivetune.shared.domain.repository.ContentKind

/**
 * YouTube Music podcast shows browse under `MPSP…` ids (`MPSPPL…`, `MPSPOL…`). The prefix is the provider's own
 * marker, and the core client reads it the same way; a show page answers with a header and episode rows, never with
 * the playlist or album shape the other detail pages carry.
 */
internal const val YOUTUBE_PODCAST_SHOW_PREFIX = "MPSP"

internal fun isYouTubePodcastShowId(id: String?): Boolean =
    id != null && id.startsWith(YOUTUBE_PODCAST_SHOW_PREFIX)

/** The show facts a podcast page carries above its episodes. Every field stays null when the page omits it. */
internal data class YouTubePodcastShow(
    val title: String? = null,
    val subtitle: String? = null,
    val artworkUri: String? = null,
    val description: String? = null,
)

/** One podcast show page reduced to the common entry contract, plus the cursor its episode shelf carries. */
internal data class YouTubePodcastPage(
    val show: YouTubePodcastShow,
    val entries: List<YouTubeEntry>,
    val continuation: String? = null,
)

/**
 * The first page of a podcast show. Episodes live in the secondary section's shelf; the primary section's shelf is
 * read as well, because a show answered without a secondary column carries its episodes there. A row that names no
 * video id is dropped rather than rendered as an unplayable episode, and the show's own credit fills in a row whose
 * subtitle omits it.
 */
internal fun youTubePodcastPage(response: JsonObject): YouTubePodcastPage {
    val contents = response.obj("contents")
    val twoColumn = contents?.obj("twoColumnBrowseResultsRenderer")
    val primary = twoColumn?.sectionList() ?: contents?.obj("sectionListRenderer")
    val show = primary?.podcastShow()
    val secondary = twoColumn?.obj("secondaryContents")?.obj("sectionListRenderer")
    val shelves = secondary?.episodeShelves().orEmpty().ifEmpty { primary?.episodeShelves().orEmpty() }
    val showName = show?.subtitle?.takeIf(String::isNotBlank) ?: show?.title?.takeIf(String::isNotBlank)
    val showId = shelves.firstNotNullOfOrNull { shelf -> shelf.showBrowseId() }
    return YouTubePodcastPage(
        show = show ?: YouTubePodcastShow(),
        entries = shelves.flatMap { shelf -> shelf.episodeEntries(showName, showId) },
        continuation = shelves.firstNotNullOfOrNull { shelf -> shelf.continuationToken() },
    )
}

/** One appended page of a show's episode shelf, answered by a continuation browse. */
internal fun youTubePodcastContinuationPage(response: JsonObject): YouTubePodcastPage {
    val appended =
        response.array("onResponseReceivedActions").orEmpty().flatMap { action ->
            (action as? JsonObject)?.obj("appendContinuationItemsAction")?.array("continuationItems").orEmpty()
        }
    val continuationContents = response.obj("continuationContents")
    val shelf =
        continuationContents?.obj("musicShelfContinuation")
            ?: continuationContents?.obj("musicPlaylistShelfContinuation")
    val cells = appended.ifEmpty { shelf?.array("contents").orEmpty() }
    return YouTubePodcastPage(
        show = YouTubePodcastShow(),
        entries = cells.mapNotNull { cell -> (cell as? JsonObject)?.let { row -> podcastEpisodeEntry(row) } },
        continuation =
            cells.mapNotNull { cell -> (cell as? JsonObject)?.continuationToken() }.firstOrNull()
                ?: shelf?.continuationToken(),
    )
}

/** The tab's section list: the tabbed layout podcasts use, and the flat one some answers carry. */
private fun JsonObject.sectionList(): JsonObject? {
    val sectionLists =
        array("tabs").orEmpty().mapNotNull { tab ->
            (tab as? JsonObject)?.obj("tabRenderer")?.obj("content")?.obj("sectionListRenderer")
        }
    val withHeader = sectionLists.firstOrNull { list ->
        list.array("contents").orEmpty().any { cell ->
            val item = cell as? JsonObject
            item?.obj("musicResponsiveHeaderRenderer") != null || item?.obj("musicImmersiveHeaderRenderer") != null
        }
    }
    return withHeader ?: sectionLists.firstOrNull()
}

private fun JsonObject.podcastShow(): YouTubePodcastShow? {
    val contents = array("contents").orEmpty().mapNotNull { it as? JsonObject }
    val header =
        contents.firstNotNullOfOrNull { it.obj("musicResponsiveHeaderRenderer") }
            ?: contents.firstNotNullOfOrNull { it.obj("musicImmersiveHeaderRenderer") }
            ?: contents.firstNotNullOfOrNull { it.obj("musicDetailHeaderRenderer") }
    if (header == null) return null
    val description =
        header.obj("description")?.obj("musicDescriptionShelfRenderer")?.obj("description")?.wireText()
            ?: contents.firstNotNullOfOrNull {
                it.obj("musicDescriptionShelfRenderer")?.obj("description")?.wireText()
            }
    return YouTubePodcastShow(
        title = header.obj("title")?.wireText(),
        // The strapline carries the show's own name as its linked run; the trailing " • 42 episodes" run is counts.
        subtitle = header.obj("straplineTextOne")?.showName() ?: header.obj("subtitle")?.showName(),
        artworkUri = header.obj("thumbnail")?.bestThumbnailUrl(),
        description = description,
    )
}

/** The name of the run that links a channel or the show itself, else the first run with text. */
private fun JsonObject.showName(): String? =
    runs().firstNotNullOfOrNull { run ->
        val linked = run.obj("navigationEndpoint")?.obj("browseEndpoint")?.stringOrNull("browseId")
        run.stringOrNull("text")?.takeIf { linked != null && it.isNotBlank() }
    } ?: runs().firstNotNullOfOrNull { run -> run.stringOrNull("text")?.takeIf(String::isNotBlank) }

/** Every shelf under a section list that can carry podcast episodes. */
private fun JsonObject.episodeShelves(): List<JsonObject> =
    array("contents").orEmpty().mapNotNull { cell ->
        val item = cell as? JsonObject ?: return@mapNotNull null
        item.obj("musicShelfRenderer") ?: item.obj("musicPlaylistShelfRenderer")
    }

/** The show every episode row of a shelf credits, so a row without its own credit still names the show. */
private fun JsonObject.showBrowseId(): String? =
    array("contents").orEmpty().firstNotNullOfOrNull { cell ->
        ((cell as? JsonObject)?.obj("musicMultiRowListItemRenderer") ?: (cell as? JsonObject)?.obj("musicResponsiveListItemRenderer"))
            ?.obj("subtitle")?.podcastShowCredit()?.second
    }

/**
 * One episode row. A show page lists episodes as `musicMultiRowListItemRenderer`s; a row answered as a responsive
 * list item is read the same way, because both carry a watch endpoint and a title.
 */
internal fun podcastEpisodeEntry(
    cell: JsonObject,
    showName: String? = null,
    showId: String? = null,
): YouTubeEntry? {
    val row = cell.obj("musicMultiRowListItemRenderer") ?: cell.obj("musicResponsiveListItemRenderer") ?: return null
    val title = row.obj("title")?.wireText() ?: row.firstFlexColumnText() ?: return null
    val videoId =
        row.obj("onTap")?.watchVideoId()
            ?: row.obj("overlay")?.obj("musicItemThumbnailOverlayRenderer")?.obj("content")
                ?.obj("musicPlayButtonRenderer")?.obj("playNavigationEndpoint")?.watchVideoId()
            ?: row.obj("playlistItemData")?.stringOrNull("videoId")
            ?: row.obj("navigationEndpoint")?.watchVideoId()
            ?: return null
    val credit = row.obj("subtitle")?.podcastShowCredit()
    val durationText =
        row.obj("playbackProgress")?.obj("musicPlaybackProgressRenderer")?.obj("durationText")?.wireText()
            ?: row.array("fixedColumns").orEmpty().firstNotNullOfOrNull { column ->
                (column as? JsonObject)?.obj("musicResponsiveListItemFixedColumnRenderer")?.obj("text")?.wireText()
                    ?.takeIf { text -> text.podcastDuration() != null }
            }
    return YouTubeEntry(
        kind = ContentKind.EPISODE,
        id = videoId,
        title = title,
        subtitle = credit?.first ?: showName.orEmpty(),
        artworkUri = row.obj("thumbnail")?.bestThumbnailUrl(),
        durationSeconds = durationText?.podcastDuration(),
        albumId = credit?.second ?: showId,
    )
}

/** The (name, show browse id) of the run that links a show, when the subtitle carries one. */
private fun JsonObject.podcastShowCredit(): Pair<String, String>? =
    runs().firstNotNullOfOrNull { run ->
        val id = run.obj("navigationEndpoint")?.obj("browseEndpoint")?.stringOrNull("browseId")
            ?.takeIf(::isYouTubePodcastShowId)
        val name = run.stringOrNull("text")?.takeIf(String::isNotBlank)
        if (id != null && name != null) name to id else null
    }

private fun JsonObject.episodeEntries(showName: String?, showId: String?): List<YouTubeEntry> =
    array("contents").orEmpty().mapNotNull { cell ->
        (cell as? JsonObject)?.let { row -> podcastEpisodeEntry(row, showName, showId) }
    }

private fun JsonObject.firstFlexColumnText(): String? =
    array("flexColumns").orEmpty().firstNotNullOfOrNull { column ->
        (column as? JsonObject)?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")?.wireText()
    }

/** The watch endpoint's video id, whether the endpoint sits directly here or under `anyWatchEndpoint`. */
private fun JsonObject.watchVideoId(): String? =
    obj("watchEndpoint")?.stringOrNull("videoId")
        ?: obj("anyWatchEndpoint")?.stringOrNull("videoId")
        ?: stringOrNull("videoId")

/** The next page of a shelf, or of a continuation row the shelf appended. */
private fun JsonObject.continuationToken(): String? =
    array("continuations").orEmpty().firstNotNullOfOrNull { entry ->
        val continuation = entry as? JsonObject ?: return@firstNotNullOfOrNull null
        continuation.obj("nextContinuationData")?.stringOrNull("continuation")
            ?: continuation.obj("nextRadioContinuationData")?.stringOrNull("continuation")
    } ?: obj("continuationItemRenderer")?.obj("continuationEndpoint")
        ?.obj("continuationCommand")?.stringOrNull("token")

private fun JsonObject.wireText(): String? =
    text("simpleText") ?: runs().joinToString("") { run -> run.stringOrNull("text").orEmpty() }.trim().ifBlank { null }

private fun JsonObject.runs(): List<JsonObject> = array("runs").orEmpty().mapNotNull { it as? JsonObject }

/** The largest thumbnail the renderer sent, falling back to the last one with a url. */
private fun JsonObject.bestThumbnailUrl(): String? {
    val thumbnail = obj("musicThumbnailRenderer") ?: obj("croppedSquareThumbnailRenderer") ?: return null
    val trees = thumbnail.obj("thumbnail")?.array("thumbnails") ?: thumbnail.array("thumbnails")
    val urls = trees.orEmpty().mapNotNull { it as? JsonObject }.filter { it.stringOrNull("url") != null }
    val best =
        urls.maxByOrNull { it.stringOrNull("width")?.toLongOrNull().orZero() * it.stringOrNull("height")?.toLongOrNull().orZero() }
            ?: urls.lastOrNull()
    return best?.stringOrNull("url")?.let { url -> if (url.startsWith("//")) "https:$url" else url }
}

private fun Long?.orZero(): Long = this ?: 0L

/** `1:02:03` or `2:03` in seconds; anything else is not a duration. */
private fun String.podcastDuration(): Int? {
    val parts = trim().split(':')
    if (parts.size !in 2..3) return null
    val values = parts.map { part -> part.toIntOrNull() ?: return null }
    if (values.any { it < 0 }) return null
    return if (values.size == 2) values[0] * 60 + values[1] else values[0] * 3600 + values[1] * 60 + values[2]
}
