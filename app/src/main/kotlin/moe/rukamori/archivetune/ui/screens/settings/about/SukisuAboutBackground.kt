package moe.rukamori.archivetune.ui.screens.settings.about

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.kyant.backdrop.RuntimeShader
import com.kyant.backdrop.asComposeShader
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.isRuntimeShaderSupported
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.LocalAnimationScale
import moe.rukamori.archivetune.LocalAnimationsDisabled
import moe.rukamori.archivetune.LocalBlurPolicy
import moe.rukamori.archivetune.ui.theme.LocalYumaGlassBackdrop
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

internal class SukisuBgConfig(
    val points: FloatArray,
    val colors1: FloatArray,
    val colors2: FloatArray,
    val colors3: FloatArray,
    val colorInterpPeriod: Float,
    val lightOffset: Float,
    val saturateOffset: Float,
    val pointOffset: Float,
)

private val SukisuPhoneLight =
    SukisuBgConfig(
        points = floatArrayOf(0.8f, 0.2f, 1.0f, 0.8f, 0.9f, 1.0f, 0.2f, 0.9f, 1.0f, 0.2f, 0.2f, 1.0f),
        colors1 = floatArrayOf(1.0f, 0.9f, 0.94f, 1.0f, 1.0f, 0.84f, 0.89f, 1.0f, 0.97f, 0.73f, 0.82f, 1.0f, 0.64f, 0.65f, 0.98f, 1.0f),
        colors2 = floatArrayOf(0.58f, 0.74f, 1.0f, 1.0f, 1.0f, 0.9f, 0.93f, 1.0f, 0.74f, 0.76f, 1.0f, 1.0f, 0.97f, 0.77f, 0.84f, 1.0f),
        colors3 = floatArrayOf(0.98f, 0.86f, 0.9f, 1.0f, 0.6f, 0.73f, 0.98f, 1.0f, 0.92f, 0.93f, 1.0f, 1.0f, 0.56f, 0.69f, 1.0f, 1.0f),
        colorInterpPeriod = 5.0f,
        lightOffset = 0.1f,
        saturateOffset = 0.2f,
        pointOffset = 0.2f,
    )

private val SukisuPhoneDark =
    SukisuBgConfig(
        points = floatArrayOf(0.8f, 0.2f, 1.0f, 0.8f, 0.9f, 1.0f, 0.2f, 0.9f, 1.0f, 0.2f, 0.2f, 1.0f),
        colors1 = floatArrayOf(0.2f, 0.06f, 0.88f, 0.4f, 0.3f, 0.14f, 0.55f, 0.5f, 0.0f, 0.64f, 0.96f, 0.5f, 0.11f, 0.16f, 0.83f, 0.4f),
        colors2 = floatArrayOf(0.07f, 0.15f, 0.79f, 0.5f, 0.62f, 0.21f, 0.67f, 0.5f, 0.06f, 0.25f, 0.84f, 0.5f, 0.0f, 0.2f, 0.78f, 0.5f),
        colors3 = floatArrayOf(0.58f, 0.3f, 0.74f, 0.4f, 0.27f, 0.18f, 0.6f, 0.5f, 0.66f, 0.26f, 0.62f, 0.5f, 0.12f, 0.16f, 0.7f, 0.6f),
        colorInterpPeriod = 8.0f,
        lightOffset = 0.0f,
        saturateOffset = 0.17f,
        pointOffset = 0.4f,
    )

private const val SUKISU_BG_FRAG = """
    uniform float2 uResolution;
    uniform float uAnimTime;
    uniform float4 uBound;
    uniform float uTranslateY;
    uniform float3 uPoints[4];
    uniform float2 uPointsAnim[4];
    uniform float4 uColors[4];
    uniform float uAlphaMulti;
    uniform float uNoiseScale;
    uniform float uPointRadiusMulti;
    uniform float uSaturateOffset;
    uniform float uLightOffset;

    vec3 rgb2hsv(vec3 c) {
        vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
        vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
        vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
        float d = q.x - min(q.w, q.y);
        float e = 1.0e-10;
        return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
    }

    vec3 hsv2rgb(vec3 c) {
        vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
        vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
        return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
    }

    float hash(vec2 p) {
        vec3 p3 = fract(vec3(p.xyx) * 0.13);
        p3 += dot(p3, p3.yzx + 3.333);
        return fract((p3.x + p3.y) * p3.z);
    }

    float perlin(vec2 x) {
        vec2 i = floor(x); vec2 f = fract(x);

        float a = hash(i); float b = hash(i + vec2(1.0, 0.0));
        float c = hash(i + vec2(0.0, 1.0)); float d = hash(i + vec2(1.0, 1.0));

        vec2 u = f * f * (3.0 - 2.0 * f);
        return mix(a, b, u.x) + (c - a) * u.y * (1.0 - u.x) + (d - b) * u.x * u.y;
    }

    float gradientNoise(in vec2 uv) {
        return fract(52.9829189 * fract(dot(uv, vec2(0.06711056, 0.00583715))));
    }

    vec4 main(vec2 fragCoord){
        vec2 vUv = fragCoord/uResolution;
        vUv.y = 1.0-vUv.y;
        vec2 uv = vUv;
        uv -= vec2(0., uTranslateY);
        uv.xy -= uBound.xy;
        uv.xy /= uBound.zw;

        vec4 color = vec4(0.0);
        float noiseValue = perlin(vUv * uNoiseScale + vec2(-uAnimTime, -uAnimTime));

        for (int i = 0; i < 4; i++){
            vec4 pointColor = uColors[i];
            pointColor.rgb *= pointColor.a;
            vec2 point = uPointsAnim[i];
            float rad = uPoints[i].z * uPointRadiusMulti;

            float d = distance(uv, point);
            float pct = smoothstep(rad, 0., d);
            color.rgb = mix(color.rgb, pointColor.rgb, pct);
            color.a = mix(color.a, pointColor.a, pct);
        }

        float oppositeNoise = smoothstep(0., 1., noiseValue);
        color.rgb /= color.a;
        vec3 hsv = rgb2hsv(color.rgb);
        hsv.y = mix(hsv.y, 0.0, oppositeNoise * uSaturateOffset);
        color.rgb = hsv2rgb(hsv);
        color.rgb += oppositeNoise * uLightOffset;

        color.a = clamp(color.a, 0., 1.);
        color.a *= uAlphaMulti;

        color += (10.0 / 255.0) * gradientNoise(fragCoord.xy) - (5.0 / 255.0);
        return vec4(color.rgb * color.a, color.a);
    }
"""

/**
 * How far the header has scrolled out of view, 0 at rest and 1 once the first item has gone. The
 * background fades over the first three quarters of that distance and stops drawing after it.
 */
internal fun sukisuHeaderScrollProgress(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    firstItemHeight: Int,
): Float =
    when {
        firstVisibleItemIndex > 0 -> 1f
        firstItemHeight <= 0 -> 0f
        else -> (firstVisibleItemScrollOffset.toFloat() / firstItemHeight).coerceIn(0f, 1f)
    }

internal fun sukisuBackgroundAlpha(scrollProgress: Float): Float = ((1f - scrollProgress) / 0.75f).coerceIn(0f, 1f)

/** The frame loop runs only while it can be seen and motion is allowed; otherwise one still frame. */
internal fun sukisuBackgroundAnimates(
    resumed: Boolean,
    animationsDisabled: Boolean,
    animationScale: Float,
    backgroundAlpha: Float,
): Boolean = resumed && !animationsDisabled && animationScale > 0f && backgroundAlpha > SUKISU_VISIBLE_ALPHA

private const val SUKISU_VISIBLE_ALPHA = 0.01f

private class SukisuBgPainter {
    val runtimeShader: RuntimeShader? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && isRuntimeShaderSupported()) {
            runCatching { RuntimeShader(SUKISU_BG_FRAG).also { initStaticUniforms(it) } }.getOrNull()
        } else {
            null
        }

    val brush: Brush? = runtimeShader?.let { ShaderBrush(it.asComposeShader()) }

    private val resolution = FloatArray(2)
    private val bound = FloatArray(4)
    private val colorsBuffer = FloatArray(16)
    private val pointsAnimBuffer = FloatArray(8)

    private var animTime = Float.NaN
    private var appliedPreset: SukisuBgConfig? = null
    private var cachedBoundHeight = Float.NaN
    private var cachedTotalHeight = Float.NaN
    private var cachedTotalWidth = Float.NaN
    private var cachedColorStage = Float.NaN
    private var cachedColorsPreset: SukisuBgConfig? = null
    private var cachedPointsAnimTime = Float.NaN
    private var cachedPointsAnimPreset: SukisuBgConfig? = null

    private fun initStaticUniforms(shader: RuntimeShader) {
        shader.setFloatUniform("uTranslateY", 0f)
        shader.setFloatUniform("uNoiseScale", 1.5f)
        shader.setFloatUniform("uPointRadiusMulti", 1f)
        shader.setFloatUniform("uAlphaMulti", 1f)
    }

    fun updateResolution(
        width: Float,
        height: Float,
    ) {
        if (resolution[0] == width && resolution[1] == height) return
        resolution[0] = width
        resolution[1] = height
        runtimeShader?.setFloatUniform("uResolution", resolution)
    }

    fun updateAnimTime(time: Float) {
        if (animTime == time) return
        animTime = time
        runtimeShader?.setFloatUniform("uAnimTime", animTime)
    }

    fun updatePointsAnim(
        time: Float,
        preset: SukisuBgConfig,
    ) {
        if (cachedPointsAnimTime == time && cachedPointsAnimPreset === preset) return
        val offset = preset.pointOffset
        for (i in 0 until 4) {
            val srcX = preset.points[i * 3]
            val srcY = preset.points[i * 3 + 1]
            val animX = srcX + sin(time + srcY) * offset
            val animY = srcY + cos(time + animX) * offset
            pointsAnimBuffer[i * 2] = animX
            pointsAnimBuffer[i * 2 + 1] = animY
        }
        runtimeShader?.setFloatUniform("uPointsAnim", pointsAnimBuffer)
        cachedPointsAnimTime = time
        cachedPointsAnimPreset = preset
    }

    fun updateColors(
        preset: SukisuBgConfig,
        stage: Float,
    ) {
        if (cachedColorsPreset === preset && cachedColorStage == stage) return
        val base = floor(stage).toInt()
        val fraction = stage - base
        val start = colorsForCycleIndex(preset, base)
        val end = colorsForCycleIndex(preset, base + 1)
        for (i in 0 until 16) {
            colorsBuffer[i] = start[i] + (end[i] - start[i]) * fraction
        }
        runtimeShader?.setFloatUniform("uColors", colorsBuffer)
        cachedColorsPreset = preset
        cachedColorStage = stage
    }

    private fun colorsForCycleIndex(
        preset: SukisuBgConfig,
        index: Int,
    ): FloatArray =
        when (index.mod(4)) {
            1 -> preset.colors1
            3 -> preset.colors3
            else -> preset.colors2
        }

    fun updateBoundIfNeeded(
        drawHeight: Float,
        totalHeight: Float,
        totalWidth: Float,
    ) {
        if (cachedBoundHeight == drawHeight && cachedTotalHeight == totalHeight && cachedTotalWidth == totalWidth) return
        val heightRatio = drawHeight / totalHeight
        if (totalWidth <= totalHeight) {
            bound[0] = 0f
            bound[1] = 1f - heightRatio
            bound[2] = 1f
            bound[3] = heightRatio
        } else {
            val aspectRatio = totalWidth / totalHeight
            val contentCenterY = 1f - heightRatio / 2f
            bound[0] = 0f
            bound[1] = contentCenterY - aspectRatio / 2f
            bound[2] = 1f
            bound[3] = aspectRatio
        }
        runtimeShader?.setFloatUniform("uBound", bound)
        cachedBoundHeight = drawHeight
        cachedTotalHeight = totalHeight
        cachedTotalWidth = totalWidth
    }

    fun updatePresetIfNeeded(preset: SukisuBgConfig) {
        if (appliedPreset === preset) return
        runtimeShader?.setFloatUniform("uPoints", preset.points)
        runtimeShader?.setFloatUniform("uLightOffset", preset.lightOffset)
        runtimeShader?.setFloatUniform("uSaturateOffset", preset.saturateOffset)
        appliedPreset = preset
    }
}

private data class SukisuBgElement(
    val painter: SukisuBgPainter,
    val preset: SukisuBgConfig,
    val surface: Color,
    val playing: Boolean,
    val colorStage: () -> Float,
    val alpha: () -> Float,
) : ModifierNodeElement<SukisuBgNode>() {
    override fun create(): SukisuBgNode = SukisuBgNode(painter, preset, surface, playing, colorStage, alpha)

    override fun update(node: SukisuBgNode) {
        node.update(painter, preset, surface, playing, colorStage, alpha)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "sukisuAboutBackground"
    }
}

private class SukisuBgNode(
    private var painter: SukisuBgPainter,
    private var preset: SukisuBgConfig,
    private var surface: Color,
    private var playing: Boolean,
    private var colorStage: () -> Float,
    private var alpha: () -> Float,
) : Modifier.Node(),
    DrawModifierNode {
    private var animationJob: Job? = null
    private var animTime = 0f

    override fun onAttach() {
        if (playing) startAnimation()
    }

    override fun onDetach() {
        stopAnimation()
    }

    fun update(
        painter: SukisuBgPainter,
        preset: SukisuBgConfig,
        surface: Color,
        playing: Boolean,
        colorStage: () -> Float,
        alpha: () -> Float,
    ) {
        this.painter = painter
        this.preset = preset
        this.surface = surface
        this.colorStage = colorStage
        this.alpha = alpha
        if (this.playing != playing) {
            this.playing = playing
            if (playing && isAttached) startAnimation() else stopAnimation()
        }
        invalidateDraw()
    }

    private fun stopAnimation() {
        animationJob?.cancel()
        animationJob = null
    }

    private fun startAnimation() {
        stopAnimation()
        val startOffset = animTime
        animationJob =
            coroutineScope.launch {
                val origin = withFrameNanos { it }
                var lastEmit = origin
                while (isActive) {
                    val now = withFrameNanos { it }
                    if (now - lastEmit < MIN_FRAME_DELTA_NANOS) continue
                    lastEmit = now
                    animTime = startOffset + (now - origin) / 1_000_000_000f
                    invalidateDraw()
                }
            }
    }

    override fun ContentDrawScope.draw() {
        drawRect(surface)
        val brush = painter.brush
        val alphaValue = alpha()
        if (brush != null && alphaValue > 0f) {
            painter.updateResolution(size.width, size.height)
            painter.updateBoundIfNeeded(size.height * 0.8f, size.height, size.width)
            painter.updatePresetIfNeeded(preset)
            painter.updateColors(preset, colorStage())
            painter.updateAnimTime(animTime)
            painter.updatePointsAnim(animTime, preset)
            drawRect(brush, alpha = alphaValue)
        }
        drawContent()
    }

    private companion object {
        const val MIN_FRAME_DELTA_NANOS = 1_000_000_000L / 60L
    }
}

/**
 * The About page's moving blob gradient, with the page's settings rows drawn as frosted glass over
 * it. The shader needs Android 13 and runtime-shader support, and the frosted rows also need the
 * blur policy to allow platform blur; anywhere else [content] is shown over the plain theme
 * background, as it was before the gradient existed. [listState] is the page list: the gradient fades out as its header scrolls away.
 */
@Composable
fun SukisuAboutBackground(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val painter = remember { SukisuBgPainter() }
    val surface = MaterialTheme.colorScheme.background
    if (painter.brush == null) {
        Box(modifier = modifier.background(surface)) { content() }
        return
    }

    val isDark = surface.luminance() < 0.5f
    val preset = if (isDark) SukisuPhoneDark else SukisuPhoneLight
    val backgroundAlpha by remember(listState) {
        derivedStateOf {
            val progress =
                sukisuHeaderScrollProgress(
                    firstVisibleItemIndex = listState.firstVisibleItemIndex,
                    firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                    firstItemHeight = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 0,
                )
            sukisuBackgroundAlpha(progress)
        }
    }
    val visible by remember { derivedStateOf { backgroundAlpha > SUKISU_VISIBLE_ALPHA } }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val playing =
        sukisuBackgroundAnimates(
            resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED),
            animationsDisabled = LocalAnimationsDisabled.current,
            animationScale = LocalAnimationScale.current,
            backgroundAlpha = if (visible) 1f else 0f,
        )

    val colorStage = remember { Animatable(0f) }
    LaunchedEffect(preset, playing) {
        if (!playing) return@LaunchedEffect
        var targetStage = floor(colorStage.value) + 1f
        while (isActive) {
            delay((preset.colorInterpPeriod * 500).toLong())
            colorStage.animateTo(
                targetValue = targetStage,
                animationSpec = spring(dampingRatio = 0.9f, stiffness = 35f),
            )
            targetStage += 1f
        }
    }

    val glassBackdrop = if (LocalBlurPolicy.current.renderEffect) rememberLayerBackdrop() else null
    Box(modifier = modifier) {
        Spacer(
            modifier =
                Modifier
                    .fillMaxSize()
                    .then(if (glassBackdrop != null) Modifier.layerBackdrop(glassBackdrop) else Modifier)
                    .then(
                        SukisuBgElement(
                            painter = painter,
                            preset = preset,
                            surface = surface,
                            playing = playing,
                            colorStage = { colorStage.value },
                            alpha = { backgroundAlpha },
                        ),
                    ),
        )
        CompositionLocalProvider(LocalYumaGlassBackdrop provides glassBackdrop) {
            content()
        }
    }
}
