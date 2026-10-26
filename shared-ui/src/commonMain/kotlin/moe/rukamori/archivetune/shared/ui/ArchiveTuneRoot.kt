package moe.rukamori.archivetune.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.glass.*
import moe.rukamori.archivetune.shared.ui.navigation.*
import moe.rukamori.archivetune.shared.ui.player.*
import moe.rukamori.archivetune.shared.ui.screens.*
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.theme.ArchiveTuneTheme
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
)

/** Host lifecycle is an input: losing UI visibility never pauses background audio. */
@Composable
fun ArchiveTuneRoot(
    host: ArchiveTuneHostBindings,
    visible: Boolean,
    resumed: Boolean,
    opticalCapabilities: OpticalCapabilities,
    television: Boolean = false,
) {
    key(host) {
        val saved by host.settings.snapshots.collectAsState()
        val preferences = saved
        if (preferences == null) {
            ArchiveTuneTheme { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        } else {
            ArchiveTuneLoadedRoot(host, preferences, visible, resumed, opticalCapabilities, television)
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
        LaunchedEffect(host, navigation, interaction) {
            host.backRequests.collect {
                when {
                    expanded && interaction.back() -> Unit
                    expanded -> expanded = false
                    !navigation.back() -> host.onBackAtRoot()
                }
            }
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
        ArchiveTuneTheme(disableAnimations = preferences[DisableAnimationsKey, false]) {
            ProvideBackdropSources(policy) { pageSource ->
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    ArchiveTuneShell(navigation, preferences,
                        onSearch = { navigation.select(ShellTab.Search, toRoot = true) },
                        television = television, contentModifier = pageSource,
                        miniPlayer = {
                            if (current != null) Row(
                                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { expanded = true }.padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(current.item.title, Modifier.weight(1f), maxLines = 1)
                                TextButton(onClick = {
                                    if (playback?.playWhenReady == true) host.queue.player.pause() else host.queue.player.play()
                                }) { Text(stringResource(if (playback?.playWhenReady == true) Res.string.integration_pause else Res.string.integration_play)) }
                            }
                        },
                    ) {
                        ArchiveNavigationHost(navigation, host.page, graphActions, host.artwork, host.newsImage,
                            host.formatNewsDate, host.librarySourceSelector, host.hostScreen, host.overlays)
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

/** Explicit unconfigured boundary retained for hosts whose required owner has not been published. */
@Composable
fun ArchiveTuneRoot() {
    ArchiveTuneTheme {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            Text(stringResource(Res.string.integration_host_pending))
        }
    }
}
