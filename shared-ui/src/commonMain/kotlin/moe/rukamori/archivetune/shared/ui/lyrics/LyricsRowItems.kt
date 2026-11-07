package moe.rukamori.archivetune.shared.ui.lyrics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.resources.lyrics_auto_created
import moe.rukamori.archivetune.shared.ui.resources.lyrics_clear_selection
import moe.rukamori.archivetune.shared.ui.resources.lyrics_selected_count
import moe.rukamori.archivetune.shared.ui.resources.lyrics_share
import org.jetbrains.compose.resources.stringResource

/** Horizontal inset of each lyric row. The shaping width is the list width minus twice this inset. */
internal val LYRICS_ROW_PADDING = 16.dp

private const val LINE_HEIGHT_FACTOR = 1.25f
private const val PHONETIC_SIZE_RATIO = 0.55f
private const val SELECTED_ROW_ALPHA = 0.16f
private const val BACKGROUND_SIZE_RATIO = 0.7f
private const val BACKGROUND_ACTIVE_ALPHA = 0.72f

/** The document's second voice: an answer to the lead line, not another lead line. */
private const val SECOND_VOICE_SIZE_RATIO = 0.82f

/**
 * The soft dark lift under every sung glyph. Artwork behind the rows can be bright or busy, so a
 * short-radius shadow keeps the edges reading on any palette without a visible outline.
 */
private val LYRICS_TEXT_SHADOW =
    Shadow(
        color = Color.Black.copy(alpha = 0.45f),
        offset = Offset(0f, 1.5f),
        blurRadius = 6f,
    )

/** One lyric row: the swept line with its reading and translation beneath. Tap and long press are host-decided. */
@Composable
internal fun LyricsRowItem(
    row: LyricsRenderRow,
    alignment: LyricsLineAlignment,
    active: Boolean,
    /** A synced row behind the playhead keeps a faded active colour instead of the idle dim. */
    passed: Boolean = false,
    selected: Boolean,
    mainStyle: TextStyle,
    phoneticStyle: TextStyle,
    palette: LyricsPalette,
    clock: LyricsClock,
    shapes: LyricsShapeCache<LyricsShapeKey, LyricsShapedRow>,
    minSweepMs: Long,
    reduceMotion: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    depth: LyricsDepth = LyricsDepth.Flat,
    depthEnabled: Boolean = false,
    /** Waits before a line change reaches the row. Depth and the settle share it, so they ripple together. */
    staggerMs: Long = 0L,
    /** The line-change glide of the scroll. Null on the looks that keep the single animated scroll. */
    settle: LyricsLineSettle? = null,
    refined: Boolean = false,
    interlude: LyricsInterlude? = null,
    /** The quick scale the sung line pops to, like [LyricsModeProfile.activeScale]; 1 keeps its size. */
    activeScale: Float = 1f,
    /** Machine-made readings/translations carry the badge when the document says so. */
    autoCreatedPhonetic: Boolean = false,
    autoCreatedTranslation: Boolean = false,
) {
    // Unsynced text has no timing to follow, so it reads at full strength rather than as an idle line.
    val linePalette = if (row.isSynced) palette else palette.copy(dim = palette.bright)
    // The document's second voice answers the lead line: its own row, its own sweep, drawn as an answer.
    val secondVoiceStyle = remember(mainStyle) { lyricsSecondVoiceStyle(mainStyle) }
    Column(
        Modifier
            .fillMaxWidth()
            .then(
                if (depthEnabled) {
                    Modifier.lyricsDepth(depth, alignment, animate = !reduceMotion, staggerMs = staggerMs)
                } else {
                    Modifier
                },
            )
            .then(if (settle != null) Modifier.lyricsSettle(settle, row.index, staggerMs) else Modifier)
            .then(Modifier.lyricsActiveScale(active, activeScale, animate = !reduceMotion, alignment))
            .testTag(lyricsRowTag(row.index))
            .background(if (selected) palette.glow.copy(alpha = SELECTED_ROW_ALPHA) else Color.Transparent)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .padding(vertical = 8.dp),
        horizontalAlignment = alignment.horizontal(),
    ) {
        interlude?.let {
            LyricsInterludeDots(interlude = it, clock = clock, color = palette.bright, reduceMotion = reduceMotion)
        }
        SweptLyricLine(
            row = row,
            style = if (row.isSecondVoice) secondVoiceStyle else mainStyle,
            palette = linePalette,
            active = active,
            passed = passed,
            clock = clock,
            shapes = shapes,
            minSweepMs = minSweepMs,
            reduceMotion = reduceMotion,
            refined = refined,
            modifier = Modifier.fillMaxWidth(),
        )
        row.background?.let { background ->
            val backgroundStyle = remember(mainStyle) { lyricsBackgroundStyle(mainStyle) }
            Text(
                text = background,
                style = backgroundStyle,
                color = if (active) palette.bright.copy(alpha = BACKGROUND_ACTIVE_ALPHA) else palette.dim,
            )
        }
        val autoCreatedLabel = stringResource(Res.string.lyrics_auto_created)
        row.phonetic?.let {
            RevealedSubline(
                text = it,
                style = phoneticStyle,
                color = palette.dim,
                reduceMotion = reduceMotion,
                autoCreated = autoCreatedPhonetic,
                autoCreatedLabel = autoCreatedLabel,
            )
        }
        row.translation?.let {
            RevealedSubline(
                text = it,
                style = phoneticStyle,
                color = palette.dim,
                reduceMotion = reduceMotion,
                autoCreated = autoCreatedTranslation,
                autoCreatedLabel = autoCreatedLabel,
            )
        }
    }
}

/** The reading and translation slide in under the line over ~0.6s when the line activates. */
private const val REVEAL_MS = 600

/** The mark a machine-made reading or translation carries, named for screen readers. */
private const val AUTO_CREATED_MARK = "✦ "

/**
 * One revealed line: it slides in when its text publishes to its row — the row may not be the sung one,
 * as a listener reading ahead still sees every reading — and it stays while the row stays composed.
 * Reduced motion shows it without the slide.
 */
@Composable
private fun RevealedSubline(
    text: String,
    style: TextStyle,
    color: Color,
    reduceMotion: Boolean,
    autoCreated: Boolean,
    autoCreatedLabel: String,
) {
    val reveal = remember(text) { MutableTransitionState(false) }
    LaunchedEffect(text) {
        reveal.targetState = true
    }
    AnimatedVisibility(
        visibleState = reveal,
        enter = if (reduceMotion) {
            EnterTransition.None
        } else {
            expandVertically(expandFrom = Alignment.Top, animationSpec = tween(REVEAL_MS)) +
                fadeIn(animationSpec = tween(REVEAL_MS))
        },
    ) {
        Row {
            if (autoCreated) {
                Text(
                    text = AUTO_CREATED_MARK,
                    style = style,
                    color = color,
                    modifier = Modifier.semantics { contentDescription = autoCreatedLabel },
                )
            }
            Text(text = text, style = style, color = color)
        }
    }
}

/**
 * Scale, fade and softening by distance from the sung line. The scale grows from the row's aligned edge so a
 * start-aligned line does not drift sideways. Without reduced motion the change follows a soft spring, held back by
 * [staggerMs] so a line change ripples outward from the sung line. Scale and alpha are read in the draw phase, so a
 * spring frame does not recompose the row. The blur radius is composed, because the blur modifier has to exist.
 */
@Composable
private fun Modifier.lyricsDepth(
    depth: LyricsDepth,
    alignment: LyricsLineAlignment,
    animate: Boolean,
    staggerMs: Long,
): Modifier {
    val scaleAnim = remember { Animatable(depth.scale) }
    val alphaAnim = remember { Animatable(depth.alpha) }
    val blurAnim = remember { Animatable(depth.blurDp) }
    LaunchedEffect(depth, animate, staggerMs) {
        if (!animate) {
            scaleAnim.snapTo(depth.scale)
            alphaAnim.snapTo(depth.alpha)
            blurAnim.snapTo(depth.blurDp)
            return@LaunchedEffect
        }
        if (staggerMs > 0L) delay(staggerMs)
        val spec = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow)
        launch { scaleAnim.animateTo(depth.scale, spec) }
        launch { alphaAnim.animateTo(depth.alpha, spec) }
        launch { blurAnim.animateTo(depth.blurDp, spec) }
    }
    val originX = when (alignment) {
        LyricsLineAlignment.START -> 0f
        LyricsLineAlignment.CENTER -> 0.5f
        LyricsLineAlignment.END -> 1f
    }
    return graphicsLayer {
        scaleX = scaleAnim.value
        scaleY = scaleAnim.value
        alpha = alphaAnim.value
        transformOrigin = TransformOrigin(originX, 0.5f)
    }.then(
        if (blurAnim.value > BLUR_THRESHOLD_DP) {
            Modifier.blur(blurAnim.value.dp, BlurredEdgeTreatment.Unbounded)
        } else {
            Modifier
        },
    )
}

/**
 * The line-change glide. The row is drawn where it was before the list moved, and eases to its place after [staggerMs].
 * The offset is read in the draw phase, so a frame of the glide does not recompose the row.
 */
private fun Modifier.lyricsSettle(settle: LyricsLineSettle, rowIndex: Int, staggerMs: Long): Modifier =
    graphicsLayer { translationY = settle.offsetPx(rowIndex, staggerMs) }

private const val ACTIVE_SCALE_MS = 100

/**
 * The quick pop the sung line takes on activation. It scales from the edge its text starts at, so it
 * grows out of the line instead of swelling from the middle.
 */
@Composable
private fun Modifier.lyricsActiveScale(
    active: Boolean,
    targetScale: Float,
    animate: Boolean,
    alignment: LyricsLineAlignment,
): Modifier {
    val scale by animateFloatAsState(
        targetValue = if (active && targetScale > 1f) targetScale else 1f,
        animationSpec = if (animate) tween(ACTIVE_SCALE_MS) else snap(),
        label = "lyrics-active-scale",
    )
    if (targetScale <= 1f) return this
    val originX = when (alignment) {
        LyricsLineAlignment.START -> 0f
        LyricsLineAlignment.CENTER -> 0.5f
        LyricsLineAlignment.END -> 1f
    }
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
        transformOrigin = TransformOrigin(originX, 0.5f)
    }
}

/** Below this the softening is invisible, and skipping it spares the row an offscreen layer. */
private const val BLUR_THRESHOLD_DP = 0.05f

/** Background vocals sit beneath the lead line, smaller and lighter, as Apple Music sets them. */
internal fun lyricsBackgroundStyle(mainStyle: TextStyle): TextStyle =
    mainStyle.copy(
        fontSize = if (mainStyle.fontSize.isSpecified) mainStyle.fontSize * BACKGROUND_SIZE_RATIO else mainStyle.fontSize,
        lineHeight = if (mainStyle.lineHeight.isSpecified) mainStyle.lineHeight * BACKGROUND_SIZE_RATIO else mainStyle.lineHeight,
        fontWeight = FontWeight.SemiBold,
    )

/**
 * The document's second voice — the backing vocal a lead line is answered by. It is a line of its own, so
 * it keeps its own sweep, but it reads as an answer rather than as another lead: smaller than the line it
 * answers, the same way the background row does.
 */
internal fun lyricsSecondVoiceStyle(mainStyle: TextStyle): TextStyle =
    mainStyle.copy(
        fontSize = if (mainStyle.fontSize.isSpecified) mainStyle.fontSize * SECOND_VOICE_SIZE_RATIO else mainStyle.fontSize,
        lineHeight = if (mainStyle.lineHeight.isSpecified) mainStyle.lineHeight * SECOND_VOICE_SIZE_RATIO else mainStyle.lineHeight,
        fontWeight = FontWeight.SemiBold,
    )

/** The bar shown while lines are selected. It holds the count and the share and clear actions. */
@Composable
internal fun LyricsSelectionBar(
    count: Int,
    onShare: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(Res.string.lyrics_selected_count, count),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row {
            TextButton(onClick = onClear) { Text(stringResource(Res.string.lyrics_clear_selection)) }
            TextButton(onClick = onShare) { Text(stringResource(Res.string.lyrics_share)) }
        }
    }
}

/** The stable test tag of one lyric row, so tests can find it by index. */
internal fun lyricsRowTag(index: Int): String = "lyrics-row-$index"

/** The stable test tag of the fullscreen lyric list, so tests can scroll it. */
internal const val LYRICS_LIST_TAG = "lyrics-list"

@Composable
internal fun lyricsPalette(profile: LyricsModeProfile): LyricsPalette {
    val colors = MaterialTheme.colorScheme
    return remember(profile, colors) {
        LyricsPalette(
            dim = colors.onSurface.copy(alpha = profile.idleAlpha),
            bright = colors.onSurface,
            glow = colors.primary,
        )
    }
}

internal fun lyricsMainStyle(profile: LyricsModeProfile, alignment: LyricsLineAlignment): TextStyle =
    TextStyle(
        fontSize = profile.mainSizeSp.sp,
        lineHeight = (profile.mainSizeSp * profile.lineSpacingFactor * LINE_HEIGHT_FACTOR).sp,
        fontWeight = FontWeight.Bold,
        textAlign = alignment.textAlign(),
        shadow = LYRICS_TEXT_SHADOW,
    )

/** The reading and translation rows sit at 0.55 of the main size, as the Android renderers set them. */
internal fun lyricsPhoneticStyle(profile: LyricsModeProfile, alignment: LyricsLineAlignment): TextStyle =
    TextStyle(fontSize = (profile.mainSizeSp * PHONETIC_SIZE_RATIO).sp, textAlign = alignment.textAlign(), shadow = LYRICS_TEXT_SHADOW)

/**
 * The text styles of one look, one pair per alignment. A row is shaped in the style it is drawn with, so the shape
 * cache key and the drawn text always agree.
 */
internal class LyricsRowStyles(profile: LyricsModeProfile) {
    private val main = LyricsLineAlignment.entries.associateWith { lyricsMainStyle(profile, it) }
    private val phonetic = LyricsLineAlignment.entries.associateWith { lyricsPhoneticStyle(profile, it) }

    fun main(alignment: LyricsLineAlignment): TextStyle = main.getValue(alignment)

    fun phonetic(alignment: LyricsLineAlignment): TextStyle = phonetic.getValue(alignment)
}

private fun LyricsLineAlignment.textAlign(): TextAlign =
    when (this) {
        LyricsLineAlignment.START -> TextAlign.Start
        LyricsLineAlignment.CENTER -> TextAlign.Center
        LyricsLineAlignment.END -> TextAlign.End
    }

private fun LyricsLineAlignment.horizontal(): Alignment.Horizontal =
    when (this) {
        LyricsLineAlignment.START -> Alignment.Start
        LyricsLineAlignment.CENTER -> Alignment.CenterHorizontally
        LyricsLineAlignment.END -> Alignment.End
    }
