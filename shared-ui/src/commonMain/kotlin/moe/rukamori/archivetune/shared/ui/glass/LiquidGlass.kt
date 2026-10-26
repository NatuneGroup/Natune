package moe.rukamori.archivetune.shared.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop as recordBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow

typealias PlatformBackdrop = LayerBackdrop
val LocalLiquidGlassBackdrop = staticCompositionLocalOf<PlatformBackdrop?> { null }
enum class LiquidGlassRole { Surface, NavigationBar, SelectionLens, Card, ActionMenu }

@Composable
fun rememberBackdrop(color: Color): PlatformBackdrop = rememberLayerBackdrop {
    drawRect(color)
    drawContent()
}

fun Modifier.layerBackdrop(backdrop: PlatformBackdrop): Modifier = recordBackdrop(backdrop)

/**
 * The single optical adapter for glass surfaces. Only paint, the specular light and the optional press spotlight are
 * added here: geometry, semantics and hit testing stay with the caller, so the flat fallback and the glass share one
 * layout. [interactive] attaches the press spotlight, which runs only while motion is allowed. The navigation role
 * casts the baseline shadow; [baseColor] paints under the backdrop when given.
 */
@Composable
fun Modifier.liquidGlassMaterial(
    backdrop: Backdrop? = LocalLiquidGlassBackdrop.current,
    shape: Shape = CircleShape,
    role: LiquidGlassRole = LiquidGlassRole.Surface,
    interactive: Boolean = false,
    baseColor: Color = Color.Unspecified,
    fallbackColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    progress: () -> Float = { 1f },
    highlight: (() -> Highlight)? = null,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    onDrawSurface: (DrawScope.() -> Unit)? = null,
): Modifier {
    val policy = LocalOpticalPolicy.current
    if (!liquidGlassAllowed(policy.recordLiquidGlass, true, backdrop != null, true)) {
        return background(fallbackColor, shape)
    }
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < .5f
    // An animator duration scale of zero turns motion off system-wide, as the baseline's LocalAnimationScale check does.
    val motion = policy.motion && LocalAnimatorDurationScale.current > 0f
    val specular = rememberGlassSpecularHighlight(if (role == LiquidGlassRole.SelectionLens) 90f else -45f)
    val scope = rememberCoroutineScope()
    val spotlight = if (interactive && motion) remember(scope) { GlassPressSpotlight(scope) } else null
    DisposableEffect(spotlight) { onDispose { spotlight?.cancel() } }
    val tint = when (role) {
        LiquidGlassRole.ActionMenu -> Color.Black.copy(alpha = .30f)
        LiquidGlassRole.Card -> scheme.surfaceContainerHighest.copy(alpha = .7f)
        LiquidGlassRole.NavigationBar -> scheme.surfaceContainer.copy(alpha = .4f)
        LiquidGlassRole.SelectionLens -> (if (dark) Color.White else Color.Black).copy(alpha = .1f)
        LiquidGlassRole.Surface -> (if (dark) Color.Black else Color.White).copy(alpha = .28f)
    }
    // Remember only the optical node, never the caller's layout or semantics chain.
    val material = remember(backdrop, shape, role, baseColor, tint, dark, motion, specular, progress, highlight, layerBlock, onDrawSurface) {
        Modifier.drawBackdrop(
            backdrop = backdrop!!,
            shape = { shape },
            effects = {
                val p = if (motion) progress().coerceIn(0f, 1f) else 0f
                if (role != LiquidGlassRole.SelectionLens) {
                    padding = maxOf(padding, 40.dp.toPx())
                    vibrancy()
                    blur(if (role == LiquidGlassRole.ActionMenu) 20.dp.toPx() else 4.dp.toPx())
                }
                val height = when (role) {
                    LiquidGlassRole.SelectionLens -> 10.dp.toPx() * p
                    LiquidGlassRole.Card -> 2.dp.toPx()
                    LiquidGlassRole.ActionMenu -> 16.dp.toPx()
                    else -> 24.dp.toPx()
                }
                lens(
                    refractionHeight = liquidGlassRefractionHeight(height, size.minDimension),
                    refractionAmount = when (role) {
                        LiquidGlassRole.SelectionLens -> 14.dp.toPx() * p
                        LiquidGlassRole.Card -> 2.dp.toPx()
                        LiquidGlassRole.ActionMenu -> 40.dp.toPx()
                        else -> size.minDimension / 4f
                    },
                    depthEffect = role == LiquidGlassRole.SelectionLens,
                    chromaticAberration = role == LiquidGlassRole.SelectionLens,
                )
            },
            highlight = {
                if (motion) highlight?.invoke() ?: specular.value.copy(alpha = .75f)
                else specular.value.copy(alpha = .75f)
            },
            shadow = {
                if (role == LiquidGlassRole.NavigationBar) {
                    Shadow(radius = 10.dp, color = Color.Black, alpha = if (dark) .2f else .1f)
                } else null
            },
            layerBlock = { if (motion) layerBlock?.invoke(this) },
            onDrawBehind = if (baseColor != Color.Unspecified) { { drawRect(baseColor) } } else null,
            onDrawSurface = { if (onDrawSurface != null) onDrawSurface.invoke(this) else drawRect(tint) },
        )
    }
    return then(material).then(spotlight?.let { it.modifier.then(it.gestureModifier) } ?: Modifier)
}

/**
 * The 48 dp liquid-glass pill that hosts a row of header controls. Height and corner are fixed here so every header pill
 * keeps the same geometry. A null [backdrop] draws the same pill as a flat surface through [liquidGlassMaterial].
 */
@Composable
fun LiquidGlassActionPill(
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    interactive: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .height(48.dp)
            .liquidGlassMaterial(
                backdrop = backdrop,
                shape = RoundedCornerShape(24.dp),
                interactive = interactive,
            ),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * A liquid-glass surface around arbitrary [content], such as a row of icon buttons. A null [backdrop] draws the same
 * shape as a flat surface through [liquidGlassMaterial], so the glass and its flat twin keep one layout.
 */
@Composable
fun LiquidGlassContainer(
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    interactive: Boolean = false,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.liquidGlassMaterial(backdrop = backdrop, shape = shape, interactive = interactive),
        contentAlignment = contentAlignment,
        content = content,
    )
}

/**
 * The single-icon glass button, such as a detail header's back control. The painter overload reads the app's drawable
 * resources; the image vector overload keeps the baseline's second entry point.
 */
@Composable
fun LiquidGlassIconButton(
    backdrop: Backdrop?,
    painter: Painter,
    modifier: Modifier = Modifier.size(48.dp),
    shape: Shape = CircleShape,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    contentDescription: String? = null,
    interactive: Boolean = false,
    onClick: () -> Unit,
) {
    LiquidGlassContainer(
        backdrop = backdrop,
        modifier = modifier,
        shape = shape,
        interactive = interactive,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
            Icon(painter = painter, contentDescription = contentDescription, tint = tint)
        }
    }
}

@Composable
fun LiquidGlassIconButton(
    backdrop: Backdrop?,
    imageVector: ImageVector,
    modifier: Modifier = Modifier.size(48.dp),
    shape: Shape = CircleShape,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    contentDescription: String? = null,
    interactive: Boolean = false,
    onClick: () -> Unit,
) {
    LiquidGlassContainer(
        backdrop = backdrop,
        modifier = modifier,
        shape = shape,
        interactive = interactive,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
            Icon(imageVector = imageVector, contentDescription = contentDescription, tint = tint)
        }
    }
}
