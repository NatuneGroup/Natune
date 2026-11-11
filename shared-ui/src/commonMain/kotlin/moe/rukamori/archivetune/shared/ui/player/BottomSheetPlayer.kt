package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.LocalHostSafeArea
import moe.rukamori.archivetune.shared.ui.glass.OpticalCapabilities
import moe.rukamori.archivetune.shared.ui.glass.OpticalPolicy
import moe.rukamori.archivetune.shared.ui.glass.opticalPolicy
import moe.rukamori.archivetune.shared.ui.navigation.LocalDesktopLayout
import moe.rukamori.archivetune.shared.ui.playerstyles.*
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.safeAreaPadding
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
    /** The platform's reduce-motion setting; it keeps crossfades and drops travel, scale and overshoot. */
    val systemReducedMotion: Boolean = false,
    val motionLevel: PlayerMotionLevel = playerMotionLevel(optical.motion && !state.reducedMotion, systemReducedMotion),
) {
    val surfaces: PlayerSurfaceActivity get() = playerSurfaceActivity(
        preferences.style,interaction.panel,interaction.fullscreenLyrics,landscape,state.active)
    val motionActive: Boolean get() = motionLevel.moves
    val fadeActive: Boolean get() = motionLevel.fades
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
    val phonePresentation=moe.rukamori.archivetune.shared.ui.host.LocalAndroidPhonePresentation.current
    val horizontalPadding=if(phonePresentation && presentation.style==PlayerDesignStyle.V4) 32.dp else 20.dp
    val motionLevel=rememberPlayerMotionLevel(!state.reducedMotion && state.active && !preferences[DisableAnimationsKey, false])
    val artworkPager=rememberPlayerArtworkPager(state,actions,motionLevel.moves)
    LaunchedEffect(state.playback?.occurrenceId,state.playback?.playbackGeneration,state.playback?.discontinuityGeneration) {
        interaction.reconcile(state.playback)
    }
    DisposableEffect(interaction) { onDispose { interaction.cancelPreview() } }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val landscape=isSplitPlayerViewport(maxWidth,maxHeight)
        // The desktop presents one bounded portrait column; the style dispatch sees a portrait surface even when the
        // window is wider than it is tall, so no style falls back to its two-pane tablet split.
        val desktop=LocalDesktopLayout.current
        // Captured here: the BoxWithConstraints receiver is not implicit inside the Row lambda used for the side panel.
        val windowWidth=maxWidth
        val styleLandscape=landscape && !desktop
        val surfaces=playerSurfaceActivity(presentation.style,interaction.panel,interaction.fullscreenLyrics,landscape,state.active)
        // Occluded or hidden shells get a static artwork policy, so decorative blur and motion stop with their surface.
        val optical=opticalPolicy(preferences,OpticalCapabilities(blur=blurSupported,refraction=false),
            visible=surfaces.background || interaction.fullscreenLyrics && state.active)
        val handoff=rememberPlayerHandoff(state,optical.motion && motionLevel.moves)
        val content=slots(preferences,surfaces).withHandoff(handoff).withTrackMotion(motionLevel.takeIf { surfaces.artwork } ?: PlayerMotionLevel.None)
        // The fullscreen lyrics backdrop keeps its optical motion, but the player's own controls are occluded there.
        val c=PlayerRenderContext(state,actions,presentation,interaction,content,styleLandscape,artworkPager,optical,
            LocalSystemReducedMotion.current, motionLevel.takeIf { optical.motion && surfaces.background } ?: PlayerMotionLevel.None)
        val showingLyrics=surfaces.fullLyrics || surfaces.compactLyrics
        val visibilityCallback by rememberUpdatedState(actions.onLyricsVisibility)
        DisposableEffect(showingLyrics) {
            visibilityCallback(showingLyrics)
            onDispose { visibilityCallback(false) }
        }
        val forceDark=if(interaction.fullscreenLyrics) presentation.effectiveLyricsBackground() != "FOLLOW_THEME"
            else presentation.style.capabilities.monochrome ||
                presentation.style.resolveBackground(presentation.background)!=PlayerBackgroundStyle.DEFAULT
        MaterialTheme(colorScheme=if(forceDark) darkColorScheme() else MaterialTheme.colorScheme) {
            CompositionLocalProvider(
                LocalPlayerTrackMotion provides rememberPlayerTrackMotion(state.displayedOccurrenceId, state.occurrenceIds, motionLevel),
                LocalContentColor provides MaterialTheme.colorScheme.onBackground,
                LocalPlayerControlMotion provides PlayerControlMotionEnvironment(c.motionActive, state.repeat, c.fadeActive),
                LocalPlayerGlyphStyle provides presentation.style,
            ) {
                // The expansion surface paints edge to edge: its background fills the whole window, including the
                // host's status-bar and home-indicator strips, and only the controls are inset by the host safe area.
                // A host with no measured safe area (Android, desktop) gets an inset of zero and this is unchanged.
                Box(Modifier.fillMaxSize().testTag("player-${presentation.style.name}-${if(landscape) "landscape" else "portrait"}")) {
                    if (interaction.fullscreenLyrics) PlayerLyricsBackground(c) else PlayerBackground(c)
                    Box(Modifier.fillMaxSize().safeAreaPadding(LocalHostSafeArea.current)) {
                    if(interaction.fullscreenLyrics) {
                        FullscreenLyricsChrome(c)
                    } else if(desktop) {
                        Box(Modifier.fillMaxSize()) {
                            // The desktop keeps one visible close control; the style columns start under it.
                            PlayerIcon(
                                PlayerSymbol.BACK, stringResource(Res.string.m5_back),
                                { actions.onAction(PlayerAction.COLLAPSE) },
                                modifier = Modifier.align(Alignment.TopStart).padding(12.dp).testTag("player-close"),
                                size = 40.dp, iconSize = 22.dp,
                            )
                            Row(
                                Modifier.fillMaxSize().padding(start = 56.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(24.dp),
                            ) {
                                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                    Box(Modifier.widthIn(max = 648.dp).fillMaxHeight()) { PlayerStyleDispatch(c) }
                                }
                                val sidePanel = presentation.style != PlayerDesignStyle.FLAMINGO &&
                                    (interaction.panel != PlayerPanel.ARTWORK || presentation.style == PlayerDesignStyle.METROLIST_KMP)
                                val panelWidth = if (windowWidth >= 1200.dp) 420.dp else windowWidth * 0.42f
                                PlayerPanelTransition(interaction.panel.takeIf { sidePanel }, c.motionLevel, vertical = false) { panel ->
                                    Box(Modifier.width(panelWidth).fillMaxHeight()) {
                                        SelectedPlayerPanel(c, Modifier.fillMaxSize(), panel)
                                    }
                                }
                            }
                        }
                    } else if(landscape) {
                        Row(Modifier.fillMaxSize().padding(top=48.dp).padding(16.dp),horizontalArrangement=Arrangement.spacedBy(24.dp)) {
                            Box(Modifier.weight(1f).fillMaxHeight()) { PlayerStyleDispatch(c) }
                            if(presentation.style!=PlayerDesignStyle.FLAMINGO &&
                                (interaction.panel!=PlayerPanel.ARTWORK || presentation.style==PlayerDesignStyle.METROLIST_KMP)) {
                                SelectedPlayerPanel(c,Modifier.weight(1f).fillMaxHeight())
                            }
                        }
                    } else {
                        val cinematicPhone = phonePresentation && presentation.style == PlayerDesignStyle.V4
                        if (!(phonePresentation && interaction.panel == PlayerPanel.QUEUE)) {
                            Box(Modifier.fillMaxSize().padding(top=if(cinematicPhone) 0.dp else 48.dp).padding(horizontal=horizontalPadding,vertical=12.dp)) { PlayerStyleDispatch(c) }
                        }
                        val overlayPanel=interaction.panel.takeIf {
                            it!=PlayerPanel.ARTWORK && presentation.style!=PlayerDesignStyle.FLAMINGO
                        }
                        // The phone queue draws its own sheet chrome edge to edge; every other panel sits below the close control.
                        val panelSurface=Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
                        val panelInset=Modifier.padding(top=48.dp).padding(16.dp)
                        if(presentation.style==PlayerDesignStyle.SPATIALFLOW) {
                            // SpatialFlow reveals its own panel.
                            if(overlayPanel!=null) SelectedPlayerPanel(c,panelSurface
                                .then(if(phonePresentation && overlayPanel==PlayerPanel.QUEUE) Modifier else panelInset))
                        } else {
                            PlayerPanelTransition(overlayPanel,c.motionLevel) { panel ->
                                SelectedPlayerPanel(c,panelSurface
                                    .then(if(phonePresentation && panel==PlayerPanel.QUEUE) Modifier else panelInset),panel)
                            }
                        }
                    }
                    // Every phone/tablet style shares this hit target above artwork and panels.
                    // iOS has no hardware back button; its Compose root is not a UIKit navigation stack.
                    if (!desktop && !interaction.fullscreenLyrics &&
                        !(phonePresentation && !landscape && (interaction.panel == PlayerPanel.QUEUE ||
                            presentation.style == PlayerDesignStyle.V4 && interaction.panel == PlayerPanel.ARTWORK))) {
                        PlayerIcon(
                            if (moe.rukamori.archivetune.shared.ui.host.LocalAndroidPhonePresentation.current) PlayerSymbol.DOWN else PlayerSymbol.BACK,
                            stringResource(Res.string.m5_back),
                            { actions.onAction(PlayerAction.COLLAPSE) },
                            modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp)
                                .testTag("player-close"),
                            size = 48.dp, iconSize = 24.dp,
                        )
                    }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectedPlayerPanel(c: PlayerRenderContext,modifier: Modifier,panel: PlayerPanel=c.interaction.panel) {
    if(c.preferences.style==PlayerDesignStyle.SPATIALFLOW) SpatialFlowPanel(c,modifier)
    else PlayerSidePanel(c,modifier,panel)
}

/** [panel] is the one to draw; it differs from the interaction's while a closing panel animates out. */
@Composable
internal fun PlayerSidePanel(c: PlayerRenderContext,modifier: Modifier,panel: PlayerPanel=c.interaction.panel) {
    if (moe.rukamori.archivetune.shared.ui.host.LocalAndroidPhonePresentation.current && !c.landscape &&
        panel == PlayerPanel.QUEUE) {
        CompositionLocalProvider(moe.rukamori.archivetune.shared.ui.queue.LocalPhoneQueuePlayerActions provides
            moe.rukamori.archivetune.shared.ui.queue.PhoneQueuePlayerActions(c.state.displayedOccurrenceId, c.state.liked,
                if (PlayerAction.LIKE in c.state.actionSupport) { { c.actions.onAction(PlayerAction.LIKE) } } else null,
                if (PlayerAction.MORE in c.state.actionSupport) { { c.actions.onAction(PlayerAction.MORE) } } else null,
                onClose = { c.interaction.panel = PlayerPanel.ARTWORK })) {
            c.slots.queue(modifier)
        }
        return
    }
    Column(modifier.testTag("player-side-panel")) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            PlayerIcon(PlayerSymbol.BACK,stringResource(Res.string.m5_back),{ c.interaction.panel=PlayerPanel.ARTWORK })
            TextButton(onClick={ c.interaction.panel=PlayerPanel.LYRICS }) { Text(stringResource(Res.string.m5_lyrics)) }
            TextButton(onClick={ c.interaction.panel=PlayerPanel.QUEUE }) { Text(stringResource(Res.string.m5_queue)) }
            if(panel!=PlayerPanel.QUEUE) PlayerIcon(PlayerSymbol.EXPAND,stringResource(Res.string.m5_fullscreen),
                { c.interaction.fullscreenLyrics=true })
        }
        if(panel==PlayerPanel.QUEUE) c.slots.queue(Modifier.weight(1f).fillMaxWidth())
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

internal fun isSplitPlayerViewport(maxWidth: Dp, maxHeight: Dp): Boolean =
    maxWidth > maxHeight || (maxWidth >= 600.dp && maxHeight >= 480.dp)
