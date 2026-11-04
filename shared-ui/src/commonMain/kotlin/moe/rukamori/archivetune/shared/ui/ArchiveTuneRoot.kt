package moe.rukamori.archivetune.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
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
import moe.rukamori.archivetune.shared.ui.glass.*
import moe.rukamori.archivetune.shared.ui.navigation.*
import moe.rukamori.archivetune.shared.ui.player.*
import moe.rukamori.archivetune.shared.ui.screens.*
import moe.rukamori.archivetune.shared.ui.marquee.MarqueeText
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.theme.ArchiveTuneTheme
import moe.rukamori.archivetune.shared.ui.theme.archiveTuneDefaultColorScheme
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
            ArchiveTuneTheme { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
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
            CompositionLocalProvider(
                moe.rukamori.archivetune.shared.ui.songmetadata.LocalSongMetadataOverrides provides edits,
                LocalPodcastProgress provides byEpisode,
                moe.rukamori.archivetune.shared.ui.menu.LocalPlaylistPickerContext provides pickerContext,
                moe.rukamori.archivetune.shared.ui.screens.LocalCustomArtworkOverrides provides artworkOverrides,
            ) {
                ArchiveTuneLoadedRoot(host, preferences, visible, resumed, opticalCapabilities, television, systemDark)
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
) {
        val navigation = rememberShellNavigation(preferences)
        val unreadNews by host.unreadNews.collectAsState(false)
        val interaction = remember { PlayerInteractionState() }
        var expanded by remember { mutableStateOf(false) }
        // Collapsing is the root's own action, so the native owner never receives it.
        val sheetControls = remember(host.playerControls, interaction) {
            CollapsingPlayerControls(host.playerControls) { if (!interaction.back()) expanded = false }
        }
        val active = visible && resumed
        val queue by host.queue.state.collectAsState()
        val playback by host.queue.player.snapshot.collectAsState()
        val current = queue.entries.firstOrNull { it.occurrenceId == (playback?.occurrenceId ?: queue.currentOccurrenceId) }
        LaunchedEffect(host, navigation) {
            host.routes.collect { route ->
                if (ArchiveRoutes.resolve(route) != null) navigation.open(route) else host.onFailure()
            }
        }
        val goBack = {
            when {
                expanded && interaction.back() -> Unit
                expanded -> expanded = false
                !navigation.back() -> host.onBackAtRoot()
            }
        }
        LaunchedEffect(host, navigation, interaction) {
            host.backRequests.collect { goBack() }
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
        // A palette is rebuilt only when its seed, style or dark flag changes. Without a saved palette the scheme is
        // the default one, so the default look does not change.
        val paletteSeed = preferences.savedPaletteSeed()
        val paletteStyle = preferences.themePaletteStyle()
        val colorScheme = remember(paletteSeed, paletteStyle, darkTheme) {
            paletteSeed?.let { paletteColorScheme(it, darkTheme, paletteStyle) } ?: archiveTuneDefaultColorScheme(darkTheme)
        }
        val nativeState by host.playerControls.state.collectAsState()
        var rememberedMute by remember { mutableStateOf<Float?>(null) }
        fun handleKeymap(action: KeymapAction): Boolean {
            val player = host.queue.player
            val snapshot = player.snapshot.value
            when (action) {
                KeymapAction.TogglePlayback -> {
                    if (snapshot == null) return false
                    if (snapshot.playWhenReady) player.pause() else player.play()
                }
                KeymapAction.SeekBack -> dispatchNativePlayerAction(player, host.playerControls, PlayerAction.SEEK_BACKWARD)
                KeymapAction.SeekForward -> dispatchNativePlayerAction(player, host.playerControls, PlayerAction.SEEK_FORWARD)
                KeymapAction.Next -> dispatchNativePlayerAction(player, host.playerControls, PlayerAction.NEXT)
                KeymapAction.Previous -> dispatchNativePlayerAction(player, host.playerControls, PlayerAction.PREVIOUS)
                KeymapAction.Shuffle -> dispatchNativePlayerAction(player, host.playerControls, PlayerAction.SHUFFLE)
                KeymapAction.Repeat -> dispatchNativePlayerAction(player, host.playerControls, PlayerAction.REPEAT)
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
                KeymapAction.Search -> navigation.select(ShellTab.Search, toRoot = true)
                KeymapAction.ExpandPlayer -> expanded = !expanded
                KeymapAction.FullscreenLyrics -> { expanded = true; interaction.fullscreenLyrics = !interaction.fullscreenLyrics }
                KeymapAction.ToggleFullscreen -> host.toggleWindowFullscreen?.invoke() ?: return false
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
        ArchiveTuneTheme(darkTheme = darkTheme, disableAnimations = preferences[DisableAnimationsKey, false],
            fontPreference = launchSettings.fontPreference, customFontFamily = launchCustomFamily,
            colorScheme = colorScheme) {
            ProvideBackdropSources(policy) { pageSource ->
                // The background is drawn before the safe-area padding, so the strips keep the app's own colour; the
                // padding is physical and consumes the bar insets, so no screen below reserves them a second time.
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                    .safeAreaPadding(safeArea).onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val action = keymapActionOf(event.key,
                        ctrlOrMeta = event.isCtrlPressed || event.isMetaPressed,
                        alt = event.isAltPressed, shift = event.isShiftPressed) ?: return@onKeyEvent false
                    handleKeymap(action)
                }) {
                    ArchiveTuneShell(navigation, preferences,
                        onSearch = { navigation.select(ShellTab.Search, toRoot = true) },
                        television = television, contentModifier = pageSource,
                        miniPlayer = {
                            if (current != null) ArchiveMiniPlayer(
                                current = current,
                                playback = playback,
                                nativeState = nativeState,
                                artwork = host.artwork,
                                motion = policy.motion,
                                onExpand = { expanded = true },
                                onOpenPanel = { panel -> interaction.panel = panel; expanded = true },
                                onPlayPause = {
                                    if (playback?.playWhenReady == true) host.queue.player.pause() else host.queue.player.play()
                                },
                                onSeekTo = { targetMs -> host.queue.player.seekTo(targetMs) },
                                onAction = { action -> dispatchNativePlayerAction(host.queue.player, host.playerControls, action) },
                                onVolumeChange = { volume -> host.playerControls.setVolume(volume.coerceIn(0f, 1f)) },
                            )
                        },
                    ) {
                        ArchiveNavigationHost(navigation, host.page, graphActions, host.artwork, host.newsImage,
                            host.formatNewsDate, host.librarySourceSelector, host.hostScreen, host.overlays,
                            preferences = preferences, newsEntry = NewsEntry(host.newsAvailable, unreadNews))
                    }
                    // Keep the interaction owner above style dispatch; a saved style edit cannot reset
                    // seek ownership, queue/lyrics panels or the fullscreen scroll owner.
                    val slots = host.playerSlots(expanded, active, interaction)
                    if (expanded && current != null) BoundBottomSheetPlayer(
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
