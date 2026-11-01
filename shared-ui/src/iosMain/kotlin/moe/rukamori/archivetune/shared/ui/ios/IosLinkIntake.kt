package moe.rukamori.archivetune.shared.ui.ios

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.ui.IosHost
import moe.rukamori.archivetune.shared.ui.navigation.ExternalLink
import moe.rukamori.archivetune.shared.ui.navigation.ExternalLinks

/**
 * The links the host receives — `archivetune://` opens and every share or universal link SwiftUI hands `onOpenURL` —
 * classified by [ExternalLinks] and resolved into the route strings the root opens. An invite joins its room and a
 * watch link plays, as the Android host handles them; a link that names no openable page is reported once and dropped.
 */
internal fun iosLaunchRoutes(host: IosHost, graph: IosSessionGraph): Flow<String> =
    host.linkRequests.mapNotNull { link ->
        when (val target = ExternalLinks.classify(link)) {
            is ExternalLink.ListenTogetherInvite -> {
                graph.listenTogether.joinFromLink(target.code)
                null
            }
            is ExternalLink.YouTubeWatch -> {
                iosPlayWatchLink(graph, target)
                null
            }
            is ExternalLink.Route -> ExternalLinks.openableRoute(target.route) ?: run {
                graph.report("link not opened: it names no page that a link may open")
                null
            }
            is ExternalLink.Login, null -> run {
                graph.report("link not opened: it names no page that a link may open")
                null
            }
        }
    }

/**
 * The route an `archivetune://open/...` link opens, or null when it opens none. The shared classifier decides, and the
 * refusal every host keeps stays: a link starts no sign-in and no action.
 */
internal fun iosLaunchRoute(link: String): String? =
    (ExternalLinks.classify(link) as? ExternalLink.Route)?.route?.let(ExternalLinks::openableRoute)

/**
 * A watch link plays the video it names the way the Android host plays it: the queue becomes the song, identified by
 * its YouTube video id until the resolver supplies the rest. A playlist id on the link is the queue context the
 * Android host reads while it queues, not a second navigation, and the shuffle request is for playlist playback, so
 * neither is applied to this single-song queue.
 */
private fun iosPlayWatchLink(graph: IosSessionGraph, target: ExternalLink.YouTubeWatch) {
    val occurrence = ContentOccurrence(
        occurrenceId = "link-watch:${target.videoId}",
        item = ContentItem(ContentRef("youtube", ContentKind.SONG, target.videoId), target.videoId),
    )
    graph.scope.launch {
        try {
            graph.queue.replace(listOf(occurrence), occurrence.occurrenceId, playWhenReady = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            graph.report("link not opened: ${failure::class.simpleName}")
        }
    }
}
