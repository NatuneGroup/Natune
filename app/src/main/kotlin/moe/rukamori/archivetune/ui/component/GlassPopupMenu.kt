package moe.rukamori.archivetune.ui.component

// Container and row layout adapted from ArchiveTune's BlurredOverflowMenu.kt and TopAppBarOverflowMenu.kt (GPL-3.0).

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import moe.rukamori.archivetune.R

private val GlassPopupMenuWidth = 264.dp
private val GlassPopupMaxHeight = 560.dp
private val GlassPopupGap = 4.dp
private val GlassPopupElevation = 6.dp
private val GlassPopupRowHeight = 56.dp
private val GlassPopupIconSize = 20.dp
private const val GlassPopupBorderAlpha = 0.45f

/** The surface a popup menu is drawn with. Each look is one the navigation bar can also take. */
internal enum class GlassPopupLook { SOLID, FROSTED, TINTED_FROSTED, LIQUID_GLASS }

/**
 * Liquid glass wins whenever the header's glass buttons are glass, so the menu and the toggle beside
 * it never disagree. Frost follows the navigation bar's own blur and tint settings.
 */
internal fun glassPopupLook(
    liquidGlass: Boolean,
    frosted: Boolean,
    tinted: Boolean,
): GlassPopupLook =
    when {
        liquidGlass -> GlassPopupLook.LIQUID_GLASS
        frosted && tinted -> GlassPopupLook.TINTED_FROSTED
        frosted -> GlassPopupLook.FROSTED
        else -> GlassPopupLook.SOLID
    }

/**
 * Top-left corner of a menu hanging from the end edge of [anchor]. The popup window and the frosted
 * backdrop both use it, because the backdrop has to know where the menu sits in the window.
 */
internal fun glassPopupTopLeft(
    anchor: IntRect,
    popupWidthPx: Int,
    gapPx: Int,
): IntOffset =
    IntOffset(
        x = (anchor.right - popupWidthPx).coerceAtLeast(0),
        y = anchor.bottom + gapPx,
    )

/**
 * A menu that hangs from [anchor], given in root coordinates. It takes the navigation bar's look:
 * liquid glass when the header's glass buttons are glass, frosted or tinted when the bar is, and an
 * opaque surface otherwise.
 */
@Composable
fun GlassPopupMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    anchor: IntRect,
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (expanded) {
        GlassPopupWindow(onDismissRequest = onDismissRequest, anchor = anchor, title = title, content = content)
    }
}

/**
 * One choice in a [GlassPopupMenu]: a 56dp target with the icon, the label, and a check on the
 * selected entry. It reads as a radio button to a screen reader.
 */
@Composable
fun GlassPopupMenuItem(
    text: String,
    icon: Painter,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = GlassPopupRowHeight)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painter = icon, contentDescription = null, modifier = Modifier.size(GlassPopupIconSize))
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = LocalContentColor.current,
        )
        if (selected) {
            Spacer(Modifier.width(12.dp))
            Icon(
                painter = painterResource(R.drawable.check),
                contentDescription = null,
                modifier = Modifier.size(GlassPopupIconSize),
            )
        }
    }
}

@Immutable
private class GlassPopupStyle(
    val look: GlassPopupLook,
    val containerColor: Color,
    val contentColor: Color,
    val glassTint: Color,
)

@Composable
private fun rememberGlassPopupStyle(): GlassPopupStyle {
    val scheme = MaterialTheme.colorScheme
    val pill = rememberPillStyle()
    val pureBlack = scheme.background == Color.Black
    val dark = scheme.surface.luminance() < 0.5f
    val solid = if (pureBlack) Color.Black else scheme.surfaceContainerHigh
    val solidInk = if (pureBlack) Color.White else scheme.onSurface
    val look =
        glassPopupLook(
            liquidGlass = liquidGlassEligible(LocalLiquidGlassBackdrop.current),
            frosted = pill.backdrop == PillBackdrop.FROSTED,
            tinted = pill.tinted,
        )
    return when (look) {
        GlassPopupLook.LIQUID_GLASS ->
            GlassPopupStyle(
                look = look,
                containerColor = solid,
                contentColor = menuGlassColorScheme(scheme, dark).onSurface,
                glassTint = menuGlassTint(scheme, dark, LocalLiquidGlassTuning.current.tintFactor),
            )

        GlassPopupLook.FROSTED -> GlassPopupStyle(look, pill.containerColor, solidInk, Color.Unspecified)
        GlassPopupLook.TINTED_FROSTED -> GlassPopupStyle(look, pill.containerColor, pill.tintedContent, Color.Unspecified)
        GlassPopupLook.SOLID -> GlassPopupStyle(look, solid, solidInk, Color.Unspecified)
    }
}

@Composable
private fun GlassPopupWindow(
    onDismissRequest: () -> Unit,
    anchor: IntRect,
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val widthPx = with(density) { GlassPopupMenuWidth.roundToPx() }
    val gapPx = with(density) { GlassPopupGap.roundToPx() }
    val origin = glassPopupTopLeft(anchor, widthPx, gapPx)
    val positionProvider =
        remember(widthPx, gapPx) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset = glassPopupTopLeft(anchorBounds, widthPx, gapPx)
            }
        }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        GlassPopupSurface(
            title = title,
            originInRoot = Offset(origin.x.toFloat(), origin.y.toFloat()),
            content = content,
        )
    }
}

@Composable
private fun GlassPopupSurface(
    title: String,
    originInRoot: Offset,
    content: @Composable ColumnScope.() -> Unit,
) {
    val style = rememberGlassPopupStyle()
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.large
    val borderColor = scheme.outlineVariant.copy(alpha = GlassPopupBorderAlpha)
    val frostedBackdrop = LocalNavigationBarBackdrop.current
    val frosted = style.look == GlassPopupLook.FROSTED || style.look == GlassPopupLook.TINTED_FROSTED
    val frostAlpha = if (style.look == GlassPopupLook.TINTED_FROSTED) PillTintFrostOverlayAlpha else PillFrostOverlayAlpha
    val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }
    val surface: Modifier =
        if (style.look == GlassPopupLook.LIQUID_GLASS) {
            val glassTint = style.glassTint
            val drawGlassTint: DrawScope.() -> Unit = remember(glassTint) { { drawRect(glassTint) } }
            Modifier.liquidGlassMaterial(
                backdrop = LocalLiquidGlassBackdrop.current,
                shape = shape,
                role = LiquidGlassRole.ActionMenu,
                fallbackColor = style.containerColor,
                onDrawSurface = drawGlassTint,
            )
        } else {
            Modifier.background(style.containerColor, shape)
        }
    CompositionLocalProvider(LocalContentColor provides style.contentColor) {
        AnimatedVisibility(
            visibleState = visibleState,
            enter = scaleIn(initialScale = 0.92f) + fadeIn(),
        ) {
            Box(
                modifier =
                    Modifier
                        .width(GlassPopupMenuWidth)
                        .shadow(elevation = GlassPopupElevation, shape = shape)
                        .clip(shape)
                        .then(surface)
                        .border(width = 1.dp, color = borderColor, shape = shape)
                        .semantics { paneTitle = title },
            ) {
                if (frosted && frostedBackdrop != null) {
                    Box(
                        modifier =
                            Modifier
                                .matchParentSize()
                                .graphicsLayer {
                                    renderEffect =
                                        BlurEffect(
                                            radiusX = PillFrostBlurRadiusPx,
                                            radiusY = PillFrostBlurRadiusPx,
                                            edgeTreatment = TileMode.Clamp,
                                        )
                                    alpha = frostAlpha
                                    clip = true
                                }
                                .drawBehind {
                                    val offset = frostedBackdrop.contentOffsetInRoot - originInRoot
                                    translate(offset.x, offset.y) {
                                        drawLayer(frostedBackdrop.layer)
                                    }
                                },
                    ) {}
                }
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = GlassPopupMaxHeight)
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 8.dp),
                    content = content,
                )
            }
        }
    }
}
