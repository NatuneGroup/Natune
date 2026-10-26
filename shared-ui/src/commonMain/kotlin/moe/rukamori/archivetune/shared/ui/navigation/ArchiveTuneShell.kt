package moe.rukamori.archivetune.shared.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
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

/** Apply to scroll content, not its viewport: the last row clears chrome while earlier rows pass below it. */
val LocalShellContentPadding = staticCompositionLocalOf<PaddingValues> { PaddingValues(0.dp) }

/**
 * Slots share one root owner. [content] excludes the mini-player/navigation.
 * Use ProvideBackdropSources and pass its page-only source modifier as [contentModifier].
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
    contentModifier: Modifier = Modifier,
    miniPlayer: @Composable () -> Unit,
    content: @Composable (route: String) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val layout = shellLayout(maxWidth.value, television, preferences, scrolledDown, searchActive,
            navigation.route == navigation.selectedTab.route)
        // The page is recorded for frosted navigation only while a frosted bar, rail or compact row is composed on screen.
        val frostedGate = LocalFrostedNavigationGate.current
        val frostedOnScreen = frostedNavigationOnScreen(layout, preferences)
        SideEffect { frostedGate?.onScreen = frostedOnScreen }
        val apple = preferences.interfaceStyle() == InterfaceStyle.APPLE_MUSIC
        var chromeHeight by remember(layout) { mutableIntStateOf(0) }
        val chromePadding = PaddingValues(bottom = with(LocalDensity.current) { chromeHeight.toDp() })
        Row(Modifier.fillMaxSize()) {
            if (layout == ShellLayout.Rail || layout == ShellLayout.TvRail) {
                NavigationRail(Modifier.testTag("shell-rail").fillMaxHeight().navigationMaterial(preferences),
                    containerColor = Color.Transparent) {
                    ShellTab.entries.forEach { tab ->
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
            Box(Modifier.weight(1f).fillMaxHeight()) {
                CompositionLocalProvider(LocalShellContentPadding provides chromePadding) {
                    Box(Modifier.fillMaxSize().then(contentModifier).testTag("shell-content")) { content(navigation.route) }
                }
                // This sibling is excluded from both recordings; only page pixels feed the chrome.
                Column(Modifier.align(Alignment.BottomCenter).onSizeChanged { chromeHeight = it.height }) {
                    when (layout) {
                        ShellLayout.Hidden -> Unit
                        ShellLayout.CompactRow -> Row(
                            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp)
                                .navigationMaterial(preferences, RoundedCornerShape(32.dp)).testTag("shell-compact"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val left = if (apple || compactLeftIsLibrary(navigation.route)) ShellTab.Library else ShellTab.Home
                            CircleTab(left, navigation.selectedTab == left, preferences) { navigation.select(left, toRoot = true) }
                            Box(Modifier.weight(1f).heightIn(min = 48.dp).testTag("shell-mini-player")) { miniPlayer() }
                            if (apple || compactSearchVisible(navigation.route)) {
                                CircleTab(ShellTab.Search, navigation.selectedTab == ShellTab.Search, preferences, onSearch)
                            }
                        }
                        else -> {
                            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("shell-mini-player")) { miniPlayer() }
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
                                NavigationBar(Modifier.testTag("shell-bottom-bar").navigationMaterial(preferences),
                                    containerColor = Color.Transparent, windowInsets = WindowInsets(0, 0, 0, 0)) {
                                    ShellTab.entries.forEach { tab ->
                                        NavigationBarItem(
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

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun CircleTab(tab: ShellTab, selected: Boolean, preferences: PreferenceSnapshot, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val policy = LocalOpticalPolicy.current
    val glass = preferences[LiquidGlassNavBarEnabledKey, false] && policy.recordLiquidGlass && LocalLiquidGlassBackdrop.current != null
    // This finite press response is disposed when inactive/occluded/reduced-motion. No gesture is consumed.
    val progress = if (selected && policy.motion && glass) {
        animateFloatAsState(if (pressed) 1f else .25f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "tab-lens")
    } else rememberUpdatedState(0f)
    CompositionLocalProvider(LocalContentColor provides
        if (selected && !glass) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) {
        Box(Modifier.size(56.dp).clip(CircleShape)
            .navigationMaterial(preferences, CircleShape,
                if (selected) LiquidGlassRole.SelectionLens else LiquidGlassRole.NavigationBar, progress = { progress.value })
            .selectable(selected, interactionSource = interactions, indication = LocalIndication.current, role = Role.Tab, onClick = onClick)
            .testTag("shell-tab-${tab.name}"), contentAlignment = Alignment.Center) {
            TabMark(tab)
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

/** Resource labels remain accessible even when a compact circle has no visible caption. */
@Composable
private fun TabMark(tab: ShellTab) {
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
