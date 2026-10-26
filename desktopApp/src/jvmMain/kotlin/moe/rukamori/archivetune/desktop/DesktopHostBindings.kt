package moe.rukamori.archivetune.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.awt.Desktop
import java.net.URI
import java.net.URLDecoder
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import moe.rukamori.archivetune.desktop.integration.DesktopLaunchIntake
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherServers
import moe.rukamori.archivetune.shared.domain.settings.PlayerDesignStyle
import moe.rukamori.archivetune.shared.domain.settings.PlayerDesignStyleKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.toEnum
import moe.rukamori.archivetune.shared.ui.ArchiveTuneHostBindings
import moe.rukamori.archivetune.shared.ui.glass.OpticalCapabilities
import moe.rukamori.archivetune.shared.ui.lyrics.LyricsCompactStyle
import moe.rukamori.archivetune.shared.ui.lyrics.LyricsSurfaceActions
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveDestination
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoutes
import moe.rukamori.archivetune.shared.ui.player.PlayerSlots
import moe.rukamori.archivetune.shared.ui.player.playerLyricsSettings
import moe.rukamori.archivetune.shared.ui.player.playerLyricsSlots

/**
 * Blur is the one optical effect this host draws. Refraction stays off until a native owner reports it, so the
 * glass policy never promises an effect the window cannot show.
 */
internal val DesktopOpticalCapabilities = OpticalCapabilities(blur = true, refraction = false)

/**
 * The root's bindings over the one session graph. The queue, the player, the settings and the external links are real.
 * A surface whose content owner is not connected on this host takes the explicit unbound boundary, so no route shows an
 * empty list, a fake page or a success that never happened. Every such gap is reported in the integration request.
 */
internal fun desktopHostBindings(
    graph: DesktopSessionGraph,
    backRequests: MutableSharedFlow<Unit>,
    recognition: DesktopRecognitionMount,
    feedback: DesktopFeedbackMount,
    quit: () -> Unit,
): ArchiveTuneHostBindings {
    val dates = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    // One row menu per host: a row's overflow opens it, and the overlay of the page the row is on shows it.
    val menu = DesktopItemMenuState()
    // Routes this host opens itself, such as the playlist a save has just made. The root collects them with the deep links.
    val ownRoutes = Channel<String>(capacity = OWN_ROUTE_CAPACITY)
    // The create dialog saves through the one library owner, and a saved playlist opens through the root.
    val createPlaylist = DesktopPlaylistCreation(graph.scope, graph.libraryContent) { route -> ownRoutes.trySend(route) }
    // The playlist overflow menu, tag dialogs and CSV export of the Playlists and Manage tags pages.
    val playlistMenus = DesktopPlaylistMenuState(graph) { route -> ownRoutes.trySend(route) }
    // The native controls observe the same queue, player and settings owners that the library rows use.
    val sleepSheet = DesktopSleepTimerSheetState()
    val playerMenu = DesktopPlayerMenuState()
    val controls = DesktopPlayerControls(graph, sleepSheet, playerMenu)
    // One settings controller and sign-in lifecycle for every settings page of this window.
    val settingsOwners = DesktopSettingsOwners(graph)
    return ArchiveTuneHostBindings(
        queue = graph.queue,
        settings = graph.settings,
        actionScope = graph.scope,
        // Deep links from the desktop intake open their page through the root. Back arrives from the window.
        routes = merge(desktopLaunchRoutes(graph), ownRoutes.receiveAsFlow()),
        backRequests = backRequests,
        // Library, playlists, the local or liked playlist routes, search, YouTube albums and finite playlists, music
        // recognition and Listen Together read their owners. Every other destination keeps the boundary.
        page = { route ->
            desktopGraphPage(graph, menu, createPlaylist, recognition, playlistMenus, route) { target -> ownRoutes.trySend(target) }
        },
        // Item and news images load over the graph's one client, public http or https only; a failed image keeps its frame.
        artwork = { item, modifier -> DesktopRemoteArtwork(item.artworkUri, graph.artworkLoader, modifier) },
        newsImage = { uri, modifier -> DesktopRemoteArtwork(uri, graph.artworkLoader, modifier) },
        formatNewsDate = { epochMs -> dates.format(Date(epochMs)) },
        librarySourceSelector = { },
        hostScreen = { route, navigation ->
            if (desktopMountsSettings(route)) {
                DesktopSettingsScreen(settingsOwners, graph, route, navigation)
            } else {
                DesktopUnboundSurface(Modifier.fillMaxSize())
            }
        },
        overlays = { route ->
            DesktopItemMenuDialog(menu, graph, route.value, sleepSheet) { target -> ownRoutes.trySend(target) }
            DesktopCreatePlaylistOverlay(createPlaylist, route)
            DesktopPlaylistMenuOverlay(playlistMenus, graph, route.value)
            DesktopPlayerMenuDialog(playerMenu, sleepSheet, graph) { target -> ownRoutes.trySend(target) }
            DesktopSleepTimerOverlay(sleepSheet, graph.sleepTimer)
            DesktopFeedbackOverlay(feedback, graph)
            DesktopBackupRestartDialog(graph, quit)
        },
        playerSlots = { expanded, active, _ -> desktopPlayerSlots(graph, expanded, active) },
        // The engine exposes no volume, so the native state keeps volume null and the volume row stays hidden.
        playerControls = controls,
        // The row menu queues the chosen row through the one queue. Other song rows need owners this host does not connect.
        more = { occurrence -> menu.open(occurrence) },
        // The row's list is read whole from its owner in owner order, then the queue receives that list as one replacement.
        playOccurrence = { collection, start -> desktopPlayOccurrence(graph, collection, start) },
        openExternal = { uri -> openDesktopUri(uri, graph::report) },
        onFailure = { graph.report("a host request could not be completed") },
        // The desktop root has no screen beneath it, so back at the root has nothing to pop.
        onBackAtRoot = { },
    )
}

/** Deep links the intake accepted but the root has not collected yet. The bound keeps a flood of links from growing memory. */
private const val LAUNCH_LINK_CAPACITY = 16

/** Routes this host opens itself. The bound keeps a burst of saves from growing memory, and the root drains them promptly. */
private const val OWN_ROUTE_CAPACITY = 4

/**
 * The one listener on the desktop link intake for this process. The intake buffers a link that arrives before the first
 * window composes, and this channel keeps each accepted link until the root collects it, so a launch argument is not lost.
 */
private val launchLinks: Channel<String> by lazy {
    Channel<String>(capacity = LAUNCH_LINK_CAPACITY).also { channel ->
        DesktopLaunchIntake.links.attach { link -> channel.trySend(link) }
    }
}

/**
 * The route strings the shared root opens. A link that opens no page is reported as one line and dropped here, so the
 * root never receives an unresolved route or an action link as a route.
 */
private fun desktopLaunchRoutes(graph: DesktopSessionGraph): Flow<String> =
    launchLinks.receiveAsFlow().mapNotNull { link ->
        val listenCode = desktopListenTogetherCode(link)
        if (listenCode != null) {
            graph.listenTogether.joinFromLink(listenCode)
            return@mapNotNull null
        }
        desktopLaunchRoute(link) ?: run {
            graph.report("link not opened: it names no page that a link may open")
            null
        }
    }

/**
 * The room code an `archivetune://listen` invite carries, or null when the link is not a listen invite. Mirrors the
 * Android handler exactly: `?code=`, then `?room=`, then the second path segment (pathSegments[1] — on the https invite
 * hosts the first segment is "listen"), accepted only when the link names a listen endpoint.
 */
internal fun desktopListenTogetherCode(link: String): String? {
    val uri = try {
        URI(link)
    } catch (_: Exception) {
        return null
    }
    val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
    val isListenLink = segments.firstOrNull() == "listen" ||
        uri.host?.equals("listen", ignoreCase = true) == true ||
        ListenTogetherServers.isInviteHost(uri.host)
    if (!isListenLink) return null
    val query = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.mapNotNull {
        val parts = it.split('=', limit = 2)
        try {
            URLDecoder.decode(parts[0], Charsets.UTF_8) to
                URLDecoder.decode(parts.getOrElse(1) { "" }, Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            null
        }
    }.toMap()
    return (query["code"] ?: query["room"] ?: segments.getOrNull(1))?.takeIf { it.isNotBlank() }
}

/**
 * The route an `archivetune://open/...` link opens, or null when it opens none. The path and query are passed on as they
 * arrived, and [ArchiveRoutes] decides the destination. A link starts no action: a recognition auto-start request and the
 * sign-in page are refused, so another program cannot open the microphone or start a sign-in.
 */
internal fun desktopLaunchRoute(link: String): String? {
    val uri = try {
        URI(link)
    } catch (_: Exception) {
        return null
    }
    val route = uri.rawPath.orEmpty().removePrefix("/") + uri.rawQuery?.let { "?$it" }.orEmpty()
    val resolved = ArchiveRoutes.resolve(route) ?: return null
    val opensAction = resolved.destination == ArchiveDestination.LOGIN || resolved.arguments.containsKey("autoStart")
    return route.takeUnless { opensAction }
}

/**
 * Only web links leave the app. Every other scheme, a missing browser and a failed launch are reported as one line and
 * never handed to the operating system. Returns false when the link was not opened.
 */
internal fun openDesktopUri(uri: String, report: (String) -> Unit): Boolean =
    try {
        val target = URI(uri)
        val scheme = target.scheme?.lowercase()
        require(scheme == "https" || scheme == "http") { "unsupported link scheme" }
        check(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            "no browser is registered"
        }
        Desktop.getDesktop().browse(target)
        true
    } catch (failure: Exception) {
        report("link not opened: ${failure.javaClass.simpleName}")
        false
    }

/**
 * The player's media slots. Lyrics come from the graph's lyrics owner and render through the shared renderers on the
 * player's own snapshot, so a tap seeks the one player. Readings come from the graph's lyrics surface runtime, which
 * publishes the built-in/provider/AI presentation for the document on screen.
 */
@Composable
private fun desktopPlayerSlots(graph: DesktopSessionGraph, expanded: Boolean, active: Boolean): PlayerSlots {
    val document by graph.lyrics.current.collectAsState()
    val saved by graph.settings.snapshots.collectAsState()
    val preferences = saved ?: PreferenceSnapshot.EMPTY
    val sessionKey = graph.lyricsSurface.sessionKey(document)
    val romanization = graph.lyricsSurface.romanization
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
        artwork = { occurrenceId, modifier -> DesktopPlayerArtwork(occurrenceId, graph, expanded && active, modifier) },
        // No canvas loop source is connected. The artwork slot keeps the cover, as it does for a song without a canvas.
        canvas = { _, _, _ -> },
        customBackground = { _, modifier -> DesktopArtworkPlaceholder(modifier) },
        lyrics = lyrics.full,
        compactLyrics = lyrics.compact,
        queue = { modifier -> DesktopQueuePanel(graph, modifier) },
    )
}

/**
 * The frame a slot keeps while its image is absent, loading or unreadable. It draws no image it has not decoded. A
 * remote image is drawn by DesktopRemoteArtwork over this frame, and a custom background stays on it.
 */
@Composable
internal fun DesktopArtworkPlaceholder(modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {}
}

/** The explicit boundary for a surface whose owner is not connected here. It names the gap instead of an empty result. */
@Composable
private fun DesktopUnboundSurface(modifier: Modifier) {
    Box(modifier.padding(16.dp), contentAlignment = Alignment.Center) {
        Text(DesktopStrings["host_surface_unbound"], textAlign = TextAlign.Center)
    }
}
