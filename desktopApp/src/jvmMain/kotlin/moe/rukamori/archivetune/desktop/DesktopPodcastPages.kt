package moe.rukamori.archivetune.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphPage

@Composable
internal fun desktopPodcastsPage(owner: DesktopPodcastsOwner): ArchiveGraphPage {
    LaunchedEffect(owner) { owner.ensureLoaded() }
    return ArchiveGraphPage.Podcasts(
        state = owner.browseUi(DesktopStrings["content_podcast_subscriptions_local"]),
        query = owner::query,
        subscriptionsOnly = owner::subscriptionsOnly,
        subscribe = owner::subscribe,
        retry = owner::reload,
        loadMore = owner::loadMore,
    )
}

@Composable
internal fun desktopPodcastPage(owner: DesktopPodcastsOwner, browseId: String): ArchiveGraphPage {
    val detail = owner.detail(browseId)
    val ref = detail.header?.ref ?: ContentRef(ContentProvider.YOUTUBE.id, ContentKind.PODCAST, browseId)
    LaunchedEffect(owner, detail) { owner.ensureLoaded(); detail.ensureLoaded() }
    return ArchiveGraphPage.Podcast(
        state = detail.ui(owner.subscriptionState(ref), DesktopStrings["content_podcast_subscriptions_local"]),
        subscribe = { subscribed -> owner.subscribe(ref, subscribed) },
        retry = detail::reload,
        loadMore = detail::loadMore,
    )
}
