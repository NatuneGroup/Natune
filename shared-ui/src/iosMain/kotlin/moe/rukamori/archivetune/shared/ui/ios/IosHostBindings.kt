package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.data.platform.ios.iosDiagnosticTime
import moe.rukamori.archivetune.shared.data.platform.ios.iosNowMillis
import moe.rukamori.archivetune.shared.domain.settings.PlayerDesignStyle
import moe.rukamori.archivetune.shared.domain.settings.PlayerDesignStyleKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.toEnum
import moe.rukamori.archivetune.shared.ui.ArchiveTuneHostBindings
import moe.rukamori.archivetune.shared.ui.IosHost
import moe.rukamori.archivetune.shared.ui.glass.OpticalCapabilities
import moe.rukamori.archivetune.shared.ui.host.hostHistoryPage
import moe.rukamori.archivetune.shared.ui.host.hostLibraryPage
import moe.rukamori.archivetune.shared.ui.host.hostPlayOccurrence
import moe.rukamori.archivetune.shared.ui.host.hostPlaylistPage
import moe.rukamori.archivetune.shared.ui.lyrics.LyricsCompactStyle
import moe.rukamori.archivetune.shared.ui.lyrics.LyricsRomanizationPresentation
import moe.rukamori.archivetune.shared.ui.lyrics.LyricsRomanizationState
import moe.rukamori.archivetune.shared.ui.lyrics.LyricsSurfaceActions
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveDestination
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphPage
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoute
import moe.rukamori.archivetune.shared.ui.player.PlayerSlots
import moe.rukamori.archivetune.shared.ui.player.playerLyricsSettings
import moe.rukamori.archivetune.shared.ui.player.playerLyricsSlots
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.resources.ios_host_surface_unbound
import moe.rukamori.archivetune.shared.ui.resources.ios_library_top_mixes_unavailable
import moe.rukamori.archivetune.shared.ui.resources.ios_queue_empty
import moe.rukamori.archivetune.shared.ui.resources.ios_queue_remove
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIPasteboard

/**
 * Blur is the one optical effect this host draws. Refraction stays off until a native owner reports it, so the glass
 * policy never promises an effect the screen cannot show.
 */
internal val IosOpticalCapabilities = OpticalCapabilities(blur = true, refraction = false)

/**
 * The root's bindings over the one iOS session graph. The queue, the player, the settings, the library pages and the
 * external links are real. A surface whose owner is not connected on this host takes the explicit unbound boundary, so
 * no route shows an empty list, a fake page or a success that never happened.
 */
internal fun iosHostBindings(host: IosHost, graph: IosSessionGraph): ArchiveTuneHostBindings {
    val controls = IosPlayerControls(graph)
    val settingsOwners = IosSettingsOwners(graph)
    // One row menu per host: a row's overflow opens it, and the overlay of the page the row is on shows it.
    val menu = IosSongMenuState()
    // Routes this host opens itself, such as a Library shortcut card. The root collects them as it collects links.
    val ownRoutes = Channel<String>(capacity = OWN_ROUTE_CAPACITY)
    return ArchiveTuneHostBindings(
        queue = graph.queue,
        settings = graph.settings,
        actionScope = graph.scope,
        // This host accepts no deep links yet, and the system back gesture is handled by the shell itself.
        routes = ownRoutes.receiveAsFlow(),
        backRequests = emptyFlow(),
        page = { route -> iosGraphPage(graph, menu, route) { target -> ownRoutes.trySend(target) } },
        artwork = { item, modifier -> IosRemoteArtwork(item.artworkUri, graph.artworkLoader, modifier) },
        newsImage = { uri, modifier -> IosRemoteArtwork(uri, graph.artworkLoader, modifier) },
        formatNewsDate = ::iosDiagnosticTime,
        librarySourceSelector = { },
        hostScreen = { route, navigation ->
            if (iosMountsSettings(route)) {
                IosSettingsScreen(host, settingsOwners, route, navigation)
            } else {
                IosUnboundSurface(Modifier.fillMaxSize())
            }
        },
        overlays = { route -> IosSongMenuOverlay(menu, graph, route.value) },
        playerSlots = { expanded, active, _ -> iosPlayerSlots(graph, expanded, active) },
        playerControls = controls,
        // The shared song menu runs against the queue, library, history and clipboard owners of this host.
        more = { occurrence -> menu.open(occurrence) },
        playOccurrence = { collection, start -> hostPlayOccurrence(graph.pageOwners, collection, start) },
        openExternal = { uri -> openIosUri(uri, graph::report) },
        onFailure = { graph.report("a host request could not be completed") },
        // The iOS root has no screen beneath it, so back at the root has nothing to pop.
        onBackAtRoot = { },
    )
}

/**
 * The Library, the local history and the playlist routes the library owner answers. Every other destination keeps the host boundary. The
 * key keeps each route's own page state apart.
 */
@Composable
private fun iosGraphPage(
    graph: IosSessionGraph,
    menu: IosSongMenuState,
    route: ArchiveRoute,
    openRoute: (String) -> Unit,
): ArchiveGraphPage =
    key(route.value) {
        when (route.destination) {
            ArchiveDestination.LIBRARY ->
                hostLibraryPage(graph.pageOwners, stringResource(Res.string.ios_library_top_mixes_unavailable), openRoute)
            ArchiveDestination.PLAYLIST ->
                hostPlaylistPage(graph.pageOwners, route) { occurrence ->
                    // Only an editable local playlist offers the row's remove-from-playlist action.
                    menu.open(
                        occurrence,
                        playlistId = route.arguments["playlistId"].takeIf {
                            route.arguments["playlistType"] == "local_playlist"
                        },
                    )
                }
            ArchiveDestination.HISTORY -> hostHistoryPage(
                owners = graph.pageOwners,
                offsetMsAt = ::iosOffsetMsAt,
                nowMs = ::iosNowMillis,
                formatTime = ::iosDiagnosticTime,
                more = { occurrence -> menu.open(occurrence) },
            )
            else -> ArchiveGraphPage.Host
        }
    }

/**
 * The player's media slots. Lyrics come from the graph's lyrics owner and render through the shared renderers on the
 * player's own snapshot, so a tap seeks the one player. Romanization is off on this host until the Japanese pack is
 * connected to the lyrics surface.
 */
@Composable
private fun iosPlayerSlots(graph: IosSessionGraph, expanded: Boolean, active: Boolean): PlayerSlots {
    val document by graph.lyrics.current.collectAsState()
    val saved by graph.settings.snapshots.collectAsState()
    val preferences = saved ?: PreferenceSnapshot.EMPTY
    val sessionKey = document?.occurrenceId ?: NO_LYRICS_SESSION
    val romanization = remember(sessionKey) { LyricsRomanizationState(LyricsRomanizationPresentation.off(sessionKey)) }
    val actions = remember(graph) { LyricsSurfaceActions(onSeek = { graph.player.seekTo(it) }, onShareCard = { }) }
    val style = preferences[PlayerDesignStyleKey].toEnum(PlayerDesignStyle.Default)
    val lyrics = playerLyricsSlots(
        lines = document?.lines.orEmpty(),
        sessionKey = sessionKey,
        playback = graph.player.snapshot,
        romanization = romanization,
        settings = preferences.playerLyricsSettings(syncOffsetMs = 0L, reducedMotion = false),
        actions = actions,
        compactStyle = if (style == PlayerDesignStyle.BITCHORD) LyricsCompactStyle.BITCHORD else LyricsCompactStyle.SIMPMUSIC,
        fullVisible = expanded && active,
        compactVisible = expanded && active,
        resumed = active,
    )
    return PlayerSlots(
        artwork = { occurrenceId, modifier -> IosQueueArtwork(occurrenceId, graph, modifier) },
        // No canvas loop source is connected. The artwork slot keeps the cover, as it does for a song without a canvas.
        canvas = { _, _, _ -> },
        customBackground = { _, modifier -> IosArtworkPlaceholder(modifier) },
        lyrics = lyrics.full,
        compactLyrics = lyrics.compact,
        queue = { modifier -> IosQueuePanel(graph, modifier) },
    )
}

private const val NO_LYRICS_SESSION = "ios-no-lyrics"

/** Routes this host opens itself. The bound keeps a burst of taps from growing memory, and the root drains them promptly. */
private const val OWN_ROUTE_CAPACITY = 4

/**
 * The player's queue panel: the one queue in play order, the current song marked. A row plays its song, the current
 * row pauses or resumes, and a row can be removed. Every command runs through the queue owner.
 */
@Composable
private fun IosQueuePanel(graph: IosSessionGraph, modifier: Modifier) {
    val queue by graph.queue.state.collectAsState()
    val rows = remember(queue.entries, queue.playOrder) {
        val byId = queue.entries.associateBy { it.occurrenceId }
        queue.playOrder.mapNotNull { byId[it] }
    }
    if (rows.isEmpty()) {
        Box(modifier.padding(16.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(Res.string.ios_queue_empty), style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    val removeLabel = stringResource(Res.string.ios_queue_remove)
    val colors = MaterialTheme.colorScheme
    LazyColumn(modifier) {
        items(rows, key = { it.occurrenceId }) { entry ->
            val current = entry.occurrenceId == queue.currentOccurrenceId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (current) colors.secondaryContainer else Color.Transparent)
                    .clickable {
                        if (current) {
                            if (graph.player.snapshot.value?.playWhenReady == true) graph.player.pause() else graph.player.play()
                        } else {
                            queueCommand(graph, "the song was not played") { graph.queue.select(entry.occurrenceId, 0L) }
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IosRemoteArtwork(entry.item.artworkUri, graph.artworkLoader, Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        entry.item.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                        color = if (current) colors.onSecondaryContainer else colors.onSurface,
                    )
                    val artists = entry.item.artists.joinToString(", ") { it.name }.ifEmpty { entry.item.subtitle }
                    if (artists.isNotEmpty()) {
                        Text(
                            artists,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
                // The shared icon set is not part of this panel, so the action draws a plain glyph and names itself.
                IconButton(
                    onClick = { queueCommand(graph, "the song was not removed") { graph.queue.remove(setOf(entry.occurrenceId)) } },
                    modifier = Modifier.semantics { contentDescription = removeLabel },
                ) {
                    Text("\u2715", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

private fun queueCommand(graph: IosSessionGraph, refusal: String, command: suspend () -> Unit) {
    graph.scope.launch {
        try {
            command()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            graph.report("queue panel: $refusal: ${failure::class.simpleName}")
        }
    }
}

/** The explicit boundary for a surface whose owner is not connected here. It names the gap instead of an empty result. */
@Composable
internal fun IosUnboundSurface(modifier: Modifier) {
    Box(modifier.padding(16.dp), contentAlignment = Alignment.Center) {
        Text(stringResource(Res.string.ios_host_surface_unbound), textAlign = TextAlign.Center)
    }
}

/** Only web links leave the app. Every other scheme and a failed open are reported as one line. */
internal fun openIosUri(uri: String, report: (String) -> Unit) {
    val scheme = uri.substringBefore(':', missingDelimiterValue = "").lowercase()
    val url = NSURL.URLWithString(uri)
    if ((scheme != "https" && scheme != "http") || url == null) {
        report("link not opened: unsupported link scheme")
        return
    }
    UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>()) { opened ->
        if (!opened) report("link not opened: the system refused it")
    }
}

internal fun copyToIosClipboard(text: String) {
    UIPasteboard.generalPasteboard.string = text
}
