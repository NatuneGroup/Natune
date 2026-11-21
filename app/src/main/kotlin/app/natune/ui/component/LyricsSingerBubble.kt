@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.natune.ui.component

// Singer bubble for multi-voice synced lyrics, ported from rukamori/ArchiveTune bab954164 (GPL-3.0) and adapted to the
// Haze 1.7 effect API Natune ships. A frosted pill above the focused line names who is singing and slides toward
// that singer's side.

import android.icu.text.ListFormatter
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.natune.LocalPlayerConnection
import app.natune.R
import app.natune.lyrics.LyricsLineAlignment
import app.natune.lyrics.LyricsTextDirection
import app.natune.lyrics.PreparedLyrics
import app.natune.lyrics.PreparedLyricsLine
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import java.util.Locale
import kotlin.math.roundToInt

enum class LyricsSingerSide(
    val bias: Float,
) {
    LEFT(-1f),
    CENTER(0f),
    RIGHT(1f),
}

@Immutable
data class LyricsSingerCue(
    val label: String,
    val side: LyricsSingerSide,
)

/** Extra room above each line for the bubble; zero when these lyrics show no singer names. */
fun lyricsSingerLaneHeight(
    preparedLyrics: PreparedLyrics?,
    isSynced: Boolean,
): Dp = if (isSynced && preparedLyrics?.showsSingerNames == true) SINGER_LANE_HEIGHT else 0.dp

/**
 * The bubble for the line playing at [positionMs], drawn over the lyrics list it sits in. [anchorOffset] is the focus
 * line's top inside this box; the bubble rests just above it. Nothing is drawn for lyrics without singer names.
 */
@Composable
fun BoxScope.LyricsSingerBubbleOverlay(
    preparedLyrics: PreparedLyrics?,
    isSynced: Boolean,
    isWordSynced: Boolean,
    positionMs: () -> Int,
    anchorOffset: Dp,
    hazeState: HazeState,
    contentColor: Color,
) {
    if (lyricsSingerLaneHeight(preparedLyrics, isSynced) == 0.dp) return
    val isPlaying = LocalPlayerConnection.current?.isPlaying?.collectAsStateWithLifecycle()?.value ?: false
    val formatLabel = rememberLyricsSingerLabelFormatter()
    val lines = remember(preparedLyrics) { preparedLyrics?.lines?.filter { line -> line.startMs >= 0L }.orEmpty() }
    val cue by remember(lines, isWordSynced, formatLabel) {
        derivedStateOf {
            lines
                .getOrNull(lines.activeLineIndexAt(positionMs().toLong()))
                ?.takeUnless(PreparedLyricsLine::isInstrumental)
                ?.let { line ->
                    formatLabel(line.singers)?.let { label ->
                        val side = if (line.rendersRightAligned(isWordSynced)) LyricsSingerSide.RIGHT else LyricsSingerSide.LEFT
                        LyricsSingerCue(label, side)
                    }
                }
        }
    }
    val bubbleBottomPx = with(LocalDensity.current) { (anchorOffset - SINGER_BUBBLE_GAP).roundToPx() }
    LyricsSingerBubble(
        cue = cue,
        hazeState = hazeState,
        contentColor = contentColor,
        isPlaying = isPlaying,
        bubbleBottomProvider = { bubbleBottomPx },
        modifier = Modifier.matchParentSize().padding(horizontal = SINGER_BUBBLE_INSET),
    )
}

@Composable
fun rememberLyricsSingerLabelFormatter(): (List<String>) -> String? {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    return remember(locale) {
        // ListFormatter is API 26+; older devices get a plain comma list.
        val join: (List<String>) -> String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val formatter = ListFormatter.getInstance(locale)
                ({ singers -> formatter.format(singers) })
            } else {
                ({ singers -> singers.joinToString(", ") })
            }
        val formatLabel: (List<String>) -> String? = { singers ->
            when (singers.size) {
                0 -> null
                1 -> singers.first()
                else -> join(singers)
            }
        }
        formatLabel
    }
}

@Composable
fun LyricsSingerBubble(
    cue: LyricsSingerCue?,
    hazeState: HazeState,
    contentColor: Color,
    isPlaying: Boolean,
    bubbleBottomProvider: () -> Int?,
    modifier: Modifier = Modifier,
) {
    val motionScheme = MaterialTheme.motionScheme
    // Keep the last cue while the bubble animates out, so its label does not blank mid-exit.
    val retainedCue = remember { mutableStateOf(cue) }
    SideEffect {
        if (cue != null) retainedCue.value = cue
    }
    val displayedCue = cue ?: retainedCue.value
    val biasState: State<Float> =
        animateFloatAsState(
            targetValue = displayedCue?.side?.bias ?: LyricsSingerSide.CENTER.bias,
            animationSpec = motionScheme.defaultSpatialSpec(),
            label = "lyricsSingerBubbleBias",
        )

    Layout(
        modifier = modifier,
        content = {
            AnimatedVisibility(
                visible = cue != null,
                enter =
                    fadeIn(motionScheme.fastEffectsSpec()) +
                        scaleIn(
                            animationSpec = motionScheme.fastSpatialSpec(),
                            initialScale = BUBBLE_ENTER_SCALE,
                            transformOrigin = TransformOrigin(0.5f, 1f),
                        ),
                exit =
                    fadeOut(motionScheme.fastEffectsSpec()) +
                        scaleOut(
                            animationSpec = motionScheme.fastSpatialSpec(),
                            targetScale = BUBBLE_EXIT_SCALE,
                            transformOrigin = TransformOrigin(0.5f, 1f),
                        ),
                label = "lyricsSingerBubbleVisibility",
            ) {
                displayedCue?.let { visibleCue ->
                    SingerBubblePill(
                        label = visibleCue.label,
                        hazeState = hazeState,
                        contentColor = contentColor,
                        isPlaying = isPlaying,
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val containerWidth = constraints.maxWidth
        val containerHeight = constraints.maxHeight
        val placeable =
            measurables.firstOrNull()?.measure(
                Constraints(maxWidth = (containerWidth * BUBBLE_MAX_WIDTH_FRACTION).roundToInt().coerceAtLeast(0)),
            )
        layout(containerWidth, containerHeight) {
            if (placeable == null || placeable.width == 0) return@layout
            val bubbleBottom = bubbleBottomProvider() ?: return@layout
            val travel = (containerWidth - placeable.width).coerceAtLeast(0)
            val x = (travel * (biasState.value + 1f) / 2f).roundToInt()
            val y =
                (bubbleBottom - placeable.height)
                    .coerceIn(0, (containerHeight - placeable.height).coerceAtLeast(0))
            // place, not placeRelative: the side is already absolute, so an RTL lyrics layout must not mirror it.
            placeable.place(x, y)
        }
    }
}

@Composable
private fun SingerBubblePill(
    label: String,
    hazeState: HazeState,
    contentColor: Color,
    isPlaying: Boolean,
) {
    val motionScheme = MaterialTheme.motionScheme
    val lightContent = contentColor.luminance() > 0.5f
    val glassBase = if (lightContent) Color.Black else Color.White
    val glassStyle =
        remember(glassBase, lightContent) {
            HazeStyle(
                backgroundColor = glassBase,
                tint = HazeTint(glassBase.copy(alpha = if (lightContent) 0.26f else 0.42f)),
                blurRadius = 24.dp,
                noiseFactor = 0.12f,
                fallbackTint = HazeTint(glassBase.copy(alpha = if (lightContent) 0.5f else 0.72f)),
            )
        }
    val edgeBrush =
        remember(contentColor) {
            Brush.verticalGradient(listOf(contentColor.copy(alpha = 0.3f), contentColor.copy(alpha = 0.06f)))
        }
    val description = stringResource(R.string.lyrics_now_singing, label)

    Box(
        modifier = Modifier.heightIn(min = 30.dp).clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .clip(CircleShape)
                    .hazeEffect(state = hazeState, style = glassStyle)
                    .border(width = 1.dp, brush = edgeBrush, shape = CircleShape),
        )
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SingingIndicator(color = contentColor.copy(alpha = 0.86f), animating = isPlaying)
            Spacer(modifier = Modifier.width(8.dp))
            AnimatedContent(
                targetState = label,
                transitionSpec = {
                    (
                        fadeIn(motionScheme.defaultEffectsSpec()) +
                            slideInVertically(motionScheme.defaultSpatialSpec()) { height -> height / 2 }
                    ) togetherWith (
                        fadeOut(motionScheme.fastEffectsSpec()) +
                            slideOutVertically(motionScheme.fastSpatialSpec()) { height -> -height / 2 }
                    ) using SizeTransform(clip = false) { _, _ -> motionScheme.defaultSpatialSpec() }
                },
                contentAlignment = Alignment.CenterStart,
                label = "lyricsSingerBubbleLabel",
            ) { singerLabel ->
                Text(
                    text = singerLabel,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = contentColor.copy(alpha = 0.94f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SingingIndicator(
    color: Color,
    animating: Boolean,
) {
    val phases: List<State<Float>>? =
        if (animating) {
            val transition = rememberInfiniteTransition(label = "lyricsSingingIndicator")
            SINGING_BAR_DURATIONS_MS.map { durationMs ->
                transition.animateFloat(
                    initialValue = SINGING_BAR_MIN_FRACTION,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(durationMs), RepeatMode.Reverse),
                    label = "lyricsSingingBar",
                )
            }
        } else {
            null
        }

    Canvas(modifier = Modifier.size(width = 12.dp, height = 12.dp)) {
        val barWidth = 2.5.dp.toPx()
        val gap = (size.width - barWidth * SINGING_BAR_DURATIONS_MS.size) / (SINGING_BAR_DURATIONS_MS.size - 1)
        SINGING_BAR_DURATIONS_MS.indices.forEach { index ->
            val fraction = phases?.get(index)?.value ?: SINGING_BAR_REST_FRACTIONS[index]
            val barHeight = size.height * fraction
            drawRoundRect(
                color = color,
                topLeft = Offset(x = index * (barWidth + gap), y = (size.height - barHeight) / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}

/** The line playing at [positionMs]: the latest-starting line still running, else the latest one already started. */
internal fun List<PreparedLyricsLine>.activeLineIndexAt(positionMs: Long): Int {
    var startedIndex = -1
    var activeIndex = -1
    forEachIndexed { index, line ->
        if (line.startMs <= positionMs) {
            if (startedIndex < 0 || line.startMs >= this[startedIndex].startMs) startedIndex = index
            if (positionMs < line.endMs && (activeIndex < 0 || line.startMs >= this[activeIndex].startMs)) {
                activeIndex = index
            }
        }
    }
    return if (activeIndex >= 0) activeIndex else startedIndex
}

/** True when the karaoke view draws this line on the right: an end-aligned word-timed line, flipped for RTL text. */
internal fun PreparedLyricsLine.rendersRightAligned(isWordSynced: Boolean): Boolean {
    val usesKaraokeAlignment =
        isWordSynced && (main.words.isNotEmpty() || backgrounds.any { track -> track.words.isNotEmpty() })
    val alignsEnd = usesKaraokeAlignment && alignment == LyricsLineAlignment.END
    return alignsEnd != (direction == LyricsTextDirection.RTL)
}

private val SINGER_LANE_HEIGHT = 22.dp
private val SINGER_BUBBLE_GAP = 2.dp
private val SINGER_BUBBLE_INSET = 16.dp
private const val BUBBLE_MAX_WIDTH_FRACTION = 0.72f
private const val BUBBLE_ENTER_SCALE = 0.82f
private const val BUBBLE_EXIT_SCALE = 0.9f
private const val SINGING_BAR_MIN_FRACTION = 0.3f
private val SINGING_BAR_DURATIONS_MS = listOf(420, 560, 380)
private val SINGING_BAR_REST_FRACTIONS = listOf(0.45f, 0.75f, 0.55f)
