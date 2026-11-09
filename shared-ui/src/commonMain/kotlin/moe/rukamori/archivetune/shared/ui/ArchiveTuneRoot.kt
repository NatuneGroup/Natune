package moe.rukamori.archivetune.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.playback.PodcastProgress
import moe.rukamori.archivetune.shared.domain.library.librarySortState
import moe.rukamori.archivetune.shared.domain.library.writeTo
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.component.ProfileMenuState
import moe.rukamori.archivetune.shared.ui.desktop.*
import moe.rukamori.archivetune.shared.ui.glass.*
import moe.rukamori.archivetune.shared.ui.navigation.*
import moe.rukamori.archivetune.shared.ui.player.*
import moe.rukamori.archivetune.shared.ui.screens.*
import moe.rukamori.archivetune.shared.ui.marquee.MarqueeText
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.shortcuts.KeyboardShortcutsDialog
import moe.rukamori.archivetune.shared.ui.shortcuts.LocalShortcutTextInput
import moe.rukamori.archivetune.shared.ui.shortcuts.ShortcutTextInputState
import moe.rukamori.archivetune.shared.ui.theme.ArchiveTuneTheme
import moe.rukamori.archivetune.shared.ui.theme.archiveTuneDefaultColorScheme
import moe.rukamori.archivetune.shared.ui.theme.artworkPaletteSeed
import moe.rukamori.archivetune.shared.ui.theme.artworkSurfaceScheme
import moe.rukamori.archivetune.shared.ui.theme.desktopArtworkSeed
import moe.rukamori.archivetune.shared.ui.theme.desktopFirstFrameScheme
import moe.rukamori.archivetune.shared.ui.theme.paletteColorScheme
import moe.rukamori.archivetune.shared.ui.theme.savedPaletteSeed
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * Required presentation bindings to one host graph. No queue, settings file, decoder or AI session
 * is created here. A missing platform operation must be reported by its owner, never a success no-op.
 */
class ArchiveTuneHostBindings(
    val queue: ContinuationQueueController,
    val settings: SettingsStore,
    val actionScope: CoroutineScope,
    val routes: Flow<String>,
    val backRequests: Flow<Unit>,
    val page: @Composable (ArchiveRoute) -> ArchiveGraphPage,
    val artwork: ContentArtwork,
    val newsImage: NewsImage,
    val formatNewsDate: (Long) -> String,
    val librarySourceSelector: @Composable () -> Unit,
    val hostScreen: @Composable (ArchiveRoute, ShellNavigation) -> Unit,
    val overlays: @Composable (ArchiveRoute) -> Unit,
    val playerSlots: @Composable (expanded: Boolean, active: Boolean, interaction: PlayerInteractionState) -> PlayerSlots,
    /** The native owner's observed capabilities and actions. The bound sheet reads and dispatches only through it. */
    val playerControls: PlayerNativeControls,
    val more: (ContentOccurrence) -> Unit,
    val playOccurrence: (ContentRequest?, QueueStart) -> Unit,
    val openExternal: (String) -> Unit,
    val onFailure: () -> Unit,
    val onBackAtRoot: () -> Unit,
    val podcastProgress: Flow<List<PodcastProgress>> = flowOf(emptyList()),
    val songMetadata: moe.rukamori.archivetune.shared.domain.songmetadata.SongMetadataRepository? = null,
    /** The repository-backed add-to-playlist picker; a host without one keeps the single-playlist chooser. */
    val playlistPicker: moe.rukamori.archivetune.shared.ui.menu.PlaylistPickerSource? = null,
    /** Loads the persisted custom font file into a family; a host without one falls back to the default family. */
    val customFontLoader: (suspend (uri: String) -> androidx.compose.ui.text.font.FontFamily?)? = null,
    /** Optional host adapter for an existing platform's richer saved palette format. */
    val savedThemeScheme: ((PreferenceSnapshot, Boolean) -> androidx.compose.material3.ColorScheme?)? = null,
    /**
     * Draws the signed-in account's picture into a frame the shell hands it, the desktop title bar's avatar and the
     * profile menu's header. Null, or a blank uri, keeps the account icon: a host without an image loader must not
     * promise a picture it cannot fetch.
     */
    val accountAvatar: (@Composable (uri: String, modifier: Modifier) -> Unit)? = null,
    /** The custom cover store; provided as overrides so playlist rows can choose cover or mosaic. */
    val customArtwork: moe.rukamori.archivetune.shared.domain.repository.CustomArtworkRepository? = null,
    /** Toggles the platform window's own fullscreen; null where the host window has no fullscreen surface. */
    val toggleWindowFullscreen: (() -> Unit)? = null,
    /** False when the build has no news feed, which hides the News entry. */
    val newsAvailable: Boolean = true,
    /** Whether the feed holds an item newer than the last one the user saw; drives the News unread dot. */
    val unreadNews: Flow<Boolean> = flowOf(false),
)

/** Host lifecycle is an input: losing UI visibility never pauses background audio. */
@Composable
fun ArchiveTuneRoot(
    host: ArchiveTuneHostBindings,
    visible: Boolean,
    resumed: Boolean,
    opticalCapabilities: OpticalCapabilities,
    television: Boolean = false,
    /** The OS light/dark answer as the host observes it; the default asks Compose, which may not see OS changes. */
    systemDark: Boolean = isSystemInDarkTheme(),
    /** The host's measured safe area; reserved once inside the theme so its own background paints the strips. */
    safeArea: SafeAreaPadding = SafeAreaPadding.None,
    /**
     * Desktop opts in: that window has no system back gesture, so the root takes key focus to receive its keymap.
     * Android and iOS leave it off and keep their platform back.
     */
    rootKeyFocus: Boolean = false,
    /**
     * Desktop opts in: the shell draws the desktop chrome (window title bar, labelled rail, docked panes, player bar)
     * around the same pages. Android and iOS leave it off and keep their current shell geometry.
     */
    desktopShell: Boolean = false,
) {
    key(host) {
        // A null local means this host has no edit store; leave repository-supplied effective rows intact.
        val edits by remember(host) {
            host.songMetadata?.observeOverrides()
                ?.map { it as Map<ContentRef, moe.rukamori.archivetune.shared.domain.songmetadata.SongMetadataOverride>? }
                ?: kotlinx.coroutines.flow.flowOf(null)
        }.collectAsState(initial = null)
        val saved by host.settings.snapshots.collectAsState()
        val preferences = saved
        if (preferences == null) {
            // The frame before the persisted read completes is still a desktop frame: it paints the desktop's own seed
            // rather than Material's stock scheme, so the window never flashes a palette it will not keep.
            val firstFrame = archiveTuneDefaultColorScheme(isSystemInDarkTheme())
            ArchiveTuneTheme(
                darkTheme = isSystemInDarkTheme(),
                colorScheme = if (desktopShell) {
                    desktopFirstFrameScheme(null, isSystemInDarkTheme(), ThemePaletteStyle.AUTOMATIC)
                } else {
                    firstFrame
                },
            ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        } else {
            val progress by host.podcastProgress.collectAsState(emptyList())
            val byEpisode = remember(progress) { progress.associateBy { it.episodeId } }
            // The picker sort is one persisted preference, read and written through the same settings store as the
            // other library sorts, so a chosen order survives the next picker on every host.
            val pickerContext = remember(host.playlistPicker, preferences) {
                host.playlistPicker?.let { source ->
                    moe.rukamori.archivetune.shared.ui.menu.PlaylistPickerContext(
                        source = source,
                        sort = preferences.librarySortState(
                            PlaylistPickerSortTypeKey,
                            PlaylistPickerSortDescendingKey,
                            moe.rukamori.archivetune.shared.domain.library.LibraryDefaults.playlistPickerSort,
                        ),
                        onSort = { type, descending ->
                            host.actionScope.launch {
                                host.settings.edit {
                                    moe.rukamori.archivetune.shared.domain.library.LibrarySortState(type, descending)
                                        .writeTo(this, PlaylistPickerSortTypeKey, PlaylistPickerSortDescendingKey)
                                }
                            }
                        },
                    )
                }
            }
            LaunchedEffect(host.customArtwork) { host.customArtwork?.ensureLoaded() }
            val artworkOverrides by (host.customArtwork?.overrides ?: remember { kotlinx.coroutines.flow.MutableStateFlow<moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkOverrides?>(null) }).collectAsState()
            val overflowAnchor = remember { moe.rukamori.archivetune.shared.ui.menu.OverflowMenuAnchor() }
            CompositionLocalProvider(
                moe.rukamori.archivetune.shared.ui.menu.LocalOverflowMenuAnchor provides overflowAnchor,
                moe.rukamori.archivetune.shared.ui.songmetadata.LocalSongMetadataOverrides provides edits,
                LocalPodcastProgress provides byEpisode,
                moe.rukamori.archivetune.shared.ui.menu.LocalPlaylistPickerContext provides pickerContext,
                moe.rukamori.archivetune.shared.ui.screens.LocalCustomArtworkOverrides provides artworkOverrides,
            ) {
                ArchiveTuneLoadedRoot(
                    host, preferences, visible, resumed, opticalCapabilities, television, systemDark, safeArea,
                    rootKeyFocus, desktopShell,
                )
            }
        }
    }
}

@Composable
private fun ArchiveTuneLoadedRoot(
    host: ArchiveTuneHostBindings,
    preferences: PreferenceSnapshot,
    visible: Boolean,
    resumed: Boolean,
    opticalCapabilities: OpticalCapabilities,
    television: Boolean,
    systemDark: Boolean,
    safeArea: SafeAreaPadding,
    rootKeyFocus: Boolean,
    desktopShell: Boolean,
) {
        val navigation = rememberShellNavigation(preferences)
        val unreadNews by host.unreadNews.collectAsState(false)
        val interaction = remember { PlayerInteractionState() }
        // The desktop columns: rail labels, the two docked panes and the expanded player. Phone hosts never read it.
        val desktopShellState = remember { DesktopShellState() }
        var expanded by remember { mutableStateOf(false) }
        var shortcutsVisible by remember { mutableStateOf(false) }
        // A search shortcut navigates and asks the Search screen for the field once; the screen reports the request
        // consumed, so returning to the tab later does not take focus from the user.
        var searchFocusRequested by remember { mutableStateOf(false) }
        // The desktop top bar owns the global search field; Ctrl+K asks it for focus instead of opening the Search page.
        val desktopSearchFocus = remember { DesktopSearchFocus() }
        // Handed to the page content, so a focused search field silences the unmodified shortcuts while typing.
        val textInputs = remember { ShortcutTextInputState() }
        // Collapsing is the root's own action, so the native owner never receives it.
        val sheetControls = remember(host.playerControls, interaction) {
            CollapsingPlayerControls(host.playerControls) { interaction.backOrCollapse { expanded = false } }
        }
        val active = visible && resumed
        val queue by host.queue.state.collectAsState()
        val playback by host.queue.player.snapshot.collectAsState()
        // Queue owners publish a removal or replacement before the engine reports the successor's occurrence, so
        // the queue's own current item stands in until playback catches up instead of collapsing the player. The
        // stale snapshot belongs to another track, so the stand-in is drawn without its progress or playback intent.
        val playing = queue.entries.firstOrNull { it.occurrenceId == playback?.occurrenceId }
        val current = playing ?: queue.entries.firstOrNull { it.occurrenceId == queue.currentOccurrenceId }
        val currentPlayback = playback.takeIf { playing != null }
        // With nothing left to show, drop the expanded destinations so neither swallows Back without its close control.
        LaunchedEffect(expanded, desktopShellState.expandedPlayer, current == null) {
            if (current == null) {
                expanded = false
                desktopShellState.expandedPlayer = false
            }
        }
        LaunchedEffect(host, navigation) {
            host.routes.collect { route ->
                if (ArchiveRoutes.resolve(route) != null) navigation.open(route) else host.onFailure()
            }
        }
        // Every back gesture goes through this one handler: the native one (Alt+Left, a mouse back button), Escape and
        // the keyboard's Back action. On the desktop the expanded player and the panes are the innermost layers, so
        // they close before the route changes; the phone shell keeps its sheet first, exactly as before.
        val goBack: () -> Unit = {
            when {
                desktopShell && desktopShellState.expandedPlayer -> desktopShellState.expandedPlayer = false
                desktopShell && (desktopShellState.queueOpen || desktopShellState.lyricsOpen) ->
                    desktopShellState.closePanes()
                expanded -> interaction.backOrCollapse { expanded = false }
                !navigation.back() -> host.onBackAtRoot()
            }
        }
        val currentGoBack by rememberUpdatedState(goBack)
        LaunchedEffect(host, navigation, interaction) {
            host.backRequests.collect { currentGoBack() }
        }
        fun load(request: QueueLoadRequest) {
            host.actionScope.launch {
                try { host.queue.load(request) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { host.onFailure() }
            }
        }
        val graphActions = ArchiveGraphActions(
            // Finite/RD/Mix/direct source meaning belongs to the existing host queue, not route
            // heuristics or a UI-loaded prefix. The host resolves selected metadata there.
            playOccurrence = host.playOccurrence,
            playCollection = ::load,
            more = host.more,
            openExternal = host.openExternal,
        )
        val policy = opticalPolicy(preferences, opticalCapabilities, resumed, visible,
            playerExpanded = expanded, lyricsExpanded = expanded && interaction.fullscreenLyrics)
        val darkTheme = darkThemeFor(preferences[DarkModeKey], systemDark)
        val nativeState by host.playerControls.state.collectAsState()
        // A palette is rebuilt only when its seed, style or dark flag changes. On desktop, with Dynamic theme on and no
        // saved custom colour, the current sleeve seeds the scheme so the page, rail and top bar carry its tonal family.
        // The sleeve's colour is remembered, so the *first* frame of the next launch is already in that family instead
        // of starting on Material's default and repainting once the feed has art; with no sleeve remembered yet the
        // desktop's own seed stands in, so the window never opens in stock purple.
        val paletteSeed = preferences.savedPaletteSeed()
        val paletteStyle = preferences.themePaletteStyle()
        val savedTheme = remember(preferences[moe.rukamori.archivetune.shared.domain.settings.CustomThemeColorKey],
            preferences[DynamicThemeKey], darkTheme, host) { host.savedThemeScheme?.invoke(preferences, darkTheme) }
        val desktopDynamic = desktopShell && preferences[DynamicThemeKey, true] && paletteSeed == null
        val liveArtworkSeed = if (desktopDynamic) artworkPaletteSeed(nativeState.palette) else null
        val storedArtworkSeed = if (desktopDynamic) preferences.desktopArtworkSeed() else null
        val artworkSeed = liveArtworkSeed ?: storedArtworkSeed
        val colorScheme = remember(paletteSeed, artworkSeed, paletteStyle, darkTheme, desktopDynamic, savedTheme) {
            savedTheme ?: paletteSeed?.let { paletteColorScheme(it, darkTheme, paletteStyle) }
                ?: artworkSurfaceScheme(artworkSeed, darkTheme, paletteStyle)
                ?: if (desktopDynamic) {
                    desktopFirstFrameScheme(null, darkTheme, paletteStyle)
                } else {
                    archiveTuneDefaultColorScheme(darkTheme)
                }
        }
        // Remember the sleeve for the next launch. The write is skipped when nothing changed, and a failed write only
        // costs the next launch its remembered colour, never this one its palette.
        LaunchedEffect(desktopDynamic, liveArtworkSeed, storedArtworkSeed) {
            if (!desktopDynamic || liveArtworkSeed == null || liveArtworkSeed == storedArtworkSeed) return@LaunchedEffect
            val argb = liveArtworkSeed.toArgb().toLong()
            runCatching { host.settings.edit { set(DesktopArtworkSeedKey, argb) } }
        }
        var rememberedMute by remember { mutableStateOf<Float?>(null) }
        fun handleKeymap(action: KeymapAction): Boolean {
            val player = host.queue.player
            val snapshot = player.snapshot.value
            when (action) {
                KeymapAction.TogglePlayback -> {
                    if (current == null) return false
                    player.togglePlayPause(currentPlayback)
                }
                KeymapAction.SeekBack -> dispatchNativePlayerAction(player, host.playerControls, PlayerAction.SEEK_BACKWARD)
                KeymapAction.SeekForward -> dispatchNativePlayerAction(player, host.playerControls, PlayerAction.SEEK_FORWARD)
                KeymapAction.Next -> dispatchNativePlayerAction(player, host.playerControls, PlayerAction.NEXT)
                KeymapAction.Previous -> dispatchNativePlayerAction(player, host.playerControls, PlayerAction.PREVIOUS)
                KeymapAction.Shuffle -> dispatchNativePlayerAction(player, host.playerControls, PlayerAction.SHUFFLE)
                KeymapAction.Repeat -> dispatchNativePlayerAction(player, host.playerControls, PlayerAction.REPEAT)
                KeymapAction.Like -> dispatchNativePlayerAction(player, host.playerControls, PlayerAction.LIKE)
                KeymapAction.VolumeUp, KeymapAction.VolumeDown -> {
                    val level = nativeState.volume ?: return false
                    val delta = if (action == KeymapAction.VolumeUp) KEYMAP_VOLUME_STEP else -KEYMAP_VOLUME_STEP
                    host.playerControls.setVolume((level + delta).coerceIn(0f, 1f))
                }
                KeymapAction.Mute -> {
                    val level = nativeState.volume ?: return false
                    val target = muteVolumeTarget(level, rememberedMute)
                    rememberedMute = target.remembered
                    host.playerControls.setVolume(target.volume)
                }
                is KeymapAction.SeekFraction -> {
                    if (snapshot?.isSeekable != true) return false
                    val target = fractionSeekTargetMs(snapshot.durationMs ?: return false, action.fraction) ?: return false
                    player.seekTo(target)
                }
                KeymapAction.Back -> goBack()
                KeymapAction.Search -> {
                    if (desktopShell) {
                        desktopSearchFocus.requestFocus()
                    } else {
                        navigation.select(ShellTab.Search, toRoot = true)
                        searchFocusRequested = true
                    }
                }
                KeymapAction.Home -> navigation.select(ShellTab.Home, toRoot = true)
                KeymapAction.Library -> navigation.select(ShellTab.Library, toRoot = true)
                KeymapAction.Playlists -> {
                    navigation.select(ShellTab.Library, toRoot = true)
                    navigation.open("library/playlists")
                }
                KeymapAction.Podcasts -> navigation.open("podcasts")
                KeymapAction.NewReleases -> navigation.open("new_release")
                KeymapAction.Charts -> navigation.open("charts_screen")
                KeymapAction.Settings -> navigation.open("settings")
                KeymapAction.NowPlaying -> if (desktopShell) {
                    desktopShellState.expandedPlayer = true
                } else {
                    expanded = true
                }
                KeymapAction.Queue -> if (desktopShell) {
                    desktopShellState.toggle(DesktopPane.Queue)
                } else {
                    expanded = true
                    interaction.panel = PlayerPanel.QUEUE
                }
                KeymapAction.TogglePlayer -> if (desktopShell) {
                    desktopShellState.expandedPlayer = !desktopShellState.expandedPlayer
                } else {
                    expanded = !expanded
                }
                KeymapAction.FullscreenLyrics -> if (desktopShell) {
                    desktopShellState.toggle(DesktopPane.Lyrics)
                } else {
                    expanded = true
                    interaction.fullscreenLyrics = !interaction.fullscreenLyrics
                }
                KeymapAction.ToggleFullscreen -> host.toggleWindowFullscreen?.invoke() ?: return false
                KeymapAction.Help -> shortcutsVisible = true
            }
            return true
        }
        // Font choices are restart-scoped: the values latched at the first root composition are what this process
        // runs, so a stored edit asks for a restart rather than swapping the typography live.
        val launchSettings = remember { RestartScopedSettings.from(host.settings.snapshots.value) }
        val customFontLoader = host.customFontLoader
        val launchCustomFamily by produceState<androidx.compose.ui.text.font.FontFamily?>(null, launchSettings.customFontUri) {
            value = if (customFontLoader != null && launchSettings.fontPreference == AppFontPreference.CUSTOM &&
                launchSettings.customFontUri.isNotEmpty()
            ) {
                try { customFontLoader(launchSettings.customFontUri) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            } else {
                null
            }
        }
        ArchiveTuneTheme(darkTheme = darkTheme, pureBlack = preferences[PureBlackKey, false],
            disableAnimations = preferences[DisableAnimationsKey, false],
            fontPreference = launchSettings.fontPreference, customFontFamily = launchCustomFamily,
            colorScheme = colorScheme) {
            CompositionLocalProvider(LocalDesktopLayout provides desktopShell) {
            ProvideBackdropSources(policy) { pageSource ->
                // One page host for both shells: only the columns around the page differ. Lyrics and queue surfaces
                // stay mounted while a desktop pane or the expanded player shows them, so their scroll owner survives
                // opening and closing the pane.
                val surfaceOpen = expanded || (desktopShell && (desktopShellState.expandedPlayer || desktopShellState.lyricsOpen))
                val slots = host.playerSlots(surfaceOpen, active, interaction)
                val page: @Composable (Modifier) -> Unit = { pageModifier ->
                    CompositionLocalProvider(LocalShortcutTextInput provides textInputs) {
                        ArchiveNavigationHost(navigation, host.page, graphActions, host.artwork, host.newsImage,
                            host.formatNewsDate, host.librarySourceSelector, host.hostScreen, host.overlays,
                            preferences = preferences, newsEntry = NewsEntry(host.newsAvailable, unreadNews),
                            settings = host.settings, onFailure = host.onFailure,
                            searchFocusRequested = searchFocusRequested,
                            onSearchFocusConsumed = { searchFocusRequested = false },
                            modifier = pageModifier)
                    }
                }
                fun playPause() = host.queue.player.togglePlayPause(currentPlayback)
                fun desktopPlayerAction(action: PlayerAction) =
                    dispatchNativePlayerAction(host.queue.player, host.playerControls, action)
                fun seekTo(targetMs: Long) = host.queue.player.seekTo(targetMs)
                fun setVolume(volume: Float) = host.playerControls.setVolume(volume.coerceIn(0f, 1f))
                // The background is drawn before the safe-area padding, so the strips keep the app's own colour. The
                // padding is physical and consumes the bar insets, so no screen below reserves them a second time; the
                // host's reading is published here for the surfaces that must paint edge to edge themselves.
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                    .rootKeyTarget(rootKeyFocus, navigation.route)
                    .playerShortcuts(textInputActive = { textInputs.active }, dispatch = ::handleKeymap)) {
                    CompositionLocalProvider(LocalHostSafeArea provides safeArea) {
                    Box(Modifier.fillMaxSize().safeAreaPadding(safeArea)) {
                    if (desktopShell) {
                        // The whole desktop shell reports text focus through the one counter the shortcut layer
                        // reads, so the title bar search field suppresses unmodified shortcuts like any page field.
                        CompositionLocalProvider(LocalShortcutTextInput provides textInputs) {
                            // The title bar's account area: the signed-in identity the phone top bar shows, read from
                            // the one settings snapshot, and the window's own navigation for the menu's rows.
                            val account = remember(preferences) { preferences.youTubeIdentity() }
                            val listenTogetherInTopBar = preferences[ListenTogetherInTopBarKey, false]
                            val profileMenuHost = remember(navigation) { desktopProfileMenuHost(navigation) }
                            DesktopShell(
                                navigation = navigation,
                                state = desktopShellState,
                                titleBar = {
                                    DesktopTitleBar(
                                        title = stringResource(Res.string.app_name),
                                        searchFocus = desktopSearchFocus,
                                        railExpanded = desktopShellState.railExpanded,
                                        onToggleRail = { desktopShellState.toggleRail() },
                                        onSubmitSearch = { query ->
                                            navigation.select(ShellTab.Search, toRoot = true)
                                            navigation.open(ArchiveRoutes.search(query, ContentProvider.YOUTUBE))
                                        },
                                        onOpenSettings = { navigation.open("settings") },
                                        // The desktop runs its update check on the Settings page, so the window has
                                        // no "an update is offered" state to put on the button yet.
                                        settingsBadge = false,
                                        profileMenu = DesktopProfileMenu(
                                            state = ProfileMenuState(
                                                accountName = account.name.takeIf { it.isNotBlank() },
                                                hasUnreadNews = unreadNews,
                                                // The title bar's own settings button covers Settings, so the menu
                                                // leaves that row out as the phone's home top bar does.
                                                showSettings = false,
                                                showListenTogether = listenTogetherInTopBar,
                                            ),
                                            host = profileMenuHost,
                                            imageUrl = account.imageUrl,
                                            avatar = host.accountAvatar,
                                        ),
                                    )
                                },
                                playerBar = {
                                    if (current != null) {
                                        DesktopPlayerBar(
                                            current = current,
                                            playback = currentPlayback,
                                            nativeState = nativeState,
                                            artwork = host.artwork,
                                            motion = policy.motion,
                                            panes = desktopShellState,
                                            onExpand = { desktopShellState.expandedPlayer = true },
                                            onPlayPause = ::playPause,
                                            onSeekTo = ::seekTo,
                                            onAction = ::desktopPlayerAction,
                                            onVolumeChange = ::setVolume,
                                        )
                                    } else {
                                        // An empty queue keeps the bar's slot, so the page never jumps.
                                        Spacer(Modifier.fillMaxWidth().height(DesktopShellMetrics.playerBarHeight))
                                    }
                                },
                                queuePane = { paneModifier -> slots.queue(paneModifier) },
                                lyricsPane = { paneModifier -> slots.lyrics(paneModifier) },
                                content = { pageModifier -> page(pageSource.then(pageModifier)) },
                            )
                            if (desktopShellState.expandedPlayer && current != null) {
                                DesktopExpandedPlayer(
                                    state = desktopPlayerState(current, currentPlayback, nativeState, policy.motion),
                                    slots = slots,
                                    onClose = { desktopShellState.expandedPlayer = false },
                                    onPlayPause = ::playPause,
                                    onSeekTo = ::seekTo,
                                    onAction = ::desktopPlayerAction,
                                    onVolumeChange = ::setVolume,
                                )
                            }
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                ArchiveTuneShell(navigation, preferences,
                                onSearch = { navigation.select(ShellTab.Search, toRoot = true) },
                                television = television, contentModifier = pageSource, chromeVisible = !expanded,
                                miniPlayer = {
                                    if (current != null) ArchiveMiniPlayer(
                                        current = current,
                                        playback = currentPlayback,
                                        nativeState = nativeState,
                                        artwork = host.artwork,
                                        motion = policy.motion,
                                        preferences = preferences,
                                        onExpand = { expanded = true },
                                        onOpenPanel = { panel -> interaction.panel = panel; expanded = true },
                                        onPlayPause = ::playPause,
                                        onSeekTo = ::seekTo,
                                        onAction = ::desktopPlayerAction,
                                        onVolumeChange = ::setVolume,
                                    )
                                    },
                                ) { page(Modifier) }
                            }
                        }
                    }
                    }
                    // A player that must paint the strips itself draws outside the root's padding and reserves the
                    // host's inset around its own controls through LocalHostSafeArea.
                    if (!desktopShell && expanded && current != null) BoundBottomSheetPlayer(
                        queue = host.queue,
                        settings = host.settings,
                        actionScope = host.actionScope,
                        controls = sheetControls,
                        slots = { _, _ -> slots },
                        blurSupported = opticalCapabilities.blur,
                        visible = visible,
                        resumed = resumed,
                        reducedMotion = preferences[DisableAnimationsKey, false],
                        onLyricsVisibility = { interaction.fullscreenLyrics = it },
                        modifier = Modifier.fillMaxSize(),
                        interaction = interaction,
                    )
                    if (shortcutsVisible) KeyboardShortcutsDialog(onDismiss = { shortcutsVisible = false })
                    }
                }
            }
            }
        }
}

/**
 * The root owns collapsing the sheet. The native owner never sees [PlayerAction.COLLAPSE], so a fullscreen lyrics panel
 * closes first and the sheet then follows, as it did before the native owner was bound.
 */
private class CollapsingPlayerControls(
    private val delegate: PlayerNativeControls,
    private val collapse: () -> Unit,
) : PlayerNativeControls by delegate {
    override fun onAction(action: PlayerAction, occurrenceId: String?) {
        if (action == PlayerAction.COLLAPSE) collapse() else delegate.onAction(action, occurrenceId)
    }
}

/**
 * The stored dark-mode choice resolves over the platform's own light/dark reading; "AUTO" and unknown values
 * follow it. The settings page writes exactly ON, OFF and AUTO.
 */
internal fun darkThemeFor(mode: String?, systemDark: Boolean): Boolean = when (mode) {
    "ON" -> true
    "OFF" -> false
    else -> systemDark
}

/** Explicit unconfigured boundary retained for hosts whose required owner has not been published. */
@Composable
fun ArchiveTuneRoot() {
    ArchiveTuneTheme {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            Text(stringResource(Res.string.integration_host_pending))
        }
    }
}
