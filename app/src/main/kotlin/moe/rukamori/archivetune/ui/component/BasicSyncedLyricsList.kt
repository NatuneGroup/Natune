package moe.rukamori.archivetune.ui.component

import android.os.SystemClock
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import moe.rukamori.archivetune.LocalAnimationsDisabled
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsCredits
import moe.rukamori.archivetune.LocalBlurPolicy
import moe.rukamori.archivetune.utils.isLowEndDevice

/**
 * Index of the last line whose start time has been reached, or -1 before the first line.
 * [starts] must be sorted ascending, which synced lyrics always are.
 */
internal fun activeSyncedLineIndex(starts: IntArray, position: Int): Int {
    var low = 0
    var high = starts.lastIndex
    var result = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (starts[mid] <= position) {
            result = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return result
}

/** How long auto-scroll must still hold off so it resumes [resumeAfterMs] after the last user drag. */
internal fun syncedLyricsAutoScrollDelayMs(nowMs: Long, lastUserDragMs: Long, resumeAfterMs: Long): Long {
    if (lastUserDragMs <= 0L) return 0L
    return (resumeAfterMs - (nowMs - lastUserDragMs)).coerceAtLeast(0L)
}

/**
 * The synced Enhanced lyrics list. It owns its auto-scroll and draws every row itself, because the library's
 * KaraokeLyricsView cannot draw the feathered sweep, the line-change glide, the distance depth or the interlude dots.
 * List indices match [SyncedLyrics.lines] one to one.
 *
 * [useBlurEffect] is the user's line-blur choice. The distance blur also needs the platform render effect, and it is
 * lifted while [manualScrolling] is true, so the lines being read stay sharp.
 */
@Composable
internal fun BasicSyncedLyricsList(
    listState: LazyListState,
    lyrics: SyncedLyrics,
    currentPosition: () -> Int,
    onLineClicked: (ISyncedLine) -> Unit,
    onLinePressed: (ISyncedLine) -> Unit,
    textColor: Color,
    normalLineTextStyle: TextStyle,
    accompanimentLineTextStyle: TextStyle,
    phoneticTextStyle: TextStyle,
    showTranslation: Boolean,
    showPhonetic: Boolean,
    anchorOffset: Dp,
    viewportHeight: Dp,
    modifier: Modifier = Modifier,
    useBlurEffect: Boolean = false,
    manualScrolling: Boolean = false,
    /** The document's credits, for the writer footer at the end of the list. */
    credits: LyricsCredits? = null,
) {
    val lines = lyrics.lines
    val starts = remember(lines) { IntArray(lines.size) { lines[it].start } }
    val ends = remember(lines) { IntArray(lines.size) { lines[it].end } }
    val interludes = remember(starts, ends) { enhancedInterludes(starts, ends) }
    val wordSynced = remember(lines) { BooleanArray(lines.size) { lines[it].hasWordTiming() } }
    // The lit line opens its activation-lead early; the word sweep still follows the real starts, and
    // a line-synced row activates at its own start — no lead on top of the LRC lead.
    val activationStarts = remember(starts, ends, wordSynced) { enhancedActivationStarts(starts, ends, wordSynced) }
    val activeIndex by remember(activationStarts) {
        derivedStateOf { activeSyncedLineIndex(activationStarts, currentPosition()) }
    }
    // Animations disabled is the one reduced-motion switch: the glide, the depth springs and the scroll animation
    // all turn off together.
    val animationsDisabled = LocalAnimationsDisabled.current
    val context = LocalContext.current
    val lowEnd = remember(context) { context.isLowEndDevice() }
    val blurAllowed =
        EnhancedLyricsMotion.blurAllowed(
            blurPreference = useBlurEffect,
            blurRenderAllowed = LocalBlurPolicy.current.renderEffect,
            animationsDisabled = animationsDisabled,
            lowEnd = lowEnd,
            manualScrolling = manualScrolling,
        )
    val lifecycleOwner = LocalLifecycleOwner.current
    val settle = remember(listState) { EnhancedLineSettle(listState) }
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    var lastUserDragMs by remember(listState) { mutableLongStateOf(0L) }
    // The line the list last followed. Only a single step forward from it is a line change; a seek or a tap is not.
    var followedLine by remember(listState) { mutableIntStateOf(-1) }

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction) lastUserDragMs = SystemClock.uptimeMillis()
        }
    }

    // The glide's frame loop runs only while the screen is resumed, so a backgrounded lyrics screen draws no frames.
    LaunchedEffect(settle, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { settle.runEachSettle() }
    }
    // A hand on the list ends a glide at once, so nothing moves under a finger.
    LaunchedEffect(dragged) {
        if (dragged) settle.clear()
    }

    LaunchedEffect(listState, activeIndex, lastUserDragMs) {
        val target = activeIndex
        if (target !in lines.indices) return@LaunchedEffect
        val stepped = followedLine >= 0 && target == followedLine + 1
        followedLine = target
        // The hold runs from the last touch. A finger or a fling still moving the list keeps follow waiting, so the list
        // is pulled back only once it has been idle for the whole hold.
        var wait = syncedLyricsAutoScrollDelayMs(SystemClock.uptimeMillis(), lastUserDragMs, AUTO_SCROLL_RESUME_MS)
        while (wait > 0L || dragged || listState.isScrollInProgress) {
            if (wait > 0L) {
                delay(wait)
            } else {
                snapshotFlow { dragged || listState.isScrollInProgress }.first { !it }
            }
            wait = syncedLyricsAutoScrollDelayMs(SystemClock.uptimeMillis(), lastUserDragMs, AUTO_SCROLL_RESUME_MS)
        }
        if (listState.isScrollInProgress) return@LaunchedEffect
        val alreadyAnchored = listState.firstVisibleItemIndex == target && listState.firstVisibleItemScrollOffset == 0
        if (alreadyAnchored) return@LaunchedEffect
        val firstFocus = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        // A line change moves the list at once and lets the rows glide from where they were, so the change ripples
        // outward from the sung line. Every other move keeps the single animated scroll, and a seek never ripples.
        val glideFrom = if (stepped && !animationsDisabled && !dragged) listState.itemTopOrNull(target) else null
        if (glideFrom != null) {
            settle.start(target, glideFrom)
            listState.scrollToItem(target)
        } else {
            settle.clear()
            if (animationsDisabled || firstFocus) {
                listState.scrollToItem(target)
            } else {
                // A line at hand eases to the anchor on the fixed 350ms scroll; a far one keeps the
                // distance-covering animation.
                val top = listState.itemTopOrNull(target)
                if (top != null) {
                    val delta = top - listState.layoutInfo.beforeContentPadding
                    if (delta != 0) {
                        listState.animateScrollBy(
                            value = delta.toFloat(),
                            animationSpec = tween(AUTO_SCROLL_MS, easing = AUTO_SCROLL_EASE),
                        )
                        if (listState.itemTopOrNull(target) != listState.layoutInfo.beforeContentPadding) {
                            listState.scrollToItem(target)
                        }
                    }
                } else {
                    listState.animateScrollToItem(target)
                }
            }
        }
    }

    val bottomPadding = (viewportHeight - anchorOffset).coerceAtLeast(0.dp)
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(top = anchorOffset, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(LINE_SPACING),
        modifier = modifier,
    ) {
        itemsIndexed(lines) { index, line ->
            val distance = EnhancedLyricsMotion.distance(index, activeIndex)
            BasicSyncedLyricLine(
                line = line,
                isActive = index == activeIndex,
                depth = EnhancedLyricsMotion.depthOf(distance, reduceMotion = animationsDisabled),
                settle = settle,
                settleDelayMs = EnhancedLyricsMotion.staggerDelayMs(distance),
                interlude = interludes[index],
                currentPosition = currentPosition,
                blurAllowed = blurAllowed,
                onClick = {
                    // A tap is a seek, and it also resumes following at once, even inside the drag hold. Forgetting the
                    // followed line makes the next follow start fresh, so the jump does not glide.
                    followedLine = -1
                    lastUserDragMs = 0L
                    onLineClicked(line)
                },
                onLongClick = { onLinePressed(line) },
                textColor = textColor,
                normalLineTextStyle = normalLineTextStyle,
                accompanimentLineTextStyle = accompanimentLineTextStyle,
                phoneticTextStyle = phoneticTextStyle,
                showTranslation = showTranslation,
                showPhonetic = showPhonetic,
                animationsDisabled = animationsDisabled,
            )
        }
        if (credits != null && !credits.isEmpty) {
            item {
                EnhancedCreditsFooter(
                    credits = credits,
                    textColor = textColor,
                    style = phoneticTextStyle,
                )
            }
        }
    }
}

@Composable
private fun BasicSyncedLyricLine(
    line: ISyncedLine,
    isActive: Boolean,
    depth: EnhancedDepth,
    settle: EnhancedLineSettle,
    settleDelayMs: Long,
    interlude: EnhancedInterlude?,
    currentPosition: () -> Int,
    blurAllowed: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    textColor: Color,
    normalLineTextStyle: TextStyle,
    accompanimentLineTextStyle: TextStyle,
    phoneticTextStyle: TextStyle,
    showTranslation: Boolean,
    showPhonetic: Boolean,
    animationsDisabled: Boolean,
) {
    val depthSpec =
        if (animationsDisabled) {
            snap<Float>()
        } else {
            spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow)
        }
    val lineAlpha by animateFloatAsState(
        targetValue = if (isActive) 1f else EnhancedLyricsMotion.INACTIVE_ALPHA,
        animationSpec = tween(durationMillis = if (animationsDisabled) 0 else 240),
        label = "enhanced-line-alpha",
    )
    val depthAlpha by animateFloatAsState(depth.alpha, depthSpec, label = "enhanced-depth-alpha")
    val depthScale by animateFloatAsState(depth.scale, depthSpec, label = "enhanced-depth-scale")
    val depthBlur by animateFloatAsState(
        if (blurAllowed) depth.blurDp else 0f,
        depthSpec,
        label = "enhanced-depth-blur",
    )
    // The quick pop the sung line takes on activation; reduced motion keeps the line's size.
    val activePop by animateFloatAsState(
        targetValue = if (isActive && !animationsDisabled) ACTIVE_POP_SCALE else 1f,
        animationSpec = if (animationsDisabled) snap() else tween(ACTIVE_POP_MS),
        label = "enhanced-active-pop",
    )
    val karaoke = line as? KaraokeLine
    val endAligned = karaoke?.alignment == KaraokeAlignment.End
    val horizontal = if (endAligned) Alignment.End else Alignment.Start
    val textAlign = if (endAligned) TextAlign.End else TextAlign.Start
    Column(
        horizontalAlignment = horizontal,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 24.dp, vertical = 6.dp)
                // The scale grows from the aligned edge so a start-aligned line does not drift sideways. Scale, fade and
                // the glide are read in the draw phase, so a frame of them does not recompose the row.
                .graphicsLayer {
                    scaleX = depthScale * activePop
                    scaleY = depthScale * activePop
                    alpha = lineAlpha * depthAlpha
                    transformOrigin = TransformOrigin(if (endAligned) 1f else 0f, 0.5f)
                    translationY = settle.offsetPx(settleDelayMs)
                }
                .then(
                    if (depthBlur > EnhancedLyricsMotion.BLUR_VISIBLE_DP) {
                        Modifier.blur(depthBlur.dp, BlurredEdgeTreatment.Unbounded)
                    } else {
                        Modifier
                    },
                ),
    ) {
        interlude?.let {
            EnhancedInterludeDots(
                interlude = it,
                currentPosition = currentPosition,
                color = textColor,
                reduceMotion = animationsDisabled,
            )
        }
        if (showPhonetic && karaoke != null) {
            val phonetic =
                (karaoke as? KaraokeLine.MainKaraokeLine)?.phonetic?.takeIf { it.isNotBlank() }
                    ?: karaoke.syllables
                        .mapNotNull { it.phonetic?.trim() }
                        .filter { it.isNotEmpty() }
                        .joinToString(" ")
            if (phonetic.isNotEmpty()) {
                Text(text = phonetic, style = phoneticTextStyle, color = textColor.copy(alpha = 0.75f), textAlign = textAlign)
            }
        }
        when {
            karaoke != null && isActive ->
                EnhancedSweptLine(
                    syllables = karaoke.syllables,
                    style = normalLineTextStyle.copy(textAlign = textAlign),
                    textColor = textColor,
                    reduceMotion = animationsDisabled,
                    currentPosition = currentPosition,
                )
            karaoke != null ->
                Text(text = karaoke.syllables.joinSyllableContents(), style = normalLineTextStyle, color = textColor, textAlign = textAlign)
            line is SyncedLine ->
                Text(text = line.content, style = normalLineTextStyle, color = textColor, textAlign = textAlign)
        }
        (karaoke as? KaraokeLine.MainKaraokeLine)?.accompanimentLines?.forEach { accompaniment ->
            val accompanimentLine = accompaniment as? KaraokeLine
            val accompanimentSyllables = accompanimentLine?.syllables.orEmpty()
            val accompanimentText = accompanimentSyllables.joinSyllableContents()
            when {
                accompanimentText.isBlank() -> Unit
                // Background vocals carry their own word times, so on the sung row they sweep and lift over
                // their own spans like the main voice instead of sitting as plain text.
                isActive && accompanimentLine?.hasWordTiming() == true ->
                    EnhancedSweptLine(
                        syllables = accompanimentSyllables,
                        style = accompanimentLineTextStyle.copy(textAlign = textAlign),
                        textColor = textColor.copy(alpha = textColor.alpha * 0.6f),
                        reduceMotion = animationsDisabled,
                        currentPosition = currentPosition,
                    )
                else ->
                    Text(
                        text = accompanimentText,
                        style = accompanimentLineTextStyle,
                        color = textColor.copy(alpha = 0.6f),
                        textAlign = textAlign,
                    )
            }
        }
        if (showTranslation) {
            TranslationStack(
                translation = karaoke?.translation ?: (line as? SyncedLine)?.translation,
                textColor = textColor,
                phoneticTextStyle = phoneticTextStyle,
                isActive = isActive,
                reduceMotion = animationsDisabled,
            )
        }
    }
}

/** Where the row starts in the viewport now, or null while it is not laid out. */
private fun LazyListState.itemTopOrNull(index: Int): Int? =
    layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.offset

private const val AUTO_SCROLL_RESUME_MS = 4_500L
private const val AUTO_SCROLL_MS = 350
private const val ACTIVE_POP_MS = 100
private const val ACTIVE_POP_SCALE = 1.05f

/** The one animated scroll speed of a line change: a fixed ease-in-out over [AUTO_SCROLL_MS]. */
private val AUTO_SCROLL_EASE = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
private val LINE_SPACING = 4.dp

/** The mark a machine-made translation carries. */
private const val AUTO_CREATED_MARK = "✦ "

/**
 * The credits block at the end of the list: the songwriters the document names, and the badge a
 * machine-made translation or transliteration earns, both dim and small under the last line.
 */
@Composable
private fun EnhancedCreditsFooter(
    credits: LyricsCredits,
    textColor: Color,
    style: TextStyle,
) {
    if (credits.isEmpty) return
    val autoCreated = credits.translationAutoCreated || credits.transliterationAutoCreated
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (credits.songwriters.isNotEmpty()) {
            Text(
                text = stringResource(R.string.lyrics_written_by, credits.songwriters.joinToString(", ")),
                style = style,
                color = textColor.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )
        }
        if (autoCreated) {
            Text(
                text = AUTO_CREATED_MARK + stringResource(R.string.lyrics_auto_created),
                style = style,
                color = textColor.copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
            )
        }
    }
}
