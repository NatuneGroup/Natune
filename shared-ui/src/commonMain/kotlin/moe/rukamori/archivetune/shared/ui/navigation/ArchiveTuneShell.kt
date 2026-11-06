package moe.rukamori.archivetune.shared.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.glass.*
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** Apply to scroll content, not its viewport: the last row clears chrome while earlier rows pass below it. */
val LocalShellContentPadding = staticCompositionLocalOf<PaddingValues> { PaddingValues(0.dp) }

/**
 * Slots share one root owner. [content] excludes the mini-player/navigation.
 * Use ProvideBackdropSources and pass its page-only source modifier as [contentModifier].
 * This shell is the only bottom chrome on every platform: it measures its own bar and mini player and reserves
 * exactly that height beneath the pages.
 */
@Composable
fun ArchiveTuneShell(
    navigation: ShellNavigation,
    preferences: PreferenceSnapshot,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    television: Boolean = false,
    scrolledDown: Boolean = false,
    searchActive: Boolean = false,
    onSearchItemDoubleClick: (() -> Unit)? = null,
    contentModifier: Modifier = Modifier,
    miniPlayer: @Composable () -> Unit,
    content: @Composable (route: String) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // The scroll-driven compact/hide decisions match the baseline: adaptive bars compact, and
        // "hide while scrolling" with the expanded bar slides it away for the full-width mini player.
        val compactBehavior = preferences.navigationBarCompactBehavior()
        val hideOnScroll = preferences[NavigationBarHideOnScrollKey, false]
        val scrollDrivesBottomUi =
            compactBehavior == NavigationBarCompactBehavior.ADAPTIVE ||
                (hideOnScroll && compactBehavior == NavigationBarCompactBehavior.ALWAYS_EXPANDED)
        var internalScrolledDown by remember { mutableStateOf(false) }
        val effectiveScrolledDown = scrolledDown || internalScrolledDown
        val navigationBarScrollThresholdPx = with(LocalDensity.current) { NavigationBarHideScrollThreshold.toPx() }
        val navigationBarScrollConnection =
            remember(navigationBarScrollThresholdPx) {
                object : NestedScrollConnection {
                    private var travelled = 0f

                    override fun onPostScroll(
                        consumed: Offset,
                        available: Offset,
                        source: NestedScrollSource,
                    ): Offset {
                        if (source != NestedScrollSource.UserInput || consumed.y == 0f) return Offset.Zero
                        if (travelled != 0f && (travelled < 0f) != (consumed.y < 0f)) travelled = 0f
                        travelled += consumed.y
                        if (travelled <= -navigationBarScrollThresholdPx) {
                            internalScrolledDown = true
                            travelled = 0f
                        } else if (travelled >= navigationBarScrollThresholdPx) {
                            internalScrolledDown = false
                            travelled = 0f
                        }
                        return Offset.Zero
                    }
                }
            }
        val layout = shellLayout(maxWidth.value, television, preferences, effectiveScrolledDown, searchActive,
            navigation.route == navigation.selectedTab.route, isSettingsRoute(navigation.route),
            desktop = LocalDesktopLayout.current)
        val desktop = LocalDesktopLayout.current
        // Captured in the BoxWithConstraints scope: its implicit receiver is unavailable inside the Row lambda below.
        val labelledSidebar = desktop && maxWidth >= DesktopLabelledSidebarMinWidth
        // Search is the top bar's job on desktop, so the sidebar carries only the two library destinations.
        val tabs = remember(desktop) {
            if (desktop) ShellTab.entries.filter { it != ShellTab.Search } else ShellTab.entries
        }
        val hiddenByScroll =
            layout == ShellLayout.MiniPlayerOnly && effectiveScrolledDown && hideOnScroll &&
                compactBehavior == NavigationBarCompactBehavior.ALWAYS_EXPANDED
        val policy = LocalOpticalPolicy.current
        val bottomUiCompactFraction by animateFloatAsState(
            targetValue = if (layout == ShellLayout.CompactRow) 1f else 0f,
            animationSpec =
                if (preferences[DisableAnimationsKey, false] || !policy.motion) {
                    snap()
                } else {
                    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
                },
            label = "bottomUiCompactFraction",
        )
        // The page is recorded for frosted navigation only while a frosted bar, rail or compact row is composed on screen.
        val frostedGate = LocalFrostedNavigationGate.current
        val frostedOnScreen = frostedNavigationOnScreen(layout, preferences)
        SideEffect { frostedGate?.onScreen = frostedOnScreen }
        val apple = preferences.navigationPillStyle() == NavigationBarStyle.APPLE_MUSIC
        val pill = rememberPillStyle(preferences)
        val glowStrength = preferences.navigationBarGlowStrength()
        var chromeHeight by remember(layout) { mutableIntStateOf(0) }
        // Pages reserve the measured bar and mini player, so no card sits under a control the shell drew.
        val chromePadding = PaddingValues(bottom = with(LocalDensity.current) { chromeHeight.toDp() })
        Row(Modifier.fillMaxSize()) {
            if (layout == ShellLayout.Rail || layout == ShellLayout.TvRail) {
                if (labelledSidebar && layout == ShellLayout.Rail) {
                    DesktopNavigationSidebar(navigation, tabs, Modifier.testTag("shell-rail"))
                } else {
                    NavigationRail(Modifier.testTag("shell-rail").fillMaxHeight().navigationMaterial(preferences),
                        containerColor = Color.Transparent) {
                        tabs.forEach { tab ->
                            NavigationRailItem(
                                selected = navigation.selectedTab == tab,
                                onClick = { navigation.select(tab, toRoot = false) },
                                icon = { TabMark(tab) },
                                label = { Text(stringResource(tab.label)) },
                                modifier = Modifier.testTag("shell-tab-${tab.name}"),
                            )
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                CompositionLocalProvider(LocalShellContentPadding provides chromePadding) {
                    Box(
                        Modifier.fillMaxSize()
                            .then(contentModifier)
                            .then(if (scrollDrivesBottomUi) Modifier.nestedScroll(navigationBarScrollConnection) else Modifier)
                            .testTag("shell-content"),
                    ) { content(navigation.route) }
                }
                CompositionLocalProvider(
                    LocalNavigationBarHiddenByScroll provides hiddenByScroll,
                    LocalBottomUiCompactFraction provides bottomUiCompactFraction,
                ) {
                    // This sibling is excluded from both recordings; only page pixels feed the chrome.
                    Column(Modifier.align(Alignment.BottomCenter).onSizeChanged { chromeHeight = it.height }) {
                        when (layout) {
                            ShellLayout.Hidden -> Unit
                            ShellLayout.CompactRow -> Row(
                                Modifier.fillMaxWidth().heightIn(min = CompactControlSize + 8.dp)
                                    .padding(horizontal = 12.dp)
                                    .testTag("shell-compact"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(CompactControlGap),
                            ) {
                                val left = if (apple || compactLeftIsLibrary(navigation.route)) ShellTab.Library else ShellTab.Home
                                CompactCircleTab(left, navigation.selectedTab == left, pill, glowStrength) { navigation.select(left, toRoot = true) }
                                Box(Modifier.weight(1f).heightIn(min = 48.dp).miniPlayerStrip()
                                    .testTag("shell-mini-player")) { miniPlayer() }
                                if (apple || compactSearchVisible(navigation.route)) {
                                    CompactCircleTab(ShellTab.Search, navigation.selectedTab == ShellTab.Search, pill, glowStrength, onSearch)
                                }
                            }
                            else -> {
                                Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).miniPlayerStrip()
                                    .testTag("shell-mini-player")) { miniPlayer() }
                                if (layout == ShellLayout.BottomBar && preferences.usesSukisuNavigationBar()) {
                                    // The SukiSU bar takes the same bottom slot and the same selection path as the default bar.
                                    SukisuNavigationBar(
                                        items = ShellTab.entries,
                                        selected = { tab -> navigation.selectedTab == tab },
                                        onSelect = { tab -> navigation.select(tab, toRoot = false) },
                                        modifier = Modifier.testTag("shell-bottom-bar"),
                                        hideLabels = preferences[HideNavigationBarLabelsKey, false],
                                    )
                                } else if (layout == ShellLayout.BottomBar) {
                                    FloatingNavigationToolbar(
                                        items = ShellTab.entries,
                                        preferences = preferences,
                                        pureBlack = MaterialTheme.colorScheme.background == Color.Black,
                                        modifier = Modifier.testTag("shell-bottom-bar"),
                                        style = preferences.navigationPillStyle(),
                                        isSelected = { tab -> navigation.selectedTab == tab },
                                        onItemClick = { tab -> navigation.select(tab, toRoot = false) },
                                        onSearchItemDoubleClick = onSearchItemDoubleClick,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal val ShellTab.label: StringResource get() = when (this) {
    ShellTab.Home -> Res.string.m4_home
    ShellTab.Search -> Res.string.m4_search
    ShellTab.Library -> Res.string.m4_library
}

/**
 * One round chrome button of the compact one-row layout (Library circle, Search circle). With a liquid-glass
 * backdrop it is glass with the press spotlight; otherwise a tonal surface with a small shadow, like the
 * baseline CompactControlCircle.
 */
@Composable
private fun CompactCircleTab(tab: ShellTab, selected: Boolean, pill: PillStyle, glowStrength: Float, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val policy = LocalOpticalPolicy.current
    val glass = pill.backdrop == PillBackdrop.LIQUID_GLASS && policy.recordLiquidGlass && LocalLiquidGlassBackdrop.current != null
    // This finite press response is disposed when inactive/occluded/reduced-motion. No gesture is consumed.
    val progress = if (selected && policy.motion && glass) {
        animateFloatAsState(if (pressed) 1f else .25f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "tab-lens")
    } else rememberUpdatedState(0f)
    CompactControlCircle(
        backdrop = if (glass) PillBackdrop.LIQUID_GLASS else PillBackdrop.NONE,
        glowStrength = glowStrength,
        tint = if (selected || tab == ShellTab.Library) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        interactionSource = interactions,
        onClick = onClick,
        modifier = Modifier.testTag("shell-tab-${tab.name}"),
    ) {
        CompositionLocalProvider(LocalContentColor provides
            if (selected && !glass) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) {
            Box(
                Modifier
                    .then(
                        if (glass && selected) {
                            Modifier.liquidGlassMaterial(
                                shape = CircleShape,
                                role = LiquidGlassRole.SelectionLens,
                                progress = { progress.value },
                            )
                        } else {
                            Modifier
                        },
                    )
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { TabMark(tab) }
        }
    }
}

@Composable
private fun Modifier.navigationMaterial(
    preferences: PreferenceSnapshot,
    shape: Shape = RectangleShape,
    glassRole: LiquidGlassRole = LiquidGlassRole.NavigationBar,
    progress: () -> Float = { 1f },
): Modifier = when {
    preferences[LiquidGlassNavBarEnabledKey, false] -> liquidGlassMaterial(shape = shape, role = glassRole, progress = progress)
    preferences[NavigationBarFrostedBlurKey, false] || preferences[NavigationBarTintFrostedBlurKey, false] ->
        clip(shape).frostedNavigation(tinted = preferences[NavigationBarTintFrostedBlurKey, false])
    else -> background(MaterialTheme.colorScheme.surfaceContainer, shape)
}

/**
 * The mini-player slot is chrome, so it paints an opaque surface: the page cards behind it cannot show through. It is
 * also a hit target of its own, so a press its controls did not take stops here instead of reaching the page card
 * painted beneath. Hit testing already stops at the topmost sibling under the pointer, so this handler only has to
 * exist, and it must never consume: a consumed event cancels the wait-for-up of a control inside the strip. Nothing
 * here adds semantics, focus or a click action to the empty area.
 */
@Composable
private fun Modifier.miniPlayerStrip(): Modifier =
    if (LocalDesktopLayout.current) {
        // The desktop transport bar is part of the window, not a card: it takes the page colour, and the bar's own
        // seek line separates it from the content above.
        background(MaterialTheme.colorScheme.background).pointerInput(Unit, MiniPlayerStripHitTarget)
    } else {
        background(MaterialTheme.colorScheme.surfaceContainer).pointerInput(Unit, MiniPlayerStripHitTarget)
    }

/** Watches the strip's pointer events without consuming, so presence in the chain claims the region and nothing else. */
private val MiniPlayerStripHitTarget: suspend PointerInputScope.() -> Unit = {
    awaitPointerEventScope {
        while (true) awaitPointerEvent()
    }
}

/** Resource labels remain accessible even when a compact circle has no visible caption. */
@Composable
internal fun TabMark(tab: ShellTab) {
    val color = LocalContentColor.current
    androidx.compose.foundation.Canvas(
        Modifier.size(24.dp),
        contentDescription = stringResource(tab.label),
    ) {
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
        when (tab) {
            ShellTab.Home -> {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width * .1f, size.height * .45f); lineTo(size.width * .5f, size.height * .1f)
                    lineTo(size.width * .9f, size.height * .45f); lineTo(size.width * .9f, size.height * .9f)
                    lineTo(size.width * .1f, size.height * .9f); close()
                }
                drawPath(path, color, style = stroke)
            }
            ShellTab.Search -> {
                drawCircle(color, size.width * .3f,
                    center = androidx.compose.ui.geometry.Offset(size.width * .4f, size.height * .4f), style = stroke)
                drawLine(color,
                    androidx.compose.ui.geometry.Offset(size.width * .62f, size.height * .62f),
                    androidx.compose.ui.geometry.Offset(size.width * .95f, size.height * .95f), strokeWidth = 2.dp.toPx())
            }
            ShellTab.Library -> repeat(3) { i ->
                drawRect(color,
                    androidx.compose.ui.geometry.Offset(size.width * (.1f + i * .3f), size.height * .15f),
                    androidx.compose.ui.geometry.Size(size.width * .18f, size.height * .75f), style = stroke)
            }
        }
    }
}
