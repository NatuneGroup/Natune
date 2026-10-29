package moe.rukamori.archivetune.shared.ui.navigation

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarArrangement
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.ShortNavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.settings.NavigationBarStyle
import moe.rukamori.archivetune.shared.domain.settings.CompactControlsStockColorKey
import moe.rukamori.archivetune.shared.domain.settings.DisableAnimationsKey
import moe.rukamori.archivetune.shared.domain.settings.LiquidGlassNavBarEnabledKey
import moe.rukamori.archivetune.shared.domain.settings.HideNavigationBarLabelsKey
import moe.rukamori.archivetune.shared.domain.settings.NavigationBarHeightKey
import moe.rukamori.archivetune.shared.domain.settings.NavigationBarLabelSpacingKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.ui.glass.InnerShadow
import moe.rukamori.archivetune.shared.ui.glass.LiquidGlassDragAnimation
import moe.rukamori.archivetune.shared.ui.glass.LiquidGlassRole
import moe.rukamori.archivetune.shared.ui.glass.LocalAnimatorDurationScale
import moe.rukamori.archivetune.shared.ui.glass.LocalLiquidGlassBackdrop
import moe.rukamori.archivetune.shared.ui.glass.LocalNavigationHaze
import moe.rukamori.archivetune.shared.ui.glass.LocalOpticalPolicy
import moe.rukamori.archivetune.shared.ui.glass.frostedNavigation
import moe.rukamori.archivetune.shared.ui.glass.glassGlowOverlay
import moe.rukamori.archivetune.shared.ui.glass.innerShadow
import moe.rukamori.archivetune.shared.ui.glass.liquidGlassMaterial
import moe.rukamori.archivetune.shared.ui.glass.scaleAwareSpring
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private val SukiSUBarHeight = 64.dp
private val SukiSUItemPadding = 4.dp

// The sliding pill wraps just the icon (like the stock indicator), so the label sits below it,
// outside the bubble. These are the standard Material3 active-indicator dimensions.
private val NavigationIndicatorWidth = 56.dp
private val NavigationIndicatorHeight = 32.dp

// The floating/large variant gets a bigger capsule that can still hold icon + label.
private val FloatingNavigationIndicatorWidth = 64.dp
private val FloatingNavigationIndicatorHeight = 42.dp

// ViewConfiguration.getDoubleTapTimeout() on Android; there is no platform tap query in commonMain.
private const val DoubleTapTimeoutMs = 300L

/**
 * The labelled bottom bar, ported from the baseline FloatingNavigationToolbar. One composable covers every stored
 * style except SUKISU (its own bar): DEFAULT docks to the screen, FLOATING/YUMA_PLAYER/APPLE_MUSIC draw the capsule.
 * The sliding indicator pill wraps the selected item's icon and the icon pops on every switch; with a liquid-glass
 * backdrop the bar goes transparent and a measured glass lens carries the active glyph instead.
 *
 * Where the baseline records the page into a GraphicsLayer for the frost, this port samples the shared haze source
 * through [frostedNavigation] — the same frosted material the rail and compact row already draw.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FloatingNavigationToolbar(
    items: List<ShellTab>,
    preferences: PreferenceSnapshot,
    pureBlack: Boolean,
    modifier: Modifier = Modifier,
    miniPlayerProximityProvider: () -> Float = { 0f },
    style: NavigationBarStyle = NavigationBarStyle.DEFAULT,
    isSelected: (ShellTab) -> Boolean,
    onItemClick: (ShellTab) -> Unit,
    onSearchItemDoubleClick: (() -> Unit)? = null,
) {
    if (items.isEmpty()) return
    val isFloating = style == NavigationBarStyle.FLOATING
    val isYumaPlayer = style == NavigationBarStyle.YUMA_PLAYER
    val isAppleMusic = style == NavigationBarStyle.APPLE_MUSIC
    // Navigation bar customization. Read directly here so the toolbar picks up the user's
    // tuning without the call site needing to thread extra params.
    val navBarHeightMultiplier = preferences[NavigationBarHeightKey, NAVIGATION_BAR_HEIGHT_DEFAULT]
    val navBarLabelSpacing = preferences[NavigationBarLabelSpacingKey, NAVIGATION_BAR_LABEL_SPACING_DEFAULT]
    val frostedBlur = preferences.frostedNavigationEnabled()
    val tintFrostedBlur = preferences.tintedFrostedNavigation()
    val liquidGlassBackdrop = LocalLiquidGlassBackdrop.current
    val glowStrength = preferences.navigationBarGlowStrength()
    // Either frosted variant enables backdrop blur; the liquid-glass variant draws the lens pill.
    val pill = rememberPillStyle(
        style = style,
        pureBlack = pureBlack,
        frostedBlur = frostedBlur,
        tintFrostedBlur = tintFrostedBlur,
        liquidGlass = LocalOpticalPolicy.current.liquidGlassLook &&
            preferences[LiquidGlassNavBarEnabledKey, false] &&
            !preferences[CompactControlsStockColorKey, false],
        frostedBackdropAvailable = LocalNavigationHaze.current != null,
        liquidGlassBackdropAvailable = liquidGlassBackdrop != null,
        preferences = preferences,
    )
    val canBlurBackdrop = pill.backdrop == PillBackdrop.FROSTED
    val canLiquidGlass = pill.backdrop == PillBackdrop.LIQUID_GLASS && liquidGlassBackdrop != null
    val resolvedBarHeight =
        if (canLiquidGlass) SukiSUBarHeight else NavigationBarHeight * navBarHeightMultiplier
    // SukiSU-Ultra: when the Liquid Glass nav bar is active, the items Row uses
    // 4.dp padding on ALL sides (matching SukiSU's Row.padding(4.dp)). The non-
    // Liquid-Glass variants keep the original 8.dp vertical-only padding.
    val itemVerticalPadding =
        if (canLiquidGlass) SukiSUItemPadding else NavigationItemVerticalPadding
    val itemHorizontalPadding = if (canLiquidGlass) SukiSUItemPadding else 0.dp
    // Same per-frame discipline as the mini player: the provider fires every sheet-drag
    // frame, so read it through derivedStateOf and remember the shape.
    val miniPlayerProximity by remember(miniPlayerProximityProvider) {
        derivedStateOf { miniPlayerProximityProvider().coerceIn(0f, 1f) }
    }
    val navigationShape =
        remember(pill, miniPlayerProximity) { pill.shape(PillRole.NAVIGATION_BAR, miniPlayerProximity) }
    val tintedContent = pill.tintedContent
    val navigationContainerColor = pill.containerColor
    val disableAnimations = preferences[DisableAnimationsKey, false]
    // The pill slide and the icon pop run on the animator duration scale: an Animatable in a
    // LaunchedEffect already shortens with the scale, and the scale-aware spring reshapes itself
    // below 1x so the bounce never has to be crammed into the frames that are left. Snap at 0.
    val animationScale = LocalAnimatorDurationScale.current
    val glassMotion = !disableAnimations && animationScale > 0f && LocalOpticalPolicy.current.motion
    // Apple Music's tab bar is icon + label by definition, so the style wins over the global
    // "hide labels" preference rather than silently losing its own look to it.
    val hideNavigationLabels =
        preferences[HideNavigationBarLabelsKey, false] && !isYumaPlayer && !isAppleMusic
    val density = LocalDensity.current

    val indicatorColor =
        when {
            // Liquid Glass: transparent indicator — the glass pill itself is the
            // indicator. Setting this to transparent avoids a theme-tinted blob
            // behind the active item.
            canLiquidGlass -> Color.Transparent
            // Apple Music's active tab sits in a tinted pill: the reference shows a solid
            // primary-coloured capsule with the glyph knocked out of it and the label in the accent.
            isYumaPlayer -> MaterialTheme.colorScheme.primary
            // Apple Music: the active tab gets a faint neutral capsule and the accent goes to the
            // glyph and label only, so the bar itself stays monochrome.
            isAppleMusic && pureBlack -> Color.White.copy(alpha = 0.16f)
            isAppleMusic -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
            tintFrostedBlur && !isFloating -> tintedContent.copy(alpha = 0.18f)
            isFloating -> MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
            pureBlack -> Color.White.copy(alpha = 0.16f)
            else -> MaterialTheme.colorScheme.secondaryContainer
        }
    // The app's own theme, not the system's: a forced light theme on a dark system would otherwise
    // put white glyphs on a light backdrop.
    val glassIsNight = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val glassContentColor = if (glassIsNight) Color.White else MaterialTheme.colorScheme.onSurface
    // White labels over a bright patch of artwork need a soft dark halo to stay readable.
    val glassLabelStyle =
        if (glassIsNight) {
            LocalTextStyle.current.copy(shadow = Shadow(color = Color.Black.copy(alpha = 0.8f), blurRadius = 10f))
        } else {
            LocalTextStyle.current
        }
    val glassUnselectedContentColor = if (glassIsNight) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    val largeIndicator = isFloating || isYumaPlayer || isAppleMusic
    val indicatorWidth = if (largeIndicator) FloatingNavigationIndicatorWidth else NavigationIndicatorWidth
    val indicatorHeight = if (largeIndicator) FloatingNavigationIndicatorHeight else NavigationIndicatorHeight

    // The built-in per-item indicator just fades in place; hide it so our single pill can slide
    // between items instead. On pure-black we also pin the icon/label colors for contrast.
    val itemColors =
        when {
            // White-on-glass vanishes over a light backdrop, so light themes take the scheme's
            // on-surface tones instead.
            canLiquidGlass ->
                ShortNavigationBarItemDefaults.colors(
                    selectedIndicatorColor = Color.Transparent,
                    selectedIconColor = glassContentColor,
                    selectedTextColor = glassContentColor,
                    unselectedIconColor = glassUnselectedContentColor,
                    unselectedTextColor = glassUnselectedContentColor,
                )
            isFloating || isAppleMusic ->
                ShortNavigationBarItemDefaults.colors(
                    selectedIndicatorColor = Color.Transparent,
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor =
                        if (pureBlack) Color.White.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor =
                        if (pureBlack) Color.White.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            isYumaPlayer ->
                ShortNavigationBarItemDefaults.colors(
                    // The pill is drawn behind the item (see indicatorColor), so the item's own
                    // indicator stays transparent and the glyph is knocked out of the accent.
                    selectedIndicatorColor = Color.Transparent,
                    selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            // Checked before pureBlack so an AMOLED + tinted bar keeps its tint.
            tintFrostedBlur ->
                ShortNavigationBarItemDefaults.colors(
                    selectedIndicatorColor = Color.Transparent,
                    selectedIconColor = tintedContent,
                    selectedTextColor = tintedContent,
                    unselectedIconColor = tintedContent.copy(alpha = 0.7f),
                    unselectedTextColor = tintedContent.copy(alpha = 0.7f),
                )
            pureBlack ->
                ShortNavigationBarItemDefaults.colors(
                    selectedIndicatorColor = Color.Transparent,
                    selectedIconColor = Color.White,
                    selectedTextColor = Color.White,
                    unselectedIconColor = Color.White.copy(alpha = 0.6f),
                    unselectedTextColor = Color.White.copy(alpha = 0.6f),
                )
            else -> ShortNavigationBarItemDefaults.colors(selectedIndicatorColor = Color.Transparent)
        }

    val selectedIndex = items.indexOfFirst { isSelected(it) }

    val liquidGlassTransparentColors =
        ShortNavigationBarItemDefaults.colors(
            selectedIndicatorColor = Color.Transparent,
            selectedIconColor = Color.Transparent,
            selectedTextColor = Color.Transparent,
            unselectedIconColor = Color.Transparent,
            unselectedTextColor = Color.Transparent,
        )

    // Measured center of each item's icon (root-space) and the row container's top-left, so the pill
    // can slide to the exact icon position regardless of layout/insets. Only the icon is tracked so
    // the bubble hugs the icon and leaves the text label outside of it.
    val iconCenters = remember { mutableStateMapOf<Int, Offset>() }
    val itemBounds = remember { mutableStateMapOf<Int, Rect>() }
    var containerPos by remember { mutableStateOf(Offset.Zero) }

    val indicatorX = remember { Animatable(0f) }
    var indicatorY by remember { mutableFloatStateOf(0f) }
    var indicatorPlaced by remember { mutableStateOf(false) }
    // Liquid Glass pill size, computed from the selected item's full bounds
    // (icon + label). Zero until the first item is measured.
    var liquidGlassPillWidth by remember { mutableStateOf(0.dp) }
    var liquidGlassPillHeight by remember { mutableStateOf(0.dp) }

    val animationScope = rememberCoroutineScope()
    val isLtr = true // the shared shell doesn't currently support RTL layout flipping
    var tabWidthPx by remember { mutableFloatStateOf(0f) }
    var totalWidthPx by remember { mutableFloatStateOf(0f) }
    // The items Row is centered inside the container Box (via the Box's
    // `contentAlignment = Alignment.Center`). To position the sliding pill at
    // `dampedDragAnimation.value × tabWidthPx`, we need to know the items
    // Row's left edge within the container Box — tracked here.
    var itemsRowLeftInContainer by remember { mutableFloatStateOf(0f) }
    var itemsRowTopInContainer by remember { mutableFloatStateOf(0f) }
    val rubberBandPx = with(density) { 4.dp.toPx() }
    // Accumulated raw drag delta (unclamped). Used as the source for the
    // clamped rubber-band `panelOffset` — at most ±4dp, with an EaseOut curve
    // so it ramps up quickly then slows down (the iOS liquid-glass feel).
    val rubberBandOffset = remember { Animatable(0f) }
    val panelOffset by remember(rubberBandPx, totalWidthPx) {
        derivedStateOf {
            if (totalWidthPx == 0f) {
                0f
            } else {
                val fraction = (rubberBandOffset.value / totalWidthPx).fastCoerceIn(-1f, 1f)
                rubberBandPx * fraction.sign * EaseOut.transform(abs(fraction))
            }
        }
    }

    val tabsCount = items.size
    val selectedIndexUpdated = rememberUpdatedState(selectedIndex)
    val onItemClickUpdated = rememberUpdatedState(onItemClick)
    val dampedDragAnimation =
        remember(tabsCount, canLiquidGlass) {
            if (canLiquidGlass && tabsCount > 0) {
                // Total horizontal travel of the current gesture, reset on every touch down.
                // A pure tap never moves the pill, so the selection change (the click navigates)
                // is what animates it — animating here too pressed and re-released the glass a
                // second and third time per tap.
                var dragTravel = 0f
                LiquidGlassDragAnimation(
                    animationScope = animationScope,
                    initialValue = selectedIndex.coerceIn(0, tabsCount - 1).toFloat(),
                    valueRange = 0f..(tabsCount - 1).toFloat(),
                    visibilityThreshold = 0.001f,
                    initialScale = 1f,
                    pressedScale = 78f / 56f, // SukiSU: 56dp tab → 78dp pressed pill
                    canDrag = { offset ->
                        // Only accept drags that begin inside the bar's content area
                        // (totalWidthPx × bar height). This prevents the drag from
                        // triggering when the user touches outside the items Row.
                        if (totalWidthPx == 0f) return@LiquidGlassDragAnimation false
                        offset.x in 0f..totalWidthPx && offset.y >= 0f
                    },
                    onDragStarted = {
                        dragTravel = 0f
                    },
                    onDragStopped = {
                        val targetIndex = targetValue.roundToInt().coerceIn(0, tabsCount - 1)
                        animationScope.launch {
                            rubberBandOffset.animateTo(0f, spring(1f, 300f, 0.5f))
                        }
                        // Read the LATEST selectedIndex via the State wrapper —
                        // otherwise we'd compare against the stale value captured
                        // at animation-creation time.
                        if (targetIndex != selectedIndexUpdated.value) {
                            onItemClickUpdated.value(items[targetIndex])
                        }
                        if (dragTravel != 0f) {
                            animateToValue(targetIndex.toFloat())
                        }
                    },
                    onDrag = { _, dragAmount ->
                        dragTravel += abs(dragAmount.x)
                        if (tabWidthPx > 0f) {
                            updateValue(
                                (targetValue + dragAmount.x / tabWidthPx * if (isLtr) 1f else -1f)
                                    .fastCoerceIn(0f, (tabsCount - 1).toFloat()),
                            )
                            animationScope.launch {
                                rubberBandOffset.snapTo(rubberBandOffset.value + dragAmount.x)
                            }
                        }
                    },
                )
            } else {
                null
            }
        }

    val displayIndex by remember(dampedDragAnimation) {
        derivedStateOf {
            dampedDragAnimation?.value?.roundToInt()?.coerceIn(0, items.lastIndex) ?: selectedIndex
        }
    }

    val isSelectedTracker = rememberUpdatedState(isSelected)
    val itemsTracker = rememberUpdatedState(items)
    LaunchedEffect(dampedDragAnimation) {
        val anim = dampedDragAnimation ?: return@LaunchedEffect
        snapshotFlow {
            val itemsList = itemsTracker.value
            val sel = isSelectedTracker.value
            itemsList.indexOfFirst { sel(it) }
        }.drop(1).collect { idx ->
            // `items.size` is captured at composition time. It's stable for the
            // lifetime of `dampedDragAnimation` (which is `remember(tabsCount, ...)`
            // keyed on `items.size`), so this check is safe.
            if (idx in 0..(items.size - 1)) {
                // Ignore a selection update the pill is already animating to (a drag release or
                // an earlier tap already targeted this index) — animating again would press and
                // re-release the glass a second time for the same tap.
                if (idx != anim.targetValue.roundToInt()) {
                    anim.animateToValue(idx.toFloat())
                }
            }
        }
    }

    val selectedCenter = if (selectedIndex >= 0) iconCenters[selectedIndex] else null
    val selectedItemBounds = if (selectedIndex >= 0) itemBounds[selectedIndex] else null
    // A narrow bar (or a big font scale) can leave a tab slimmer than the large indicator, which
    // then spills past the bar's edge; keep the capsule inside its own tab.
    val fittedIndicatorWidth =
        if (tabWidthPx > 0f) {
            minOf(indicatorWidth, with(density) { tabWidthPx.toDp() } - 8.dp).coerceAtLeast(indicatorHeight)
        } else {
            indicatorWidth
        }
    LaunchedEffect(selectedIndex, selectedCenter, selectedItemBounds, containerPos, disableAnimations, animationScale, fittedIndicatorWidth, indicatorHeight, canLiquidGlass, tabWidthPx, itemsRowTopInContainer, itemVerticalPadding) {
        if (canLiquidGlass) {
            if (tabWidthPx <= 0f) return@LaunchedEffect
            liquidGlassPillWidth = with(density) { tabWidthPx.toDp() }
            liquidGlassPillHeight = SukiSUBarHeight - SukiSUItemPadding * 2 // 64 - 8 = 56.dp
            // Position the pill at the items Row's content area top (which is
            // `itemVerticalPadding` below the bar's top edge).
            indicatorY = itemsRowTopInContainer + with(density) { itemVerticalPadding.toPx() }
            indicatorPlaced = true
        } else {
            // Default / frosted / floating: pill wraps only the icon (label
            // stays outside). This is the original behavior.
            val center = selectedCenter ?: return@LaunchedEffect
            val widthPx = with(density) { fittedIndicatorWidth.toPx() }
            val heightPx = with(density) { indicatorHeight.toPx() }
            val targetX = (center.x - containerPos.x) - widthPx / 2f
            indicatorY = (center.y - containerPos.y) - heightPx / 2f
            val firstPlacement = !indicatorPlaced
            if (disableAnimations || firstPlacement || animationScale <= 0f) {
                indicatorX.snapTo(targetX)
                indicatorPlaced = true
            } else {
                indicatorX.animateTo(
                    targetValue = targetX,
                    animationSpec =
                        scaleAwareSpring(
                            scale = animationScale,
                            dampingRatio = 0.72f,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                )
            }
        }
    }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal)),
        contentAlignment = Alignment.Center,
    ) {
        var barPositionInRoot by remember { mutableStateOf(Offset.Zero) }
        Box(
            modifier =
                Modifier
                    .widthIn(max = pill.maxWidth)
                    .fillMaxWidth(pill.widthFraction)
                    .height(resolvedBarHeight),
            contentAlignment = Alignment.CenterStart,
        ) {
            Surface(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .onGloballyPositioned {
                            barPositionInRoot = it.positionInRoot()
                        }
                        .graphicsLayer {
                            if (canLiquidGlass) {
                                translationX = panelOffset
                            }
                        }
                        .then(
                            when {
                                canLiquidGlass && liquidGlassBackdrop != null ->
                                    Modifier.liquidGlassMaterial(
                                        backdrop = liquidGlassBackdrop,
                                        role = LiquidGlassRole.NavigationBar,
                                        shape = navigationShape,
                                        interactive = false,
                                        baseColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    )
                                canBlurBackdrop -> Modifier.clip(navigationShape).frostedNavigation(tinted = tintFrostedBlur)
                                else -> Modifier
                            },
                        ).glassGlowOverlay(strength = glowStrength, shape = navigationShape),
                shape = navigationShape,
                color = if (canLiquidGlass || canBlurBackdrop) Color.Transparent else navigationContainerColor,
                border = pill.border,
                tonalElevation = if (canLiquidGlass || canBlurBackdrop) 0.dp else pill.tonalElevation,
                shadowElevation = if (canLiquidGlass || canBlurBackdrop) 0.dp else pill.shadowElevation,
            ) {
                val transparentRipple = androidx.compose.material3.ripple(color = Color.Transparent)
                CompositionLocalProvider(
                    LocalIndication provides transparentRipple,
                ) {
                    ShortNavigationBar(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = Color.Transparent,
                        contentColor =
                            when {
                                tintFrostedBlur -> tintedContent
                                pureBlack -> Color.White
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        windowInsets = WindowInsets(0, 0, 0, 0),
                        arrangement = ShortNavigationBarArrangement.EqualWeight,
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .onGloballyPositioned { containerPos = it.positionInRoot() },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selectedIndex >= 0 && indicatorPlaced && !canLiquidGlass) {
                                val pillWidth = fittedIndicatorWidth
                                val pillHeight = indicatorHeight
                                if (pillWidth > 0.dp && pillHeight > 0.dp) {
                                    val pillShape = RoundedCornerShape(percent = 50)
                                    Box(
                                        modifier =
                                            Modifier
                                                .align(Alignment.TopStart)
                                                .offset {
                                                    IntOffset(
                                                        indicatorX.value.roundToInt(),
                                                        indicatorY.roundToInt(),
                                                    )
                                                }
                                                .width(pillWidth)
                                                .height(pillHeight)
                                                .clip(pillShape)
                                                .background(indicatorColor),
                                    )
                                }
                            }

                            Row(
                                modifier =
                                    Modifier
                                        .widthIn(max = NavigationItemsMaxWidth)
                                        .fillMaxWidth()
                                        .fillMaxHeight()
                                        .padding(
                                            vertical = itemVerticalPadding,
                                            horizontal = itemHorizontalPadding,
                                        )
                                        .onGloballyPositioned { coordinates ->
                                            val rowPosInRoot = coordinates.positionInRoot()
                                            itemsRowLeftInContainer = rowPosInRoot.x - containerPos.x
                                            itemsRowTopInContainer = rowPosInRoot.y - containerPos.y
                                            totalWidthPx = coordinates.size.width.toFloat()
                                            val horizontalPaddingPx = with(density) { itemHorizontalPadding.toPx() }
                                            val contentWidthPx = (totalWidthPx - 2f * horizontalPaddingPx).coerceAtLeast(0f)
                                            tabWidthPx = if (tabsCount > 0) (contentWidthPx / tabsCount).coerceAtLeast(0f) else 0f
                                        }
                                        .then(
                                            if (canLiquidGlass && dampedDragAnimation != null) {
                                                dampedDragAnimation.modifier
                                            } else {
                                                Modifier
                                            },
                                        ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                items.forEachIndexed { index, tab ->
                                    val selected = isSelected(tab)
                                    // Tactile "pop": when an item becomes selected, its icon kicks
                                    // downward with velocity and springs back past 100%, giving the bottom
                                    // pill a lively bounce on every tab switch (skipped when animations are
                                    // off). The kick replaces the old snap-to-80%, which made every rapid
                                    // tap jump the icon back to 80% instead of retargeting mid-flight.
                                    val iconScale = remember(tab) { Animatable(1f) }
                                    LaunchedEffect(selected, disableAnimations, animationScale) {
                                        if (disableAnimations || animationScale <= 0f) {
                                            iconScale.snapTo(1f)
                                        } else if (selected) {
                                            iconScale.animateTo(
                                                targetValue = 1f,
                                                animationSpec =
                                                    scaleAwareSpring(
                                                        scale = animationScale,
                                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                                        stiffness = Spring.StiffnessMediumLow,
                                                    ),
                                                initialVelocity = -4f,
                                            )
                                        } else {
                                            iconScale.snapTo(1f)
                                        }
                                    }
                                    val onDoubleClick =
                                        remember(tab, onSearchItemDoubleClick) {
                                            if (tab == ShellTab.Search) onSearchItemDoubleClick else null
                                        }
                                    val lastClickMark = remember(tab) { mutableStateOf<TimeSource.Monotonic.ValueTimeMark?>(null) }
                                    // Not keyed on `selected`: a second tap inside the double-tap window
                                    // must not take the stale branch. The handler decides re-select vs
                                    // navigate at click time from the nav controller and its own
                                    // optimistic selection.
                                    val onClick =
                                        remember(tab, onItemClick, onDoubleClick) {
                                            {
                                                val now = TimeSource.Monotonic.markNow()
                                                val last = lastClickMark.value
                                                val isDoubleClick =
                                                    onDoubleClick != null && last != null &&
                                                        now.minus(last).inWholeMilliseconds <= DoubleTapTimeoutMs
                                                lastClickMark.value = if (isDoubleClick) null else now
                                                if (isDoubleClick) {
                                                    onDoubleClick?.invoke()
                                                    Unit
                                                } else {
                                                    onItemClick(tab)
                                                }
                                            }
                                        }

                                    ShortNavigationBarItem(
                                        selected = selected,
                                        onClick = onClick,
                                        colors = if (canLiquidGlass && index == displayIndex) liquidGlassTransparentColors else itemColors,
                                        modifier =
                                            Modifier
                                                .weight(1f)
                                                // Same per-tab hook as the rail, the compact circles and the
                                                // SukiSU bar: labels are resource strings, so the tag is how the
                                                // shell contract test names the tab a bottom-bar tap selects.
                                                .testTag("shell-tab-${tab.name}")
                                                .onGloballyPositioned { coordinates ->
                                                    // Track the full item bounds (icon + spacing +
                                                    // label) so the Liquid Glass pill can size to
                                                    // cover both. The default variant ignores this
                                                    // and uses the icon-only [iconCenters] map.
                                                    val pos = coordinates.positionInRoot()
                                                    val bounds =
                                                        Rect(
                                                            pos.x,
                                                            pos.y,
                                                            pos.x + coordinates.size.width,
                                                            pos.y + coordinates.size.height,
                                                        )
                                                    if (itemBounds[index] != bounds) itemBounds[index] = bounds
                                                },
                                        icon = {
                                            // Measure the icon's own bounds so the pill hugs only the icon.
                                            Box(
                                                modifier =
                                                    Modifier.onGloballyPositioned { coordinates ->
                                                        val pos = coordinates.positionInRoot()
                                                        val center =
                                                            Offset(
                                                                pos.x + coordinates.size.width / 2f,
                                                                pos.y + coordinates.size.height / 2f,
                                                            )
                                                        // Equality guard: see the itemBounds write below.
                                                        if (iconCenters[index] != center) iconCenters[index] = center
                                                    },
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Crossfade(
                                                    targetState = selected,
                                                    animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                                                    label = "navigationItemIcon",
                                                ) { isSelected ->
                                                    Icon(
                                                        painter =
                                                            painterResource(
                                                                // For Liquid Glass: the underlying bar always
                                                                // uses the INACTIVE icon variant. The pill
                                                                // overlay renders the ACTIVE variant in
                                                                // primary color on top of the glass.
                                                                if (canLiquidGlass) tab.iconInactive else if (isSelected) tab.iconActive else tab.iconInactive,
                                                            ),
                                                        contentDescription = null,
                                                        modifier =
                                                            Modifier.graphicsLayer {
                                                                scaleX = iconScale.value
                                                                scaleY = iconScale.value
                                                            },
                                                    )
                                                }
                                            }
                                        },
                                        label = if (hideNavigationLabels) {
                                            null
                                        } else {
                                            {
                                                if (canLiquidGlass) {
                                                    Text(
                                                        text = stringResource(tab.label),
                                                        maxLines = 1,
                                                        modifier = Modifier.offset(y = (-4).dp),
                                                        // Liquid Glass: underlying label is always Normal.
                                                        // The pill overlay renders the active label in
                                                        // SemiBold (primary color) on top of the glass.
                                                        fontWeight = FontWeight.Normal,
                                                        style = glassLabelStyle,
                                                    )
                                                } else {
                                                    Spacer(Modifier.height(navBarLabelSpacing.dp))
                                                    Text(
                                                        text = stringResource(tab.label),
                                                        maxLines = 1,
                                                    )
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (canLiquidGlass && selectedIndex >= 0 && indicatorPlaced && liquidGlassBackdrop != null) {
                val pillWidth = liquidGlassPillWidth
                val pillHeight = liquidGlassPillHeight
                val dragAnim = dampedDragAnimation
                if (pillWidth > 0.dp && pillHeight > 0.dp && dragAnim != null && tabWidthPx > 0f) {
                    val pillShape = RoundedCornerShape(percent = 50)
                    val isDark = glassIsNight
                    // Fallback surface drawn UNDER the backdrop sample.
                    val pillFallbackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    Box(
                        modifier =
                            Modifier
                                .offset {
                                    val pillWidthPx = pillWidth.toPx()
                                    val hPaddingPx = itemHorizontalPadding.toPx()
                                    // X: align with the items Row's content area, then slide.
                                    // iconOffsetX corrects for ShortNavigationBarItem not
                                    // centering the icon within the tab slot.
                                    val tabSlotCenterForSelected =
                                        containerPos.x +
                                            itemsRowLeftInContainer +
                                            hPaddingPx +
                                            selectedIndex * tabWidthPx +
                                            tabWidthPx / 2f
                                    val iconOffsetX =
                                        selectedCenter?.let { it.x - tabSlotCenterForSelected } ?: 0f
                                    val xInRoot =
                                        containerPos.x +
                                            itemsRowLeftInContainer +
                                            hPaddingPx +
                                            dragAnim.value * tabWidthPx +
                                            (tabWidthPx - pillWidthPx) / 2f +
                                            panelOffset +
                                            iconOffsetX
                                    // Y = 0: the wrapper Box's CenterStart alignment
                                    // centers the pill vertically. No Y computation needed.
                                    val xRelativeToWrapper = xInRoot - barPositionInRoot.x
                                    IntOffset(xRelativeToWrapper.roundToInt(), 0)
                                }
                                .width(pillWidth)
                                .height(pillHeight)
                                .graphicsLayer {
                                    // SukiSU pressedScale = 78f / 56f ≈ 1.393.
                                    // Velocity-stretch: the pill squishes horizontally when
                                    // flung (a physical "rubber" feel). The asymmetry (0.75 ×
                                    // horizontal, 0.25 × vertical) preserves area roughly.
                                    val pressScale = lerp(1f, 78f / 56f, (if (glassMotion) dragAnim.pressProgress else 0f))
                                    val velocityStretch = if (glassMotion) (dragAnim.velocity / 10f).fastCoerceIn(-0.2f, 0.2f) else 0f
                                    scaleX = pressScale / (1f - velocityStretch * 0.75f)
                                    scaleY = pressScale * (1f - velocityStretch * 0.25f)
                                }
                                .liquidGlassMaterial(
                                    backdrop = liquidGlassBackdrop,
                                    shape = pillShape,
                                    role = LiquidGlassRole.SelectionLens,
                                    progress = { (if (glassMotion) dragAnim.pressProgress else 0f) },
                                    baseColor = pillFallbackColor,
                                    onDrawSurface = {
                                        val progress = (if (glassMotion) dragAnim.pressProgress else 0f)
                                        drawRect(
                                            color = if (isDark) Color.Black else Color.White,
                                            alpha = (if (isDark) 0.28f else 0.1f) * (1f - progress),
                                        )
                                        drawRect(Color.Black.copy(alpha = 0.03f * progress))
                                    },
                                )
                                .innerShadow(shape = pillShape) {
                                    // Inner shadow only appears during press — radius + alpha
                                    // both scale with pressProgress. This is the "glass lifted
                                    // off the bar" feel.
                                    if ((if (glassMotion) dragAnim.pressProgress else 0f) > 0f) {
                                        InnerShadow(
                                            radius = 8.dp * (if (glassMotion) dragAnim.pressProgress else 0f),
                                            color = Color.Black.copy(alpha = 0.15f),
                                            alpha = (if (glassMotion) dragAnim.pressProgress else 0f),
                                        )
                                    } else {
                                        null
                                    }
                                },
                        contentAlignment = Alignment.Center,
                    ) {
                        val displayScreen = items[displayIndex]
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                        ) {
                            Icon(
                                painter = painterResource(displayScreen.iconActive),
                                contentDescription = null,
                                tint = glassContentColor,
                                modifier =
                                    Modifier.graphicsLayer {
                                        // SukiSU LocalFloatingBottomBarTabScale: the
                                        // icon scales up to 1.2× during press.
                                        val scale = lerp(1f, 1.2f, (if (glassMotion) dragAnim.pressProgress else 0f))
                                        scaleX = scale
                                        scaleY = scale
                                    },
                            )
                            if (!hideNavigationLabels) {
                                Text(
                                    text = stringResource(displayScreen.label),
                                    color = glassContentColor,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = glassLabelStyle,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
