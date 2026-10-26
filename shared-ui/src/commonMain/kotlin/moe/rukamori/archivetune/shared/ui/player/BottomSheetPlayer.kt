package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.glass.OpticalCapabilities
import moe.rukamori.archivetune.shared.ui.glass.OpticalPolicy
import moe.rukamori.archivetune.shared.ui.glass.opticalPolicy
import moe.rukamori.archivetune.shared.ui.playerstyles.*
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.getValue

internal class PlayerRenderContext(
    val state: PlayerState,
    val actions: PlayerActions,
    val preferences: PlayerPreferences,
    val interaction: PlayerInteractionState,
    val slots: PlayerSlots,
    val landscape: Boolean,
    val artworkPager: PlayerArtworkPagerState,
    /** M4's policy, built from the shell's actual background visibility; it already honors DisableBlur/DisableAnimations. */
    val optical: OpticalPolicy,
) {
    val surfaces: PlayerSurfaceActivity get() = playerSurfaceActivity(
        preferences.style,interaction.panel,interaction.fullscreenLyrics,landscape,state.active)
    val motionActive: Boolean get() = optical.motion && !state.reducedMotion
}

/**
 * Root/shell owns expansion and platform back dispatch; [interaction.back] runs before collapse.
 * Both orientation branches consume the same state/actions/draft and the same exhaustive dispatcher.
 * blurSupported is supplied by the actual platform renderer (not inferred from a stored preference).
 */
@Composable
fun BottomSheetPlayer(
    state: PlayerState,
    actions: PlayerActions,
    preferences: PreferenceSnapshot,
    slots: PlayerSlots,
    blurSupported: Boolean,
    modifier: Modifier = Modifier,
    interaction: PlayerInteractionState = remember { PlayerInteractionState() },
) {
    BottomSheetPlayer(state,actions,preferences,{ _, _ -> slots },blurSupported,modifier,interaction)
}

@Composable
fun BottomSheetPlayer(
    state: PlayerState,
    actions: PlayerActions,
    preferences: PreferenceSnapshot,
    slots: PlayerSlotFactory,
    blurSupported: Boolean,
    modifier: Modifier = Modifier,
    interaction: PlayerInteractionState = remember { PlayerInteractionState() },
) {
    val presentation=preferences.playerPreferences()
    val artworkPager=rememberPlayerArtworkPager(state,actions)
    LaunchedEffect(state.playback?.occurrenceId,state.playback?.playbackGeneration,state.playback?.discontinuityGeneration) {
        interaction.reconcile(state.playback)
    }
    DisposableEffect(interaction) { onDispose { interaction.cancelPreview() } }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val landscape=maxWidth>maxHeight
        val surfaces=playerSurfaceActivity(presentation.style,interaction.panel,interaction.fullscreenLyrics,landscape,state.active)
        // Occluded or hidden shells get a static artwork policy, so decorative blur and motion stop with their surface.
        val optical=opticalPolicy(preferences,OpticalCapabilities(blur=blurSupported,refraction=false),visible=surfaces.background)
        val handoff=rememberPlayerHandoff(state,optical.motion && !state.reducedMotion)
        val content=slots(preferences,surfaces).withHandoff(handoff)
        val c=PlayerRenderContext(state,actions,presentation,interaction,content,landscape,artworkPager,optical)
        val showingLyrics=surfaces.fullLyrics || surfaces.compactLyrics
        val visibilityCallback by rememberUpdatedState(actions.onLyricsVisibility)
        DisposableEffect(showingLyrics) {
            visibilityCallback(showingLyrics)
            onDispose { visibilityCallback(false) }
        }
        val forceDark=presentation.style.capabilities.monochrome ||
            presentation.style.resolveBackground(presentation.background)!=PlayerBackgroundStyle.DEFAULT
        MaterialTheme(colorScheme=if(forceDark) darkColorScheme() else MaterialTheme.colorScheme) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                Box(Modifier.fillMaxSize().testTag("player-${presentation.style.name}-${if(landscape) "landscape" else "portrait"}")) {
                    PlayerBackground(c)
                    if(interaction.fullscreenLyrics) {
                        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            PlayerIcon(PlayerSymbol.BACK,stringResource(Res.string.m5_back),{ interaction.fullscreenLyrics=false })
                            content.lyrics(Modifier.weight(1f).fillMaxWidth())
                        }
                    } else if(landscape) {
                        Row(Modifier.fillMaxSize().padding(16.dp),horizontalArrangement=Arrangement.spacedBy(24.dp)) {
                            Box(Modifier.weight(1f).fillMaxHeight()) { PlayerStyleDispatch(c) }
                            if(presentation.style!=PlayerDesignStyle.FLAMINGO &&
                                (interaction.panel!=PlayerPanel.ARTWORK || presentation.style==PlayerDesignStyle.METROLIST_KMP)) {
                                SelectedPlayerPanel(c,Modifier.weight(1f).fillMaxHeight())
                            }
                        }
                    } else {
                        Box(Modifier.fillMaxSize().padding(horizontal=20.dp,vertical=12.dp)) { PlayerStyleDispatch(c) }
                        if(interaction.panel!=PlayerPanel.ARTWORK && presentation.style!=PlayerDesignStyle.FLAMINGO) {
                            SelectedPlayerPanel(c,Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectedPlayerPanel(c: PlayerRenderContext,modifier: Modifier) {
    if(c.preferences.style==PlayerDesignStyle.SPATIALFLOW) SpatialFlowPanel(c,modifier)
    else PlayerSidePanel(c,modifier)
}

@Composable
internal fun PlayerSidePanel(c: PlayerRenderContext,modifier: Modifier) {
    Column(modifier.testTag("player-side-panel")) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            PlayerIcon(PlayerSymbol.BACK,stringResource(Res.string.m5_back),{ c.interaction.panel=PlayerPanel.ARTWORK })
            TextButton(onClick={ c.interaction.panel=PlayerPanel.LYRICS }) { Text(stringResource(Res.string.m5_lyrics)) }
            TextButton(onClick={ c.interaction.panel=PlayerPanel.QUEUE }) { Text(stringResource(Res.string.m5_queue)) }
            if(c.interaction.panel!=PlayerPanel.QUEUE) PlayerIcon(PlayerSymbol.EXPAND,stringResource(Res.string.m5_fullscreen),
                { c.interaction.fullscreenLyrics=true })
        }
        if(c.interaction.panel==PlayerPanel.QUEUE) c.slots.queue(Modifier.weight(1f).fillMaxWidth())
        else c.slots.lyrics(Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable
private fun PlayerStyleDispatch(c: PlayerRenderContext) {
    when(c.preferences.style) {
        PlayerDesignStyle.V1, PlayerDesignStyle.V2, PlayerDesignStyle.V3, PlayerDesignStyle.V4,
        PlayerDesignStyle.V5, PlayerDesignStyle.V6 -> NumberedPlayer(c)
        PlayerDesignStyle.V7 -> ImmersivePlayer(c)
        PlayerDesignStyle.V8 -> V8Player(c)
        PlayerDesignStyle.V9 -> V9Player(c)
        PlayerDesignStyle.V10 -> V10Player(c)
        PlayerDesignStyle.APPLE_MUSIC -> AppleMusicPlayer(c)
        PlayerDesignStyle.BITCHORD -> BitChordPlayer(c)
        PlayerDesignStyle.TIKTOK -> TikTokPlayer(c)
        PlayerDesignStyle.SIMPMUSIC -> SimpMusicPlayer(c)
        PlayerDesignStyle.SPATIALFLOW -> SpatialFlowPlayer(c)
        PlayerDesignStyle.LOOPER -> LooperPlayer(c)
        PlayerDesignStyle.FLAMINGO -> FlamingoPlayer(c)
        PlayerDesignStyle.METROLIST_KMP -> MetrolistPlayer(c)
    }
}

@Composable
internal fun PlayerColumn(modifier: Modifier = Modifier,content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
}
