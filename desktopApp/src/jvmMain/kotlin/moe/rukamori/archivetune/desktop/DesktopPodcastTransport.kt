package moe.rukamori.archivetune.desktop

import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.EpisodeItem
import moe.rukamori.archivetune.innertube.models.PodcastItem
import moe.rukamori.archivetune.shared.domain.repository.ContentCredit
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.ui.screens.PodcastEpisodeUi

internal data class DesktopPodcastBrowsePage(val shows: List<ContentItem>, val continuation: String? = null)
internal data class DesktopPodcastEpisodePage(val episodes: List<PodcastEpisodeUi>, val continuation: String? = null)
internal data class DesktopPodcastDetail(
    val header: ContentItem,
    val description: String?,
    val page: DesktopPodcastEpisodePage,
)

internal interface DesktopPodcastTransport {
    suspend fun browse(query: String, continuation: String?): DesktopPodcastBrowsePage
    suspend fun detail(browseId: String): DesktopPodcastDetail
    suspend fun episodes(header: ContentItem, continuation: String): DesktopPodcastEpisodePage
}

/** Uses the retained browse session; podcast audio still resolves through the ordinary desktop queue. */
internal class CoreDesktopPodcastTransport : DesktopPodcastTransport {
    override suspend fun browse(query: String, continuation: String?): DesktopPodcastBrowsePage {
        if (query.isNotBlank()) {
            require(continuation == null)
            val page = YouTube.searchSummary(query.trim()).getOrThrow()
            return DesktopPodcastBrowsePage(page.summaries.flatMap { it.items }.filterIsInstance<PodcastItem>().showItems())
        }
        val page = YouTube.home(continuation).getOrThrow()
        return DesktopPodcastBrowsePage(
            page.sections.flatMap { it.items }.filterIsInstance<PodcastItem>().showItems(),
            page.continuation,
        )
    }

    override suspend fun detail(browseId: String): DesktopPodcastDetail {
        val page = YouTube.podcast(browseId).getOrThrow()
        val header = page.podcast.desktopPodcastItem()
        return DesktopPodcastDetail(header, page.description, page.episodes.episodePage(header, page.continuation))
    }

    override suspend fun episodes(header: ContentItem, continuation: String): DesktopPodcastEpisodePage {
        val page = YouTube.podcastContinuation(continuation).getOrThrow()
        return page.episodes.episodePage(header, page.continuation)
    }
}

private fun List<PodcastItem>.showItems(): List<ContentItem> =
    filter { it.browseId.isNotBlank() && it.title.isNotBlank() }.distinctBy { it.browseId }.map { it.desktopPodcastItem() }

internal fun PodcastItem.desktopPodcastItem(): ContentItem = ContentItem(
    ref = ContentRef(ContentProvider.YOUTUBE.id, ContentKind.PODCAST, browseId),
    title = title,
    subtitle = author?.name.orEmpty(),
    artworkUri = thumbnail,
)

private fun List<EpisodeItem>.episodePage(header: ContentItem, continuation: String?) = DesktopPodcastEpisodePage(
    mapNotNull { it.desktopPodcastEpisode(header) }.distinctBy { it.row.item.ref },
    continuation,
)

internal fun EpisodeItem.desktopPodcastEpisode(header: ContentItem): PodcastEpisodeUi? {
    if (id.isBlank() || title.isBlank() || endpoint.videoId != id) return null
    val showName = podcast?.name?.takeIf(String::isNotBlank) ?: header.title
    val showId = podcast?.id?.takeIf(String::isNotBlank) ?: header.ref.id
    return PodcastEpisodeUi(
        row = ContentOccurrence(
            occurrenceId = "desktop-podcast:${header.ref.id}:episode:$id",
            item = ContentItem(
                ref = ContentRef(ContentProvider.YOUTUBE.id, ContentKind.EPISODE, id),
                title = title,
                subtitle = showName,
                artworkUri = thumbnail,
                durationMs = duration?.takeIf { it >= 0 }?.let { it * 1000L },
                album = header.title,
                albumId = header.ref.id,
                artists = listOf(ContentCredit(showName, showId)),
            ),
        ),
        dateText = dateText,
        durationText = durationText,
        description = description,
    )
}
