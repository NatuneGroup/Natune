package moe.rukamori.archivetune.ui.component

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.DefaultCameraDistance
import androidx.compose.ui.graphics.DefaultShadowColor
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toIntSize
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val ThrottledLayerBackdropDefaultIntervalMillis = 32L

/**
 * A backdrop whose source records into its layer at most once per [minIntervalMillis]. Kyant's
 * LayerBackdrop draws its whole source twice on every frame (once to screen, once into the layer),
 * which is the dominant cost while the app-wide source scrolls. Glass sampling a frame up to one
 * interval old is not visible; a trailing record after the last change keeps the final frame exact.
 */
@Stable
class ThrottledLayerBackdrop internal constructor(
    val graphicsLayer: GraphicsLayer,
    internal val minIntervalMillis: Long,
    internal val contentPrefix: DrawScope.() -> Unit = {},
) : Backdrop {
    override val isCoordinatesDependent: Boolean get() = true

    internal var layerCoordinates: LayoutCoordinates? by mutableStateOf(null)

    // Read by consumers while drawing, so a recorder attaching or detaching redraws them once.
    internal var consumerInvalidationTick by mutableIntStateOf(0)

    // Plain field: it only guards against a consumer inside the recorded content sampling itself.
    internal var recordingInProgress = false

    private val inverseScope = InverseScaleLayerScope()

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        @Suppress("UNUSED_VARIABLE")
        val tick = consumerInvalidationTick
        if (recordingInProgress) return
        val coordinates = coordinates ?: return
        val layerCoordinates = layerCoordinates ?: return
        if (!layerCoordinates.isAttached || !coordinates.isAttached) return
        val offset =
            try {
                layerCoordinates.localPositionOf(coordinates)
            } catch (_: Exception) {
                runCatching { coordinates.positionInWindow() - layerCoordinates.positionInWindow() }.getOrNull()
            } ?: return
        withTransform({
            if (layerBlock != null) {
                val (scaleX, scaleY) = inverseScope.measure(size, density, layerBlock)
                if (scaleX != 0f && scaleY != 0f && (scaleX != 1f || scaleY != 1f)) {
                    scale(1f / scaleX, 1f / scaleY, Offset.Zero)
                }
            }
            translate(-offset.x, -offset.y)
        }) {
            runCatching { drawLayer(graphicsLayer) }
        }
    }
}

@Composable
fun rememberThrottledLayerBackdrop(
    minIntervalMillis: Long = ThrottledLayerBackdropDefaultIntervalMillis,
): ThrottledLayerBackdrop {
    val graphicsLayer = rememberGraphicsLayer()
    return remember(graphicsLayer, minIntervalMillis) { ThrottledLayerBackdrop(graphicsLayer, minIntervalMillis) }
}

/** A throttled backdrop whose recording starts by painting [color], so translucent sources read as opaque. */
@Composable
fun rememberThrottledBackdrop(
    color: Color,
    minIntervalMillis: Long = ThrottledLayerBackdropDefaultIntervalMillis,
): ThrottledLayerBackdrop {
    val graphicsLayer = rememberGraphicsLayer()
    val backdrop =
        remember(graphicsLayer, minIntervalMillis, color) {
            ThrottledLayerBackdrop(graphicsLayer, minIntervalMillis) { drawRect(color) }
        }
    DisposableEffect(backdrop) { onDispose { backdrop.layerCoordinates = null } }
    return backdrop
}

fun Modifier.throttledLayerBackdrop(backdrop: ThrottledLayerBackdrop): Modifier =
    this then ThrottledLayerBackdropElement(backdrop)

private class ThrottledLayerBackdropElement(
    val backdrop: ThrottledLayerBackdrop,
) : ModifierNodeElement<ThrottledLayerBackdropNode>() {
    override fun create() = ThrottledLayerBackdropNode(backdrop)

    override fun update(node: ThrottledLayerBackdropNode) {
        if (node.backdrop !== backdrop) {
            node.backdrop.layerCoordinates = null
            node.backdrop = backdrop
        }
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "throttledLayerBackdrop"
        properties["backdrop"] = backdrop
        properties["minIntervalMillis"] = backdrop.minIntervalMillis
    }

    override fun equals(other: Any?): Boolean = other is ThrottledLayerBackdropElement && backdrop === other.backdrop

    override fun hashCode(): Int = backdrop.hashCode()
}

private class ThrottledLayerBackdropNode(
    var backdrop: ThrottledLayerBackdrop,
) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {
    private var lastRecordUptimeMillis = 0L
    private var trailingRecord: Job? = null

    override fun onAttach() {
        lastRecordUptimeMillis = 0L
        backdrop.consumerInvalidationTick++
    }

    override fun ContentDrawScope.draw() {
        val now = SystemClock.uptimeMillis()
        val elapsed = now - lastRecordUptimeMillis
        if (elapsed < backdrop.minIntervalMillis) {
            scheduleTrailingRecord(backdrop.minIntervalMillis - elapsed)
            drawContent()
            return
        }
        lastRecordUptimeMillis = now
        trailingRecord?.cancel()
        trailingRecord = null
        val density = requireDensity()
        val layer = backdrop.graphicsLayer
        val recorded =
            runCatching {
                backdrop.recordingInProgress = true
                try {
                    layer.record(size.toIntSize()) {
                        val previousDensity = drawContext.density
                        drawContext.density = density
                        try {
                            backdrop.contentPrefix(this@draw)
                            this@draw.drawContent()
                        } finally {
                            drawContext.density = previousDensity
                        }
                    }
                } finally {
                    backdrop.recordingInProgress = false
                }
            }.isSuccess
        if (recorded && layer.size == size.toIntSize()) {
            runCatching { drawLayer(layer) }
        } else {
            drawContent()
        }
    }

    // One pending invalidation at most, and none once content stops changing: idle costs nothing.
    private fun scheduleTrailingRecord(afterMillis: Long) {
        if (trailingRecord?.isActive == true) return
        trailingRecord =
            coroutineScope.launch {
                delay(afterMillis.coerceAtLeast(1L))
                invalidateDraw()
            }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (coordinates.isAttached) backdrop.layerCoordinates = coordinates
    }

    override fun onDetach() {
        trailingRecord = null
        if (backdrop.layerCoordinates?.isAttached != true) {
            backdrop.layerCoordinates = null
            backdrop.consumerInvalidationTick++
        }
    }
}

/**
 * Evaluates a consumer's layer block so the backdrop can undo its scale, keeping the sampled
 * content aligned under a pressed or stretched lens. Only scale is inverted: no glass consumer
 * here rotates. Adapted from Kyant backdrop's InverseLayerScope (Apache-2.0), which is internal.
 */
private class InverseScaleLayerScope : GraphicsLayerScope {
    override var size: Size = Size.Unspecified
    override var density: Float = 1f
    override var fontScale: Float = 1f
    override var scaleX: Float = 1f
    override var scaleY: Float = 1f
    override var alpha: Float = 1f
    override var translationX: Float = 0f
    override var translationY: Float = 0f
    override var shadowElevation: Float = 0f
    override var ambientShadowColor: Color = DefaultShadowColor
    override var spotShadowColor: Color = DefaultShadowColor
    override var rotationX: Float = 0f
    override var rotationY: Float = 0f
    override var rotationZ: Float = 0f
    override var cameraDistance: Float = DefaultCameraDistance
    override var transformOrigin: TransformOrigin = TransformOrigin.Center
    override var shape: Shape = RectangleShape
    override var clip: Boolean = false
    override var renderEffect: RenderEffect? = null
    override var blendMode: BlendMode = BlendMode.SrcOver
    override var colorFilter: ColorFilter? = null
    override var compositingStrategy: CompositingStrategy = CompositingStrategy.Auto

    fun measure(
        drawSize: Size,
        drawDensity: Density,
        layerBlock: GraphicsLayerScope.() -> Unit,
    ): Pair<Float, Float> {
        size = drawSize
        density = drawDensity.density
        fontScale = drawDensity.fontScale
        scaleX = 1f
        scaleY = 1f
        layerBlock()
        return scaleX to scaleY
    }
}

/**
 * Keeps the blur and vibrancy pipeline warm behind an almost invisible 1 dp strip, so the first
 * glass surface to appear does not stall on shader and render-node setup.
 */
@Composable
fun GlassPipelinePrewarm(
    backdrop: Backdrop?,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!active || backdrop == null) return
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(1.dp)
                .graphicsLayer { alpha = 0.02f }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RectangleShape },
                    effects = {
                        vibrancy()
                        blur(32.dp.toPx())
                    },
                ),
    )
}
