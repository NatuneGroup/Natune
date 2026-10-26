/*
 * Shared port of the SukiSU liquid glass navigation bar, after the baseline SukisuLiquidNavBar.kt. That file follows
 * SukiSU-Ultra's FloatingBottomBar, which adapts compose-miuix-ui's IosLiquidGlassNavigationBar (Apache 2.0). The
 * port keeps the baseline geometry (64 dp bar, 56 dp pill, 4 dp item padding), the rubber band, the drag-to-slide
 * pill, the press spotlight, the squash-and-stretch and the icon pop.
 *
 * Glass is drawn only while the optical policy records liquid glass and a backdrop exists. Otherwise the bar is the
 * solid pill the baseline draws without Liquid Glass. Taps and drags both report through [onSelect], so the shell
 * decides navigate versus re-select from its own back stack.
 */
@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.shared.ui.navigation

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.ShortNavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.settings.NavigationBarStyleKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.ui.glass.GlassPressSpotlight
import moe.rukamori.archivetune.shared.ui.glass.InnerShadow
import moe.rukamori.archivetune.shared.ui.glass.LiquidGlassDragAnimation
import moe.rukamori.archivetune.shared.ui.glass.LiquidGlassRole
import moe.rukamori.archivetune.shared.ui.glass.LocalAnimatorDurationScale
import moe.rukamori.archivetune.shared.ui.glass.LocalLiquidGlassBackdrop
import moe.rukamori.archivetune.shared.ui.glass.LocalOpticalPolicy
import moe.rukamori.archivetune.shared.ui.glass.PlatformBackdrop
import moe.rukamori.archivetune.shared.ui.glass.innerShadow
import moe.rukamori.archivetune.shared.ui.glass.layerBackdrop
import moe.rukamori.archivetune.shared.ui.glass.liquidGlassMaterial
import moe.rukamori.archivetune.shared.ui.glass.rememberGlassSpecularHighlight
import moe.rukamori.archivetune.shared.ui.glass.scaleAwareSpring
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/** The bar's fixed height (SukiSU's 64 dp). The shell reserves exactly this much chrome for the bar, plus its inset. */
val SukisuNavigationBarHeight = 64.dp

/** The persisted navigation bar style that selects this bar. It is the enum name the baseline stores. */
const val SukisuNavigationBarStyle = "SUKISU"

/** True when the persisted navigation bar style selects the SukiSU bar. */
fun PreferenceSnapshot.usesSukisuNavigationBar(): Boolean = this[NavigationBarStyleKey] == SukisuNavigationBarStyle

private val SukisuPillHeight = 56.dp
private val SukisuItemPadding = 4.dp

// The baseline floating footprint: 24 dp sides, 16 dp above the bottom inset, at most 360 dp wide.
private val SukisuBarHorizontalInset = 24.dp
private val SukisuBarBottomInset = 16.dp
private val SukisuBarMaxWidth = 360.dp

// The bar and the pill are lit from different directions, so each specular light gets its own bias.
private const val SukisuBarHighlightExtraDegrees = -45f
private const val SukisuPillHighlightExtraDegrees = 90f

internal val ShellTab.iconActive: DrawableResource
    get() = when (this) {
        ShellTab.Home -> Res.drawable.m4_icon_home_filled
        ShellTab.Search -> Res.drawable.m4_icon_search
        ShellTab.Library -> Res.drawable.m4_icon_library_filled
    }

internal val ShellTab.iconInactive: DrawableResource
    get() = when (this) {
        ShellTab.Home -> Res.drawable.m4_icon_home_outlined
        ShellTab.Search -> Res.drawable.m4_icon_search
        ShellTab.Library -> Res.drawable.m4_icon_library_outlined
    }

/**
 * The SukiSU bar for the shell's bottom slot. [items] keeps the shell's tab order, [selected] reads the current tab and
 * [onSelect] receives every tap or completed drag. [backdrop] defaults to the page backdrop the root publishes. Without
 * glass the bar draws a solid pill, so it stays usable with Liquid Glass off or occluded.
 */
@Composable
fun SukisuNavigationBar(
    items: List<ShellTab>,
    selected: (ShellTab) -> Boolean,
    onSelect: (ShellTab) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: PlatformBackdrop? = LocalLiquidGlassBackdrop.current,
    hideLabels: Boolean = false,
) {
    if (items.isEmpty()) return
    val policy = LocalOpticalPolicy.current
    // Glass needs the recorded liquid glass gate and a backdrop, as the baseline's eligibility check does.
    val glassActive = policy.recordLiquidGlass && backdrop != null
    // Motion folds in the disable-animations switch, reduced motion and an animator scale of zero, so the squash, press
    // and pop follow it. Below 1x the pop's spring reshapes itself (scaleAwareSpring) instead of overshooting.
    val animatorScale = LocalAnimatorDurationScale.current
    val motion = policy.motion && animatorScale > 0f
    val highlightMotion = glassActive && motion

    // The app's own theme decides the glyph colour, not the system's: a forced light theme on a dark system would
    // otherwise put white glyphs on a light backdrop.
    val glassIsNight = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val pillShape = CircleShape
    val accentColor = MaterialTheme.colorScheme.primary
    val tabContentColor = MaterialTheme.colorScheme.onSurface
    val containerColor =
        if (glassActive) {
            MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.4f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        }

    val tabsBackdrop = rememberLayerBackdrop()
    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val animationScope = rememberCoroutineScope()

    var tabWidthPx by remember { mutableFloatStateOf(0f) }
    var totalWidthPx by remember { mutableFloatStateOf(0f) }

    // Rubber band: while a drag pulls the pill past the first or last tab, the whole row leans a few pixels with the
    // overdrag and eases back on release.
    val offsetAnimation = remember { Animatable(0f) }
    val rubberBandPx = with(density) { 4.dp.toPx() }
    val panelOffset by remember(rubberBandPx) {
        derivedStateOf {
            if (totalWidthPx == 0f) {
                0f
            } else {
                val fraction = (offsetAnimation.value / totalWidthPx).fastCoerceIn(-1f, 1f)
                rubberBandPx * fraction.sign * EaseOut.transform(abs(fraction))
            }
        }
    }

    val tabsCount = items.size
    var currentIndex by remember { mutableIntStateOf(0) }
    val itemsUpdated = rememberUpdatedState(items)
    val selectedUpdated = rememberUpdatedState(selected)
    val onSelectUpdated = rememberUpdatedState(onSelect)

    fun indexAt(positionX: Float): Int {
        if (tabWidthPx == 0f) return currentIndex
        val horizontalPaddingPx = with(density) { SukisuItemPadding.toPx() }
        val logicalX = if (isLtr) positionX else totalWidthPx - positionX
        return ((logicalX - horizontalPaddingPx) / tabWidthPx)
            .toInt()
            .coerceIn(0, tabsCount - 1)
    }

    val dampedDragAnimation =
        remember(animationScope, tabsCount, density, isLtr) {
            LiquidGlassDragAnimation(
                animationScope = animationScope,
                initialValue = currentIndex.toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
                canDrag = { offset -> offset.x in 0f..totalWidthPx },
                onDragStarted = { position ->
                    updateValue(indexAt(position.x).toFloat())
                },
                onDragStopped = {
                    val targetIndex = targetValue.roundToInt().coerceIn(0, tabsCount - 1)
                    // A drag that releases on a new tab navigates. Releasing on the tab the bar already shows is not a
                    // re-select, so it does not scroll to top.
                    if (currentIndex != targetIndex) {
                        currentIndex = targetIndex
                        itemsUpdated.value.getOrNull(targetIndex)?.let { onSelectUpdated.value(it) }
                    }
                    updateValue(targetIndex.toFloat())
                    animationScope.launch {
                        offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                    }
                },
                onDrag = { _, dragAmount ->
                    if (tabWidthPx > 0f && dragAmount.x != 0f) {
                        updateValue(
                            (targetValue + dragAmount.x / tabWidthPx * if (isLtr) 1f else -1f)
                                .coerceIn(0f, (tabsCount - 1).toFloat()),
                        )
                        animationScope.launch {
                            offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                        }
                    }
                },
            )
        }

    fun activateTab(index: Int) {
        if (index !in 0 until tabsCount) return
        // Every tap is reported. The shell's handler decides navigate versus re-select from the live back stack, so
        // the bar never guesses from its own pill.
        currentIndex = index
        onSelectUpdated.value(itemsUpdated.value[index])
        dampedDragAnimation.animateToValue(index.toFloat())
    }

    // The pill follows the shell's selection: the optimistic tap, and any external navigation (rail, compact circles,
    // deep links). The first emission places the pill without animating.
    LaunchedEffect(dampedDragAnimation) {
        var initial = true
        snapshotFlow {
            itemsUpdated.value.indexOfFirst { selectedUpdated.value(it) }
        }.collect { idx ->
            if (idx >= 0 && idx < tabsCount && idx != currentIndex) {
                currentIndex = idx
                if (initial) {
                    dampedDragAnimation.updateValue(idx.toFloat())
                } else {
                    dampedDragAnimation.animateToValue(idx.toFloat())
                }
            }
            initial = false
        }
    }

    // The press spotlight follows the pill. Its position reads the live pill value through the drag animation, so the
    // lambda stays current without rebuilding the spotlight on every frame.
    val interactiveHighlight =
        remember(animationScope, dampedDragAnimation, isLtr, highlightMotion) {
            if (highlightMotion) {
                GlassPressSpotlight(
                    animationScope = animationScope,
                    position = { size, _ ->
                        Offset(
                            if (isLtr) {
                                (dampedDragAnimation.value + 0.5f) * tabWidthPx + panelOffset
                            } else {
                                size.width - (dampedDragAnimation.value + 0.5f) * tabWidthPx + panelOffset
                            },
                            size.height / 2f,
                        )
                    },
                )
            } else {
                null
            }
        }

    DisposableEffect(interactiveHighlight) {
        onDispose { interactiveHighlight?.cancel() }
    }

    val barHighlight by rememberGlassSpecularHighlight(SukisuBarHighlightExtraDegrees)
    val pillHighlight by rememberGlassSpecularHighlight(SukisuPillHighlightExtraDegrees)

    val combinedBackdrop =
        if (glassActive && backdrop != null) {
            rememberCombinedBackdrop(backdrop, tabsBackdrop)
        } else {
            null
        }

    val tabScaleProvider =
        remember(dampedDragAnimation, highlightMotion) {
            { lerp(1f, 1.2f, if (highlightMotion) dampedDragAnimation.pressProgress else 0f) }
        }

    val glassSelectedColor =
        if (glassIsNight) Color.White else MaterialTheme.colorScheme.onSurface
    val glassUnselectedColor =
        if (glassIsNight) {
            Color.White
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    val itemColors =
        ShortNavigationBarItemDefaults.colors(
            selectedIndicatorColor = Color.Transparent,
            selectedIconColor = glassSelectedColor,
            selectedTextColor = glassSelectedColor,
            unselectedIconColor = glassUnselectedColor,
            unselectedTextColor = glassUnselectedColor,
        )
    // The item under the pill paints nothing: the pill surface itself is the indicator there.
    val transparentItemColors =
        ShortNavigationBarItemDefaults.colors(
            selectedIndicatorColor = Color.Transparent,
            selectedIconColor = Color.Transparent,
            selectedTextColor = Color.Transparent,
            unselectedIconColor = Color.Transparent,
            unselectedTextColor = Color.Transparent,
        )
    // The hidden ghost row is recorded into [tabsBackdrop], so the sliding pill refracts accent-tinted tab content.
    // It is invisible (alpha 0, no semantics) and its items are disabled, so it never takes a tap.
    val ghostItemColors =
        ShortNavigationBarItemDefaults.colors(
            selectedIndicatorColor = Color.Transparent,
            selectedIconColor = accentColor,
            selectedTextColor = accentColor,
            unselectedIconColor = accentColor,
            unselectedTextColor = accentColor,
            disabledIconColor = accentColor,
            disabledTextColor = accentColor,
        )
    val displayIndex by remember(dampedDragAnimation) {
        derivedStateOf {
            dampedDragAnimation.value.roundToInt().coerceIn(0, items.lastIndex)
        }
    }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
                .padding(
                    start = SukisuBarHorizontalInset,
                    end = SukisuBarHorizontalInset,
                    bottom = SukisuBarBottomInset,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .widthIn(max = SukisuBarMaxWidth)
                    .fillMaxWidth()
                    .height(SukisuNavigationBarHeight),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (glassActive) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clearAndSetSemantics {}
                            .alpha(0f)
                            .layerBackdrop(tabsBackdrop)
                            .graphicsLayer { translationX = panelOffset }
                            .liquidGlassMaterial(
                                backdrop = backdrop,
                                shape = pillShape,
                                role = LiquidGlassRole.NavigationBar,
                                onDrawSurface = { drawRect(containerColor) },
                            )
                            .height(SukisuPillHeight)
                            .padding(horizontal = SukisuItemPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items.forEachIndexed { index, tab ->
                        ShortNavigationBarItem(
                            selected = index == currentIndex,
                            onClick = {},
                            enabled = false,
                            colors = ghostItemColors,
                            icon = {
                                Icon(
                                    painter = painterResource(tab.iconActive),
                                    contentDescription = null,
                                )
                            },
                            label = if (hideLabels) null else {
                                { SukisuItemLabel(tab, glassIsNight) }
                            },
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                        )
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coords ->
                        totalWidthPx = coords.size.width.toFloat()
                        val contentWidthPx =
                            totalWidthPx - with(density) { (SukisuItemPadding * 2).toPx() }
                        tabWidthPx = (contentWidthPx / tabsCount).coerceAtLeast(0f)
                    }
                    .graphicsLayer { translationX = panelOffset }
                    .then(
                        if (glassActive) {
                            Modifier.liquidGlassMaterial(
                                backdrop = backdrop,
                                shape = pillShape,
                                role = LiquidGlassRole.NavigationBar,
                                highlight = { barHighlight.copy(alpha = 0.75f) },
                                layerBlock = {
                                    val width = size.width.coerceAtLeast(1f)
                                    val s =
                                        lerp(
                                            1f,
                                            1f + 16.dp.toPx() / width,
                                            if (highlightMotion) dampedDragAnimation.pressProgress else 0f,
                                        )
                                    scaleX = s
                                    scaleY = s
                                },
                                onDrawSurface = { drawRect(containerColor) },
                            )
                        } else {
                            Modifier.background(containerColor, pillShape)
                        },
                    )
                    .then(
                        if (interactiveHighlight != null) {
                            interactiveHighlight.modifier.then(interactiveHighlight.gestureModifier)
                        } else {
                            Modifier
                        },
                    )
                    .then(dampedDragAnimation.modifier)
                    .height(SukisuNavigationBarHeight)
                    .padding(SukisuItemPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompositionLocalProvider(
                    LocalContentColor provides tabContentColor,
                ) {
                    items.forEachIndexed { index, tab ->
                        val isItemSelected = selected(tab)
                        // A velocity kick instead of a snap to 80%: a rapid re-tap retargets the pop mid-flight.
                        val iconScale = remember(tab) { Animatable(1f) }
                        LaunchedEffect(isItemSelected, motion, animatorScale) {
                            if (!motion) {
                                iconScale.snapTo(1f)
                            } else if (isItemSelected) {
                                iconScale.animateTo(
                                    targetValue = 1f,
                                    animationSpec =
                                        scaleAwareSpring(
                                            scale = animatorScale,
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMediumLow,
                                        ),
                                    initialVelocity = -4f,
                                )
                            } else {
                                iconScale.snapTo(1f)
                            }
                        }

                        ShortNavigationBarItem(
                            selected = isItemSelected,
                            onClick = { activateTab(index) },
                            colors =
                                if (combinedBackdrop != null && index == displayIndex) {
                                    transparentItemColors
                                } else {
                                    itemColors
                                },
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .graphicsLayer {
                                        val scale = tabScaleProvider()
                                        scaleX = scale
                                        scaleY = scale
                                    }
                                    .testTag("shell-tab-${tab.name}"),
                            icon = {
                                Crossfade(
                                    targetState = isItemSelected,
                                    animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                                    label = "sukisuNavItemIcon",
                                ) { showActive ->
                                    Icon(
                                        painter =
                                            painterResource(
                                                if (showActive) tab.iconActive else tab.iconInactive,
                                            ),
                                        contentDescription = null,
                                        modifier =
                                            Modifier.graphicsLayer {
                                                scaleX = iconScale.value
                                                scaleY = iconScale.value
                                            },
                                    )
                                }
                            },
                            label = if (hideLabels) null else {
                                { SukisuItemLabel(tab, glassIsNight) }
                            },
                        )
                    }
                }
            }

            if (tabWidthPx > 0f) {
                val tabWidthDp = with(density) { tabWidthPx.toDp() }
                if (combinedBackdrop != null) {
                    Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .padding(horizontal = SukisuItemPadding)
                            .graphicsLayer {
                                val progressOffset = dampedDragAnimation.value * tabWidthPx
                                translationX =
                                    if (isLtr) {
                                        progressOffset + panelOffset
                                    } else {
                                        -progressOffset + panelOffset
                                    }
                            }
                            .liquidGlassMaterial(
                                backdrop = combinedBackdrop,
                                shape = pillShape,
                                role = LiquidGlassRole.SelectionLens,
                                progress = { if (highlightMotion) dampedDragAnimation.pressProgress else 0f },
                                highlight = {
                                    pillHighlight.copy(alpha = if (highlightMotion) dampedDragAnimation.pressProgress else 0f)
                                },
                                layerBlock = {
                                    scaleX = if (highlightMotion) dampedDragAnimation.scaleX else 1f
                                    scaleY = if (highlightMotion) dampedDragAnimation.scaleY else 1f
                                    // Velocity squash and stretch: the pill elongates along its travel and contracts
                                    // across it, driven by the drag animation's velocity.
                                    val velocity = if (highlightMotion) dampedDragAnimation.velocity / 10f else 0f
                                    scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                                    scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                                },
                                onDrawSurface = {
                                    val progress = if (highlightMotion) dampedDragAnimation.pressProgress else 0f
                                    drawRect(
                                        color =
                                            if (!glassIsNight) {
                                                Color.Black.copy(alpha = 0.1f)
                                            } else {
                                                Color.White.copy(alpha = 0.1f)
                                            },
                                        alpha = 1f - progress,
                                    )
                                    drawRect(Color.Black.copy(alpha = 0.03f * progress))
                                },
                            )
                            .innerShadow(shape = pillShape) {
                                val progress = if (highlightMotion) dampedDragAnimation.pressProgress else 0f
                                if (progress > 0f) {
                                    InnerShadow(
                                        radius = 8.dp * progress,
                                        color = Color.Black.copy(alpha = 0.15f),
                                        alpha = progress,
                                    )
                                } else {
                                    null
                                }
                            }
                            .height(SukisuPillHeight)
                            .width(tabWidthDp),
                    )
                } else {
                    Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .padding(horizontal = SukisuItemPadding)
                            .graphicsLayer {
                                val progressOffset = dampedDragAnimation.value * tabWidthPx
                                translationX =
                                    if (isLtr) {
                                        progressOffset + panelOffset
                                    } else {
                                        -progressOffset + panelOffset
                                    }
                            }
                            .clip(pillShape)
                            .background(accentColor.copy(alpha = 0.15f), pillShape)
                            .height(SukisuPillHeight)
                            .width(tabWidthDp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SukisuItemLabel(tab: ShellTab, glassIsNight: Boolean) {
    val style =
        if (glassIsNight) {
            // White labels over a bright patch of artwork need a soft dark halo.
            LocalTextStyle.current.copy(
                shadow = Shadow(color = Color.Black.copy(alpha = 0.8f), blurRadius = 10f),
            )
        } else {
            LocalTextStyle.current
        }
    Text(
        text = stringResource(tab.label),
        maxLines = 1,
        modifier = Modifier.offset(y = (-4).dp),
        fontWeight = FontWeight.Normal,
        style = style,
    )
}
