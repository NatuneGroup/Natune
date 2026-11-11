package moe.rukamori.archivetune.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.awt.Desktop
import java.net.URI
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.desktop.integration.DesktopLaunchIntake
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.domain.lyrics.effectiveLyricsSyncOffsetMs
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.settings.DisableAnimationsKey
import moe.rukamori.archivetune.shared.domain.settings.GlobalLyricsOffsetMsKey
import moe.rukamori.archivetune.shared.domain.settings.LyricsModeKey
import moe.rukamori.archivetune.shared.domain.settings.PlayerDesignStyle
import moe.rukamori.archivetune.shared.domain.settings.PlayerDesignStyleKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.toEnum
import moe.rukamori.archivetune.shared.ui.ArchiveTuneHostBindings
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoutes
import moe.rukamori.archivetune.shared.ui.acoustic.AcousticQualityOverlay
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.playlist.PlaylistPickerCandidate
import moe.rukamori.archivetune.shared.ui.menu.PlaylistPickerSource
import moe.rukamori.archivetune.shared.ui.glass.OpticalCapabilities
import moe.rukamori.archivetune.shared.ui.lyrics.LyricsCompactStyle
import moe.rukamori.archivetune.shared.ui.lyrics.LyricsMode
import moe.rukamori.archivetune.shared.ui.lyrics.LyricsSurfaceActions
import moe.rukamori.archivetune.shared.ui.lyrics.LyricsSyncControls
import moe.rukamori.archivetune.shared.ui.navigation.ExternalLink
import moe.rukamori.archivetune.shared.ui.desktop.DesktopLyricsPane
import moe.rukamori.archivetune.shared.ui.host.activeLyricsOccurrence
import moe.rukamori.archivetune.shared.ui.host.belongingTo
import moe.rukamori.archivetune.shared.ui.navigation.ExternalLinks
import moe.rukamori.archivetune.shared.ui.player.PlayerSlots
import moe.rukamori.archivetune.shared.ui.player.capabilities
import moe.rukamori.archivetune.shared.ui.player.playerLyricsSettings
import moe.rukamori.archivetune.shared.ui.player.playerLyricsSlots

/**
 * Blur is the one optical effect this host draws, and only while a GPU renderer is drawing: software rasterisation
 * reports false here instead of paying a per-frame CPU blur. Refraction stays off until a native owner reports it, so
 * the glass policy never promises an effect the window cannot show.
 */
internal val DesktopOpticalCapabilities: OpticalCapabilities get() = DesktopRendererDiagnostics.capabilities

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
    overlayOwners: DesktopOverlayOwners,
    quit: () -> Unit,
    /** The F key's window fullscreen toggle; the root binds it where a window state exists. */
    toggleFullscreen: () -> Unit = {},
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
    // One settings controller and sign-in lifecycle for every settings page of this window.
    val settingsOwners = DesktopSettingsOwners(graph)
    // The native controls observe the same queue, player and settings owners that the library rows use, and the
    // typeface the settings page installs, so a SpatialFlow title draws in that typeface once it is installed.
    val sleepSheet = DesktopSleepTimerSheetState()
    val playerMenu = DesktopPlayerMenuState()
    // The player's output control opens the same live device/exclusive selection the playback settings page owns.
    val outputSheet = DesktopOutputSheetState()
    val controls = DesktopPlayerControls(graph, sleepSheet, playerMenu, outputSheet,
        settingsOwners.services.spatialFlowFontFamily,
        onTitle = { row -> row.item.albumRef()?.let { ownRoutes.trySend(ArchiveRoutes.content(it)) } ?: menu.open(row) },
        onArtists = { row -> menu.openArtists(row.item) })
    val podcasts = createDesktopPodcasts(graph)
    val topMixes = createDesktopTopMixes(graph)
    val canvas = createDesktopCanvas(graph, settingsOwners.spotify)
    val canvasFrames = DesktopCanvasFrames(graph, canvas)
    val lyricsTools = DesktopLyricsTools(graph)
    // Each navigation entry disposes its overlays, so the root closes these owners once, not an overlay.
    overlayOwners.own(podcasts::close, topMixes::close, canvas::close, lyricsTools::close)
    return ArchiveTuneHostBindings(
        queue = DesktopPodcastQueue(graph.queue, podcasts),
        songMetadata = graph.library.songMetadata,
        customArtwork = graph.customArtwork,
        // The multi-playlist add picker reads the one library repository's candidates.
        playlistPicker = object : PlaylistPickerSource {
            override fun candidates(occurrences: List<ContentOccurrence>): Flow<List<PlaylistPickerCandidate>> =
                graph.library.observePlaylistPicker(occurrences)
        },
        // The restart-scoped custom font resolves from the file the picker stored by file URI.
        customFontLoader = { uri -> desktopCustomFontFamily(uri) },
        settings = graph.settings,
        savedThemeScheme = { preferences, dark ->
            if (preferences[moe.rukamori.archivetune.shared.domain.settings.DynamicThemeKey, true]) null
            else preferences[moe.rukamori.archivetune.shared.domain.settings.CustomThemeColorKey]
                ?.takeIf { it.length <= 64 * 1024 }
                ?.let(moe.rukamori.archivetune.ui.theme.ThemeSeedPaletteCodec::decodeFromPreference)
                ?.let { moe.rukamori.archivetune.ui.theme.desktopSeedColorScheme(it, dark) }
        },
        actionScope = graph.scope,
        // Deep links from the desktop intake open their page through the root. Back arrives from the window.
        routes = merge(desktopLaunchRoutes(graph), ownRoutes.receiveAsFlow()),
        backRequests = backRequests,
        // Library, playlists, the local or liked playlist routes, search, YouTube albums and finite playlists, music
        // recognition and Listen Together read their owners. Every other destination keeps the boundary.
        page = { route ->
            desktopGraphPage(graph, menu, createPlaylist, recognition, playlistMenus, settingsOwners.spotify, podcasts, topMixes, route, back = { backRequests.tryEmit(Unit) }) { target -> ownRoutes.trySend(target) }
        },
        // Item and news images load over the graph's one client, public http or https only; a failed image keeps its frame.
        artwork = { item, modifier -> DesktopRemoteArtwork(item.artworkUri, graph.artworkLoader, modifier) },
        // The window's account avatar reads the same picture the account settings page stores, through that client.
        accountAvatar = { uri, modifier -> DesktopRemoteArtwork(uri, graph.artworkLoader, modifier) },
        newsImage = { uri, modifier -> DesktopRemoteArtwork(uri, graph.artworkLoader, modifier) },
        // Feed dates are epoch seconds, the unit the Android host stores and formats.
        formatNewsDate = { epochSeconds -> dates.format(Date(epochSeconds * 1_000L)) },
        newsAvailable = graph.newsFeed.enabled,
        unreadNews = graph.newsFeed.unread,
        librarySourceSelector = { DesktopLibrarySourceSelector(graph, settingsOwners.spotify) },
        hostScreen = { route, navigation ->
            if (desktopMountsSettings(route)) {
                DesktopSettingsScreen(settingsOwners, graph, route, navigation)
            } else {
                DesktopUnboundSurface(Modifier.fillMaxSize())
            }
        },
        overlays = { route ->
            DesktopItemMenuDialog(menu, graph, route.value, sleepSheet, canvas) { target -> ownRoutes.trySend(target) }
            DesktopCreatePlaylistOverlay(createPlaylist, route)
            DesktopPlaylistMenuOverlay(playlistMenus, graph, route.value)
            DesktopPlayerMenuDialog(playerMenu, sleepSheet, graph, canvas) { target -> ownRoutes.trySend(target) }
            DesktopSleepTimerOverlay(sleepSheet, graph.sleepTimer)
            DesktopEqualizerOverlay(graph.equalizer)
            DesktopOutputSheet(outputSheet, graph)
            AcousticQualityOverlay(graph.acoustic)
            DesktopFeedbackOverlay(feedback, graph)
            DesktopBackupRestartDialog(graph, quit)
            DesktopLyricsShareNotice(lyricsTools)
            // Device Connect spans playback, queue handoff and remote control on this window. Mounting it
            // once here means an incoming pairing/hand-off prompt or the Connected devices sheet can show
            // over any page, and its settings row stays reachable through the settings tree.
            DesktopDeviceConnectHost(graph) { ownRoutes.trySend("settings/integrations/device_connect") }
        },
        playerSlots = { expanded, active, _ ->
            desktopPlayerSlots(graph, canvas, canvasFrames, lyricsTools, outputSheet, expanded, active)
        },
        // Native volume and output state come from the same engine as playback.
        playerControls = DesktopCanvasControls(controls, canvas, graph.scope),
        // The row menu queues the chosen row through the one queue. Other song rows need owners this host does not connect.
        more = { occurrence -> menu.open(occurrence) },
        // The row's list is read whole from its owner in owner order, then the queue receives that list as one replacement.
        playOccurrence = { collection, start -> desktopPlayOccurrence(graph, settingsOwners.spotify, collection, start) },
        openExternal = { uri -> openDesktopUri(uri, graph::report) },
        onFailure = { graph.report("a host request could not be completed") },
        // The desktop root has no screen beneath it, so back at the root has nothing to pop.
        onBackAtRoot = { },
        podcastProgress = graph.podcastProgress.observeAll(),
        toggleWindowFullscreen = toggleFullscreen,
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
 * The route strings the shared root opens. The shared [ExternalLinks] table classifies each link the way the Android
 * intent handler does: an invite joins its room and a watch link plays, a page link opens its route, and a link that
 * opens nothing is reported as one line and dropped here, so the root never receives an unresolved route or an action
 * link as a route.
 */
private fun desktopLaunchRoutes(graph: DesktopSessionGraph): Flow<String> =
    launchLinks.receiveAsFlow().mapNotNull { link ->
        when (val target = ExternalLinks.classify(link)) {
            is ExternalLink.ListenTogetherInvite -> {
                graph.listenTogether.joinFromLink(target.code)
                null
            }
            is ExternalLink.YouTubeWatch -> {
                desktopPlayWatchLink(graph, target)
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
 * A watch link plays the video it names the way the Android host plays it: the queue becomes the song, identified by
 * its YouTube video id until the resolver supplies the rest.
 */
private fun desktopPlayWatchLink(graph: DesktopSessionGraph, target: ExternalLink.YouTubeWatch) {
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

/**
 * The room code an `archivetune://listen` invite carries, or null when the link is not a listen invite — the shared
 * [ExternalLinks] classifier, which mirrors the Android handler: `?code=`, then `?room=`, then the second path
 * segment, accepted only when the link names a listen endpoint.
 */
internal fun desktopListenTogetherCode(link: String): String? =
    (ExternalLinks.classify(link) as? ExternalLink.ListenTogetherInvite)?.code

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
private fun desktopPlayerSlots(
    graph: DesktopSessionGraph, canvas: DesktopCanvasOwner, canvasFrames: DesktopCanvasFrames,
    tools: DesktopLyricsTools, outputSheet: DesktopOutputSheetState, expanded: Boolean, active: Boolean,
): PlayerSlots {
    val published by graph.lyrics.current.collectAsState()
    val playback by graph.player.snapshot.collectAsState()
    val queue by graph.queue.state.collectAsState()
    // The occurrence on screen: the player's, or the queue's before playback starts or after it failed. The player
    // publishes a track change before the lyrics owner has read it, so the owner's document is the previous track's
    // until then; every slot below works from the document that belongs to this occurrence.
    val activeOccurrenceId = activeLyricsOccurrence(playback?.occurrenceId, queue.currentOccurrenceId)
    val document = published.belongingTo(activeOccurrenceId)
    val occurrenceId = document?.occurrenceId
    val current = queue.entries.firstOrNull { it.occurrenceId == activeOccurrenceId }
    val matching = occurrenceId != null && playback?.occurrenceId == occurrenceId
    val saved by graph.settings.snapshots.collectAsState()
    val preferences = saved ?: PreferenceSnapshot.EMPTY
    val sessionKey = graph.lyricsSurface.sessionKey(document)
    val romanization = graph.lyricsSurface.romanization
    val storedSyncOffsetMs by remember(current?.item?.ref) { tools.offsets.observe(current?.item?.ref) }
        .collectAsState(null)
    val actions = remember(graph, occurrenceId, tools, current) {
        LyricsSurfaceActions(
            onSeek = { position ->
                val accepted = graph.player.snapshot.value
                if (accepted?.occurrenceId == occurrenceId && accepted?.isSeekable == true) graph.player.seekTo(position)
            },
            onShareCard = { card ->
                if (graph.player.snapshot.value?.occurrenceId == occurrenceId && current != null) tools.share(card, current.item.title)
            },
            onResume = {
                val accepted = graph.player.snapshot.value
                if (accepted?.occurrenceId == occurrenceId && accepted?.isSeekable == true) graph.player.play()
            },
        )
    }
    val style = preferences[PlayerDesignStyleKey].toEnum(PlayerDesignStyle.Default)
    LaunchedEffect(canvas, expanded, active, style) {
        canvas.setVisible(expanded && active && style.capabilities.artworkCanvas)
    }
    val lyrics = playerLyricsSlots(
        // The document belongs to the queue occurrence on screen, so its lines stay readable while playback has not
        // started or has failed: the player snapshot is the highlight clock, not a condition for showing the text.
        lines = document?.lines.orEmpty(),
        sessionKey = sessionKey,
        playback = graph.player.snapshot,
        romanization = romanization,
        settings = preferences.playerLyricsSettings(
            syncOffsetMs = effectiveLyricsSyncOffsetMs(storedSyncOffsetMs, (preferences[GlobalLyricsOffsetMsKey] ?: 0).toLong()),
            reducedMotion = preferences[DisableAnimationsKey] == true,
            // ENHANCED reads its depth on desktop too; reduced motion leaves every row flat.
            depthEffects = LyricsMode.fromStored(preferences[LyricsModeKey]) == LyricsMode.ENHANCED &&
                preferences[DisableAnimationsKey] != true,
        ),
        actions = actions,
        compactStyle = if (style == PlayerDesignStyle.BITCHORD) LyricsCompactStyle.BITCHORD else LyricsCompactStyle.SIMPMUSIC,
        credits = document?.credits,
        fullVisible = expanded && active,
        compactVisible = expanded && active,
        resumed = active,
    )
    return PlayerSlots(
        artwork = { id, modifier -> DesktopCanvasArtwork(id, graph, canvas, expanded && active, modifier) },
        canvas = { id, visible, modifier -> DesktopCanvasSurface(id, graph, canvas, canvasFrames, expanded && active && visible, modifier) },
        customBackground = { uri, modifier -> DesktopRemoteArtwork(uri, graph.artworkLoader, modifier) },
        lyrics = { modifier ->
            Column(modifier) {
                DesktopLyricsEditor(graph, current?.item, document)
                val ref = current?.item?.ref
                LyricsSyncControls(
                    storedSyncOffsetMs ?: 0L, { delta -> if (ref != null) tools.offsets.adjust(ref, delta) },
                    { if (ref != null) tools.offsets.reset(ref) }, enabled = matching && ref != null,
                )
                Box(Modifier.weight(1f)) {
                    // The pane resolves the published document itself: a track change shows the read of the track on
                    // screen, never the previous track's lines. With no document for this occurrence it names the
                    // state it is in, and an ordinary miss offers the lookup again.
                    DesktopLyricsPane(
                        document = published,
                        playbackOccurrenceId = playback?.occurrenceId,
                        queueOccurrenceId = queue.currentOccurrenceId,
                        onRetry = graph.lyrics::retry,
                    ) { bodyModifier -> lyrics.full(bodyModifier.fillMaxSize()) }
                }
            }
        },
        compactLyrics = lyrics.compact,
        queue = { modifier -> DesktopQueuePanel(graph, modifier) },
        outputButton = { modifier, tint, enabled -> DesktopOutputActionButton(outputSheet, modifier, tint, enabled) },
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
