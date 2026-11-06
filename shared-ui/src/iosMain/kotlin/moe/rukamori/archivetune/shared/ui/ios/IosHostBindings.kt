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
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.data.platform.ios.iosDiagnosticTime
import moe.rukamori.archivetune.shared.data.platform.ios.iosNowMillis
import moe.rukamori.archivetune.shared.domain.queue.UserQueuedKind
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.lyrics.effectiveLyricsSyncOffsetMs
import moe.rukamori.archivetune.shared.domain.settings.GlobalLyricsOffsetMsKey
import moe.rukamori.archivetune.shared.domain.settings.PlayerDesignStyle
import moe.rukamori.archivetune.shared.domain.settings.PlayerDesignStyleKey
import moe.rukamori.archivetune.shared.domain.settings.LyricsRomanizeChineseKey
import moe.rukamori.archivetune.shared.domain.settings.LyricsRomanizeHindiKey
import moe.rukamori.archivetune.shared.domain.settings.LyricsRomanizeJapaneseKey
import moe.rukamori.archivetune.shared.domain.settings.LyricsRomanizeKoreanKey
import moe.rukamori.archivetune.shared.domain.settings.LyricsRomanizeOtherLanguagesKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.toEnum
import moe.rukamori.archivetune.shared.ui.ArchiveTuneHostBindings
import moe.rukamori.archivetune.shared.ui.IosHost
import moe.rukamori.archivetune.shared.ui.acoustic.AcousticQualityOverlay
import moe.rukamori.archivetune.shared.ui.glass.OpticalCapabilities
import moe.rukamori.archivetune.shared.ui.host.hostLibraryPage
import moe.rukamori.archivetune.shared.ui.host.civilFromDays
import moe.rukamori.archivetune.shared.ui.host.hostStatisticsPage
import moe.rukamori.archivetune.shared.ui.host.hostYearInMusicPage
import moe.rukamori.archivetune.shared.ui.host.StatisticsRangeWindow
import moe.rukamori.archivetune.shared.domain.lyrics.romanization.LyricsPhonetics
import moe.rukamori.archivetune.shared.domain.lyrics.romanization.LyricsRomanizationPreferences
import moe.rukamori.archivetune.shared.domain.lyrics.romanization.LyricsRomanizer
import moe.rukamori.archivetune.shared.ui.lyrics.LyricsCompactStyle
import moe.rukamori.archivetune.shared.ui.lyrics.LyricsRomanizationPresentation
import moe.rukamori.archivetune.shared.ui.lyrics.LyricsRomanizationSource
import moe.rukamori.archivetune.shared.ui.lyrics.LyricsRomanizationState
import moe.rukamori.archivetune.shared.ui.lyrics.LyricsSurfaceActions
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveDestination
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoutes
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphPage
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoute
import moe.rukamori.archivetune.shared.ui.player.PlayerSlots
import moe.rukamori.archivetune.shared.ui.player.capabilities
import moe.rukamori.archivetune.shared.ui.queue.PlaybackQueuePanel
import moe.rukamori.archivetune.shared.ui.player.playerLyricsSettings
import moe.rukamori.archivetune.shared.ui.player.playerLyricsSlots
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.resources.ios_host_surface_unbound
import moe.rukamori.archivetune.shared.ui.resources.ios_library_top_mixes_unavailable
import moe.rukamori.archivetune.shared.ui.resources.ios_queue_empty
import moe.rukamori.archivetune.shared.ui.resources.ios_queue_remove
import moe.rukamori.archivetune.shared.ui.resources.stats_range_1m
import moe.rukamori.archivetune.shared.ui.resources.stats_range_1w
import moe.rukamori.archivetune.shared.ui.resources.stats_range_1y
import moe.rukamori.archivetune.shared.ui.resources.stats_range_3m
import moe.rukamori.archivetune.shared.ui.resources.stats_range_6m
import moe.rukamori.archivetune.shared.ui.resources.stats_range_all
import org.jetbrains.compose.resources.stringResource
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
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
internal fun iosHostBindings(
    host: IosHost,
    graph: IosSessionGraph,
    recognition: IosRecognitionMount,
): ArchiveTuneHostBindings {
    val itemMenu = IosItemMenuState()
    val sleepSheet = IosSleepTimerSheetState()
    val tempoSheet = IosTempoPitchSheetState()
    val tempoPitchActions = IosTempoPitchActions(graph)
    val controls = IosPlayerControls(graph, host, sleepSheet, itemMenu)
    // The canvas owner resolves the current occurrence's loop behind the same gates the settings page shows;
    // its controls report availability so a canvas-capable style knows a loop answered.
    val canvas = createIosCanvas(graph)
    val canvasFrames = IosCanvasFrames(graph, canvas, graph.canvasStore)
    val canvasControls = IosCanvasControls(controls, canvas, graph.scope)
    val settingsOwners = IosSettingsOwners(graph, host.cookieBrowser, host.browser, host.appleMusicSheet) { host.viewController }
    // Routes this host opens itself, such as a Library shortcut card. The root collects them as it collects links.
    val ownRoutes = Channel<String>(capacity = OWN_ROUTE_CAPACITY)
    // Back requests a page answers itself, such as a chat's back port. The root pops the stack for them, as the gesture does.
    val backChannel = Channel<Unit>(capacity = BACK_REQUEST_CAPACITY)
    // Built once per bindings value. A page keys its remembered owner on these, so a fresh lambda per page call would
    // rebuild the owner on every root recomposition.
    val openOwnRoute: (String) -> Unit = { target -> ownRoutes.trySend(target) }
    val goBack: () -> Unit = { backChannel.trySend(Unit) }
    val catalogOwners = IosCatalogOwners(graph)
    val playlistCreation = IosPlaylistCreation(graph.scope, graph.libraryContent, openOwnRoute)
    val playlistTags = IosPlaylistTagsState(openOwnRoute)
    // The playlist overflow menu's host: pins, tags, covers and deletion run against the same owners as the page.
    val playlistMenus = IosPlaylistMenuHost(graph, host, itemMenu, playlistTags, openOwnRoute)
    graph.scope.launch {
        runCatching { graph.youtubeLibrarySync.performFullSync(authoritative = false) }
    }
    return ArchiveTuneHostBindings(
        queue = graph.queue,
        songMetadata = graph.library.songMetadata,
        customArtwork = graph.customArtwork,
        // The multi-playlist add picker reads the one library repository's candidates.
        playlistPicker = object : moe.rukamori.archivetune.shared.ui.menu.PlaylistPickerSource {
            override fun candidates(
                occurrences: List<ContentOccurrence>,
            ): Flow<List<moe.rukamori.archivetune.shared.domain.playlist.PlaylistPickerCandidate>> =
                graph.library.observePlaylistPicker(occurrences)
        },
        // The restart-scoped custom font resolves from the file the picker stored in the app container.
        customFontLoader = { uri -> IosCustomFont.family(uri) },
        settings = graph.settings,
        actionScope = graph.scope,
        // Links the Swift app session hands in merge with the host's own route opens. The system back
        // gesture is handled by the shell itself.
        routes = merge(iosLaunchRoutes(host, graph), ownRoutes.receiveAsFlow()),
        backRequests = backChannel.receiveAsFlow(),
        page = { route ->
            iosGraphPage(graph, host, itemMenu, recognition, catalogOwners, playlistCreation, playlistTags, route, openOwnRoute, goBack)
        },
        artwork = { item, modifier -> IosRemoteArtwork(item.artworkUri, graph.artworkLoader, modifier) },
        newsImage = { uri, modifier -> IosRemoteArtwork(uri, graph.artworkLoader, modifier) },
        // Feed dates are epoch seconds, the unit the Android host stores and formats.
        formatNewsDate = { epochSeconds -> iosDiagnosticTime(epochSeconds * 1_000L) },
        newsAvailable = graph.newsFeed.enabled,
        unreadNews = graph.newsFeed.unread,
        librarySourceSelector = { IosLibrarySourceSelector(graph) },
        hostScreen = { route, navigation ->
            if (iosMountsSettings(route)) {
                IosSettingsScreen(host, settingsOwners, route, navigation)
            } else {
                IosUnboundSurface(Modifier.fillMaxSize())
            }
        },
        overlays = { route ->
            IosItemMenuDialog(itemMenu, graph, host, route.value, openOwnRoute, playlistMenus, sleepSheet, tempoSheet)
            IosPlaylistOverlays(playlistCreation, playlistTags, graph, route)
            IosSleepTimerOverlay(sleepSheet, graph.sleepTimer)
            IosTempoPitchOverlay(tempoSheet, graph, tempoPitchActions)
            IosEqualizerOverlay(graph.equalizer)
            AcousticQualityOverlay(graph.acoustic)
        },
        playerSlots = { expanded, active, _ ->
            iosPlayerSlots(host, graph, canvas, canvasFrames, expanded, active)
        },
        playerControls = canvasControls,
        // The row menu offers the queue commands this host answers and the custom artwork editor; the rest of the
        // baseline menu needs owners this host does not connect yet.
        more = { occurrence -> itemMenu.open(occurrence) },
        playOccurrence = { collection, start -> iosPlayOccurrence(graph, catalogOwners, collection, start) },
        openExternal = { uri -> openIosUri(uri, graph::report) },
        onFailure = { graph.report("a host request could not be completed") },
        // The iOS root has no screen beneath it, so back at the root has nothing to pop.
        onBackAtRoot = { },
        podcastProgress = graph.podcastProgress.observeAll(),
    )
}

/**
 * The Library, the local history, the statistics, the year-in-music, the Listen Together and the Telegram browse and
 * bots routes the owners answer here; the catalogue dispatcher answers the search, detail, artist, playlist, browse,
 * account and info routes. Every other destination keeps the host boundary, and so does a Telegram route while no
 * client is signed in. The key keeps each route's own page state apart.
 */
@Composable
private fun iosGraphPage(
    graph: IosSessionGraph,
    host: IosHost,
    menu: IosItemMenuState,
    recognition: IosRecognitionMount,
    catalogOwners: IosCatalogOwners,
    playlistCreation: IosPlaylistCreation,
    playlistTags: IosPlaylistTagsState,
    route: ArchiveRoute,
    openRoute: (String) -> Unit,
    back: () -> Unit,
): ArchiveGraphPage =
    key(route.value) {
        when (route.destination) {
            ArchiveDestination.LIBRARY ->
                iosLibraryOrSpotifyPage(graph, openRoute)
            ArchiveDestination.HISTORY -> iosHistoryPage(graph, menu)
            ArchiveDestination.LOCAL_SONGS -> iosLocalSongsPage(graph, menu, back)
            ArchiveDestination.DOWNLOADS -> iosDownloadsPage(graph, back)
            ArchiveDestination.MUSIC_RECOGNITION ->
                iosRecognitionPage(recognition, route, back, openRoute, graph::report)
            ArchiveDestination.MUSIC_RECOGNITION_DETAILS ->
                iosRecognitionDetailsPage(recognition, route, back, openRoute, graph::report)
            ArchiveDestination.STATISTICS -> iosStatisticsPage(graph, menu, openRoute)
            ArchiveDestination.RECAP -> iosRecapPage(graph, host, menu, route, openRoute)
            ArchiveDestination.LISTEN_TOGETHER -> iosListenTogetherPage(graph, host, back, openRoute)
            ArchiveDestination.LISTEN_TOGETHER_CHAT -> iosCommentTogetherPage(graph, host, back)
            ArchiveDestination.TELEGRAM, ArchiveDestination.TELEGRAM_BROWSE -> iosTelegramBrowsePage(graph, host, back)
            ArchiveDestination.TELEGRAM_BOTS -> iosTelegramBotsPage(graph, host, back, openRoute)
            ArchiveDestination.TELEGRAM_BOT_CHAT -> iosTelegramBotChatPage(graph, host, route.arguments.getValue("botId"))
            ArchiveDestination.UPLOADED_SONGS -> iosUploadsPage(graph.uploads, graph.settings, host)
            else -> iosCatalogGraphPage(graph, host, catalogOwners, menu, playlistCreation, playlistTags, route, back, openRoute)
                ?: ArchiveGraphPage.Host
        }
    }

/**
 * The player's media slots. Lyrics come from the graph's lyrics owner and render through the shared renderers on the
 * player's own snapshot, so a tap seeks the one player. The Japanese pack romanizes the lines it has loaded: an
 * absent pack or an opted-out preference publishes the off presentation, as before.
 */
@Composable
private fun iosPlayerSlots(
    host: IosHost,
    graph: IosSessionGraph,
    canvas: IosCanvasOwner,
    canvasFrames: IosCanvasFrames,
    expanded: Boolean,
    active: Boolean,
): PlayerSlots {
    val document by graph.lyrics.current.collectAsState()
    val saved by graph.settings.snapshots.collectAsState()
    val preferences = saved ?: PreferenceSnapshot.EMPTY
    val sessionKey = document?.occurrenceId ?: NO_LYRICS_SESSION
    val romanization = remember(sessionKey) { LyricsRomanizationState(LyricsRomanizationPresentation.off(sessionKey)) }
    val japanesePack by host.japaneseLanguagePack.collectAsState()
    val tokenizer by (japanesePack?.tokenizer ?: flowOf(null)).collectAsState(initial = null)
    val romanizationPreferences = remember(preferences) {
        LyricsRomanizationPreferences(
            romanizeJapanese = preferences[LyricsRomanizeJapaneseKey] ?: false,
            romanizeKorean = preferences[LyricsRomanizeKoreanKey] ?: false,
            romanizeChinese = preferences[LyricsRomanizeChineseKey] ?: false,
            romanizeHindi = preferences[LyricsRomanizeHindiKey] ?: false,
            romanizeOther = preferences[LyricsRomanizeOtherLanguagesKey] ?: false,
        )
    }
    LaunchedEffect(sessionKey, tokenizer, document?.lines, romanizationPreferences) {
        if (!romanizationPreferences.isEnabled) {
            romanization.publish(LyricsRomanizationPresentation.off(sessionKey))
            return@LaunchedEffect
        }
        val lines = document?.lines.orEmpty()
        val phonetics = withContext(Dispatchers.Default) {
            val romanizer = LyricsRomanizer(japaneseTokenizer = tokenizer)
            lines.map { romanizer.phoneticsForLine(it, romanizationPreferences) }
        }
        romanization.publish(LyricsRomanizationPresentation(sessionKey, LyricsRomanizationSource.BUILT_IN, phonetics))
    }
    val actions = remember(graph, document?.occurrenceId) {
        val occurrenceId = document?.occurrenceId
        LyricsSurfaceActions(
            // Seek and resume share the desktop's acceptance gate: a tap on stale lyrics, or an
            // unseekable item, performs neither.
            onSeek = { position ->
                val accepted = graph.player.snapshot.value
                if (accepted?.occurrenceId == occurrenceId && accepted?.isSeekable == true) {
                    graph.player.seekTo(position)
                }
            },
            onShareCard = { },
            onResume = {
                val accepted = graph.player.snapshot.value
                if (accepted?.occurrenceId == occurrenceId && accepted?.isSeekable == true) graph.player.play()
            },
        )
    }
    val style = preferences[PlayerDesignStyleKey].toEnum(PlayerDesignStyle.Default)
    // The resolver runs only while a canvas-capable style shows the player surface.
    LaunchedEffect(canvas, expanded, active, style) {
        canvas.setVisible(expanded && active && style.capabilities.artworkCanvas)
    }
    val reducedMotion = rememberIosReduceMotion()
    // The stored per-song correction plus the global offset feeds every lyrics surface.
    val queueState by graph.queue.state.collectAsState()
    val currentSongRef = remember(queueState, document?.occurrenceId) {
        queueState.entries.firstOrNull { it.occurrenceId == document?.occurrenceId }?.item?.ref
    }
    val storedSyncOffsetMs by remember(currentSongRef) {
        currentSongRef?.let(graph.songLyricsOffsets::observeOffset) ?: flowOf(null)
    }.collectAsState(null)
    val lyricsSettings = preferences.playerLyricsSettings(
        // The route delay comes from PlaybackSnapshot through LyricsClock, never a second sync offset.
        syncOffsetMs = effectiveLyricsSyncOffsetMs(storedSyncOffsetMs, (preferences[GlobalLyricsOffsetMsKey] ?: 0).toLong()),
        reducedMotion = reducedMotion,
        depthEffects = true,
        backgroundVocals = true,
    )
    val lyrics = playerLyricsSlots(
        lines = document?.lines.orEmpty(),
        sessionKey = sessionKey,
        playback = graph.player.snapshot,
        romanization = romanization,
        settings = lyricsSettings,
        actions = actions,
        compactStyle = if (style == PlayerDesignStyle.BITCHORD) LyricsCompactStyle.BITCHORD else LyricsCompactStyle.SIMPMUSIC,
        fullVisible = expanded && active,
        compactVisible = expanded && active,
        resumed = active,
        credits = document?.credits,
    )
    // Every lyric look is the shared Compose renderer; this host mounts no second one.
    val fullLyrics = lyrics.full
    return PlayerSlots(
        artwork = { occurrenceId, modifier ->
            IosCanvasArtwork(occurrenceId, graph, canvas, expanded && active, modifier)
        },
        canvas = { occurrenceId, visible, modifier ->
            IosCanvasSurface(occurrenceId, graph, canvas, canvasFrames, expanded && active && visible, modifier)
        },
        // The picked file loads through the same bounded loader as remote covers; a missing file keeps the placeholder.
        customBackground = { uri, modifier -> IosRemoteArtwork(uri, graph.artworkLoader, modifier) },
        lyrics = fullLyrics,
        compactLyrics = lyrics.compact,
        queue = { modifier -> IosQueuePanel(graph, modifier) },
        outputButton = { modifier, tint, enabled -> IosAudioRoutePicker(modifier, tint, enabled) },
    )
}

private const val NO_LYRICS_SESSION = "ios-no-lyrics"

/** Routes this host opens itself. The bound keeps a burst of taps from growing memory, and the root drains them promptly. */
private const val OWN_ROUTE_CAPACITY = 4

/** Back requests a page sends itself, bounded the same way as the routes; the root drains them as it does the gesture. */
private const val BACK_REQUEST_CAPACITY = 4

/** Milliseconds in one civil day. The range labels count civil days from the device clock. */
private const val DAY_MS = 86_400_000L

/**
 * The shared Statistics page over the iOS listening history, on the device's own calendar and locale. The six fixed chip
 * names resolve here, on the composition, because the range label is a plain callback that the page calls while it composes.
 */
@Composable
private fun iosStatisticsPage(graph: IosSessionGraph, menu: IosItemMenuState, openRoute: (String) -> Unit): ArchiveGraphPage {
    val continuousLabels = mapOf(
        "c0" to stringResource(Res.string.stats_range_1w),
        "c1" to stringResource(Res.string.stats_range_1m),
        "c2" to stringResource(Res.string.stats_range_3m),
        "c3" to stringResource(Res.string.stats_range_6m),
        "c4" to stringResource(Res.string.stats_range_1y),
        "c5" to stringResource(Res.string.stats_range_all),
    )
    return hostStatisticsPage(
        statistics = graph.history,
        earliestListenMs = graph.history::earliestListenMs,
        play = { rank -> iosPlayNow(graph, rank.item) },
        open = { ref -> openRoute(ArchiveRoutes.content(ref)) },
        recap = { year -> openRoute("year_in_music?year=$year") },
        // The page hands over only a content reference; the menu resolves the real item before it opens.
        more = { ref -> iosOpenRefMenu(graph, menu, "stats", ref) },
        nowMs = ::iosNowMillis,
        offsetMsAt = ::iosOffsetMsAt,
        rangeLabel = { window -> iosStatisticsRangeLabel(window, continuousLabels) },
        formatDuration = ::iosStatsDuration,
        formatWeekday = ::iosWeekdayName,
        report = graph::report,
    )
}

/** The shared year recap over the iOS listening history. The card image shares through the system share sheet. */
@Composable
private fun iosRecapPage(
    graph: IosSessionGraph,
    host: IosHost,
    menu: IosItemMenuState,
    route: ArchiveRoute,
    openRoute: (String) -> Unit,
): ArchiveGraphPage =
    hostYearInMusicPage(
        statistics = graph.history,
        earliestListenMs = graph.history::earliestListenMs,
        yearArg = route.arguments["year"]?.toIntOrNull(),
        share = { share -> iosShareRecapCard(graph, host, share) },
        songMenu = { ref -> iosOpenRefMenu(graph, menu, "recap", ref) },
        openRecap = { year -> openRoute("year_in_music?year=$year") },
        nowMs = ::iosNowMillis,
        offsetMsAt = ::iosOffsetMsAt,
        formatDuration = ::iosStatsDuration,
        report = graph::report,
    )

/** Plays one ranked item now: it is inserted next in the queue and then selected, through the queue owner. */
private fun iosPlayNow(graph: IosSessionGraph, item: ContentItem) {
    val occurrence = ContentOccurrence(occurrenceId = "stats:${NSUUID().UUIDString.lowercase()}", item = item)
    queueCommand(graph, "the song was not played") {
        graph.queue.insert(listOf(occurrence), UserQueuedKind.PLAY_NEXT)
        graph.queue.select(occurrence.occurrenceId, 0L)
    }
}

/** The device's short month names, January first. Read on each use, so a locale change applies without a restart. */
private val iosMonthNames: List<String>
    get() = NSDateFormatter().shortMonthSymbols?.mapNotNull { it as? String }.orEmpty()

/** The device's short weekday names, Sunday first, so index 0 is the statistics slot for Sunday. */
private val iosWeekdays: List<String>
    get() = NSDateFormatter().shortWeekdaySymbols?.mapNotNull { it as? String }.orEmpty()

/**
 * The chip text the baseline formats: "dd MMM - dd MMM" for weeks, where a week inside one month starts with a bare day;
 * "MMM" or "MMM yyyy" for months; "yyyy" for years; and the six fixed names. The fixed windows are matched by their key,
 * because their continuousIndex is the number of months or years each one spans, not a slot.
 */
private fun iosStatisticsRangeLabel(window: StatisticsRangeWindow, continuousLabels: Map<String, String>): String {
    continuousLabels[window.key]?.let { return it }
    val start = window.startCivil ?: return window.key
    val end = window.endCivil ?: start
    val months = iosMonthNames
    fun month(number: Int): String = months.getOrElse(number - 1) { number.toString() }
    fun padded(day: Int): String = day.toString().padStart(2, '0')
    val now = iosNowMillis()
    val currentYear = civilFromDays((now + iosOffsetMsAt(now)).floorDiv(DAY_MS)).first
    return when {
        window.key.startsWith("y") -> "${start.first}"
        window.key.startsWith("m") ->
            if (start.first == currentYear) month(start.second) else "${month(start.second)} ${start.first}"
        else -> {
            val startText = "${padded(start.third)} ${month(start.second)}"
            val endText = "${padded(end.third)} ${month(end.second)}"
            when {
                start.first != currentYear -> "$startText, ${start.first} - $endText, ${end.first}"
                start.second != end.second -> "$startText - $endText"
                else -> "${start.third} - $endText"
            }
        }
    }
}

/** Durations in the baseline's form: "Xd Xh Xm Xs" from a day on, "Xh Xm Xs" from an hour on, otherwise "M:SS". */
private fun iosStatsDuration(durationMs: Long): String {
    var seconds = durationMs.coerceAtLeast(0L) / 1000L
    val days = seconds / 86_400L
    seconds %= 86_400L
    val hours = seconds / 3_600L
    seconds %= 3_600L
    val minutes = seconds / 60L
    seconds %= 60L
    return when {
        days > 0L -> "${days}d ${hours}h ${minutes}m ${seconds}s"
        hours > 0L -> "${hours}h ${minutes}m ${seconds}s"
        else -> "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}

/** The short weekday name for a statistics slot, where Sunday is 0. */
private fun iosWeekdayName(index: Int): String = iosWeekdays.getOrElse(index) { index.toString() }

/** Artwork remains native; queue controls and commands are shared with desktop. */
@Composable
private fun IosQueuePanel(graph: IosSessionGraph, modifier: Modifier) {
    PlaybackQueuePanel(queue = graph.queue, edits = graph.queue, settings = graph.settings,
        library = graph.library, modifier = modifier) { entry, imageModifier ->
        IosRemoteArtwork(entry.item.artworkUri, graph.artworkLoader, imageModifier)
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
