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
    /** A tab bar and mini player the platform draws above the root, or null where the shell draws its own. */
    val nativeChrome: NativeShellChrome? = null,
    /** Toggles the platform window's own fullscreen; null where the host window has no fullscreen surface. */
    val toggleWindowFullscreen: (() -> Unit)? = null,
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
            CompositionLocalProvider(
                moe.rukamori.archivetune.shared.ui.songmetadata.LocalSongMetadataOverrides provides edits,
                LocalPodcastProgress provides byEpisode,
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
        host.nativeChrome?.let { chrome ->
            NativeChromeLink(chrome, navigation, current, playback?.playWhenReady == true, expanded,
                expand = { expanded = true },
                togglePlayback = { playing -> if (playing) host.queue.player.pause() else host.queue.player.play() })
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
        ArchiveTuneTheme(darkTheme = darkTheme, disableAnimations = preferences[DisableAnimationsKey, false],
            colorScheme = colorScheme) {
            ProvideBackdropSources(policy) { pageSource ->
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val action = keymapActionOf(event.key,
                        ctrlOrMeta = event.isCtrlPressed || event.isMetaPressed,
                        alt = event.isAltPressed, shift = event.isShiftPressed) ?: return@onKeyEvent false
                    handleKeymap(action)
                }) {
                    ArchiveTuneShell(navigation, preferences,
                        onSearch = { navigation.select(ShellTab.Search, toRoot = true) },
                        television = television, contentModifier = pageSource,
                        nativeChrome = host.nativeChrome,
                        miniPlayer = {
                            if (current != null) Row(
                                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { expanded = true }.padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                MarqueeText(current.item.title, Modifier.weight(1f))
                                val playing = playback?.playWhenReady == true
                                val playMotion = rememberPlayerControlMotion(PlayerControlKind.PlayPause, playing, policy.motion)
                                TextButton(onClick = {
                                    if (playing) host.queue.player.pause() else host.queue.player.play()
                                }) {
                                    Text(stringResource(if (playing) Res.string.integration_pause else Res.string.integration_play),
                                        playMotion.modifier)
                                }
                            }
                        },
                    ) {
                        ArchiveNavigationHost(navigation, host.page, graphActions, host.artwork, host.newsImage,
                            host.formatNewsDate, host.librarySourceSelector, host.hostScreen, host.overlays,
                            preferences = preferences)
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
 * Mirrors the root's tab, song and player state to the platform chrome and applies its requests here, so
 * [ShellNavigation] and the root's expanded state keep their single owners. The chrome hides while the player is open.
 */
@Composable
private fun NativeChromeLink(
    chrome: NativeShellChrome,
    navigation: ShellNavigation,
    current: ContentOccurrence?,
    playing: Boolean,
    expanded: Boolean,
    expand: () -> Unit,
    togglePlayback: (playing: Boolean) -> Unit,
) {
    val labels = NativeShellLabels(
        home = stringResource(Res.string.m4_home),
        search = stringResource(Res.string.m4_search),
        library = stringResource(Res.string.m4_library),
        play = stringResource(Res.string.integration_play),
        pause = stringResource(Res.string.integration_pause),
        expand = stringResource(Res.string.native_chrome_open_player),
    )
    val nowPlaying = current?.let {
        NativeNowPlaying(it.occurrenceId, it.item.title, it.item.subtitle, it.item.artworkUri, playing)
    }
    // Settings pages draw no shell chrome, so the platform tab bar and mini player step aside there as well.
    val state = NativeShellState(visible = !expanded && !isSettingsRoute(navigation.route),
        selectedTab = navigation.selectedTab, nowPlaying = nowPlaying, labels = labels)
    SideEffect { chrome.publish(state) }
    DisposableEffect(chrome) { onDispose { chrome.publish(NativeShellState()) } }
    val latestCurrent by rememberUpdatedState(current)
    val latestPlaying by rememberUpdatedState(playing)
    val latestExpand by rememberUpdatedState(expand)
    val latestToggle by rememberUpdatedState(togglePlayback)
    LaunchedEffect(chrome, navigation) {
        chrome.commands.collect { command ->
            when (command) {
                is NativeShellCommand.SelectTab -> navigation.select(command.tab)
                NativeShellCommand.ExpandPlayer -> if (latestCurrent != null) latestExpand()
                NativeShellCommand.TogglePlayback -> if (latestCurrent != null) latestToggle(latestPlaying)
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
