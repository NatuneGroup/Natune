package moe.rukamori.archivetune.shared.ui.lyrics

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsCredits
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsLine
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot

/** The settings the fullscreen renderer reads. The host owns persistence and supplies the current values. */
@Stable
data class LyricsSurfaceSettings(
    val mode: LyricsMode = LyricsMode.ENHANCED,
    val tapToSeek: Boolean = true,
    val syncOffsetMs: Long = 0L,
    val reduceMotion: Boolean = false,
    /** Rows away from the sung line shrink, fade and soften with distance. Hosts opt in; the default look is flat. */
    val depthEffects: Boolean = false,
    /** Trailing background vocals move to their own smaller line beneath the lead. Hosts opt in. */
    val backgroundVocals: Boolean = false,
    /** The saved lyrics text size in sp. Hosts pass the stored value; the default keeps each look's tuned size. */
    val textSizeSp: Float = LyricsModeProfile.TEXT_SIZE_DEFAULT_SP,
)

/** The most the fullscreen list is inset above its first row and below its last. */
private val LYRICS_FULLSCREEN_MAX_INSET = 160.dp

/**
 * The top and bottom inset of the fullscreen list: the playhead's distance from the top of the viewport, kept between
 * the row padding and the full inset. A short window keeps its first and last rows reachable at the playhead, and a
 * tall one keeps the full inset. Before the viewport is measured the full inset applies, so the first frame does not move.
 */
internal fun lyricsVerticalInset(viewportHeight: Dp, anchorFraction: Float): Dp =
    if (viewportHeight <= 0.dp) LYRICS_FULLSCREEN_MAX_INSET
    else (viewportHeight * anchorFraction).coerceIn(LYRICS_ROW_PADDING, LYRICS_FULLSCREEN_MAX_INSET)

/** Host callbacks. The renderer never seeks, shares or writes anything on its own. */
@Stable
class LyricsSurfaceActions(
    val onSeek: (positionMs: Long) -> Unit,
    val onShareCard: (ImageBitmap) -> Unit,
)

/**
 * The fullscreen lyric surface for the V2, Enhanced, Spotify and SimpMusic looks. One renderer reads a
 * [LyricsModeProfile] for each look. [playback] is the only clock input, and [romanization] supplies the readings
 * for [sessionKey]. A presentation for another session is ignored.
 */
@Composable
fun LyricsFullscreenRenderer(
    lines: List<LyricsLine>,
    sessionKey: String,
    playback: StateFlow<PlaybackSnapshot?>,
    romanization: LyricsRomanizationState,
    settings: LyricsSurfaceSettings,
    actions: LyricsSurfaceActions,
    modifier: Modifier = Modifier,
    /** The document's credits: songwriters close the list, and machine-made lines carry the badge. */
    credits: LyricsCredits? = null,
    visible: Boolean = true,
    resumed: Boolean = true,
    listState: LazyListState = rememberLazyListState(),
) {
    val snapshot by playback.collectAsState()
    val presentation by romanization.presentation.collectAsState()
    val phonetics = if (presentation.sessionKey == sessionKey) presentation.phonetics else emptyList()
    val rows = remember(lines, phonetics, settings.backgroundVocals) {
        LyricsRenderRows.build(lines, phonetics, splitBackground = settings.backgroundVocals)
    }
    val profile = remember(settings.mode, settings.textSizeSp) { LyricsModeProfile.of(settings.mode, settings.textSizeSp) }
    val palette = lyricsPalette(profile)
    val styles = remember(profile) { LyricsRowStyles(profile) }
    val alignments = remember(rows, settings.mode) { rows.map { LyricsAlignmentPolicy.of(settings.mode, it) } }
    val scanner = remember(rows) { LyricsRenderRows.scanner(rows) }
    val interludes = remember(rows, profile.refinedMotion) {
        if (profile.refinedMotion) LyricsInterludes.byRow(rows) else emptyMap<Int, LyricsInterlude>()
    }
    val frameLoop = LyricsFrameGate.runsFrameLoop(visible, resumed, settings.reduceMotion, snapshot)
    val deadlines = remember(rows, profile.minSweepMs, profile.refinedMotion) {
        LyricsDeadlineSchedule.of(rows, profile.minSweepMs, profile.refinedMotion)
    }
    val clock = rememberLyricsClock(snapshot, settings.syncOffsetMs, LyricsFeedTiming.leadMsFor(rows), frameLoop, deadlines)
    val measurer = rememberTextMeasurer()
    val shapes = remember(rows, measurer) { lyricsShapeCache(rows, measurer) }
    val activeRows by remember(scanner) { derivedStateOf { scanner.activeAt(clock.feedMs.longValue) } }
    val primary = activeRows.lastOrNull() ?: -1

    val scrollOwner = rememberLyricsScrollOwner()
    // Only ENHANCED glides its rows into place on a line change, and reduced motion keeps every row still.
    val settleOn = profile.refinedMotion && !settings.reduceMotion
    val lineSettle = remember(listState) { LyricsLineSettle(listState) }
    // A glide cut short by reduced motion or another look must not leave the rows part way along their path.
    LaunchedEffect(settleOn) { if (!settleOn) lineSettle.clear() }
    val settle = if (settleOn) lineSettle else null
    // A listener reading ahead sees every row sharp, as Apple Music does while the list is dragged.
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    LyricsAutoFollow(
        owner = scrollOwner,
        listState = listState,
        primary = primary,
        discontinuity = clock.discontinuities.intValue,
        reanchor = clock.reanchors.intValue,
        visible = visible,
        reduceMotion = settings.reduceMotion,
        profile = profile,
        settle = settle,
    )
    val scrollConnection = remember(scrollOwner) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) scrollOwner.onListenerScroll()
                return Offset.Zero
            }
        }
    }

    val density = LocalDensity.current
    val horizontalPaddingPx = with(density) { (LYRICS_ROW_PADDING * 2).roundToPx() }
    var rowWidthPx by remember { mutableIntStateOf(0) }
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    val verticalInset = with(density) { lyricsVerticalInset(viewportHeightPx.toDp(), profile.anchorFraction) }
    // Shapes the rows that are about to become active, in slices that yield, so a line change does not pay for
    // its layout inside the draw that starts it.
    LaunchedEffect(shapes, primary, rowWidthPx, styles, alignments, visible) {
        if (visible && primary >= 0 && rowWidthPx > 0) {
            shapes.prewarm(lyricsPrewarmKeys(rows.size, primary, rowWidthPx) { styles.main(alignments[it]) })
        }
    }

    var selection by remember { mutableStateOf(LyricsSelectionState()) }
    var sharePending by remember { mutableStateOf<List<LyricsRenderRow>?>(null) }
    var shareToken by remember { mutableIntStateOf(0) }
    val shareLayer = rememberGraphicsLayer()
    val currentActions by rememberUpdatedState(actions)
    LaunchedEffect(shareToken) {
        if (shareToken == 0) return@LaunchedEffect
        // The card records into the layer while it is drawn, so one frame passes before the capture.
        withFrameNanos { }
        currentActions.onShareCard(shareLayer.toImageBitmap())
        sharePending = null
    }

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxSize()
                .nestedScroll(scrollConnection),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .widthIn(max = profile.maxWidthDp.dp)
                    .fillMaxSize()
                    .lyricsViewportFade()
                    .testTag(LYRICS_LIST_TAG)
                    .onSizeChanged {
                        rowWidthPx = (it.width - horizontalPaddingPx).coerceAtLeast(0)
                        viewportHeightPx = it.height
                    },
                contentPadding = PaddingValues(vertical = verticalInset, horizontal = LYRICS_ROW_PADDING),
            ) {
                items(rows, key = { it.index }) { row ->
                    val alignment = alignments[row.index]
                    val distance = LyricsDepth.distance(row.index, primary, row.index in activeRows)
                    // A row is sharp while the finger is on the list. ENHANCED also keeps rows sharp until the
                    // hold ends, so a listener reading ahead is never shown blurred lines.
                    val holding = dragged || (profile.refinedMotion && !scrollOwner.following)
                    val depth = if (settings.depthEffects && !holding) {
                        // The refined look softens every non-sung row by the same veil, not a per-step ramp.
                        val steps = LyricsDepth.of(
                            distance,
                            reduceMotion = settings.reduceMotion,
                            uniformBlurDp = if (profile.refinedMotion) LyricsDepth.UNIFORM_BLUR_DP else 0f,
                        )
                        // Reduced effects keep the static fade but drop the blur, the costly offscreen layer.
                        if (profile.refinedMotion && settings.reduceMotion) steps.copy(blurDp = 0f) else steps
                    } else {
                        LyricsDepth.Flat
                    }
                    LyricsRowItem(
                        row = row,
                        alignment = alignment,
                        active = row.index in activeRows,
                        selected = row.index in selection.indices,
                        mainStyle = styles.main(alignment),
                        phoneticStyle = styles.phonetic(alignment),
                        palette = palette,
                        clock = clock,
                        shapes = shapes,
                        minSweepMs = profile.minSweepMs,
                        reduceMotion = settings.reduceMotion,
                        depth = depth,
                        depthEnabled = settings.depthEffects,
                        staggerMs = if (profile.refinedMotion) LyricsDepth.staggerMs(distance) else 0L,
                        settle = settle,
                        refined = profile.refinedMotion,
                        interlude = interludes[row.index],
                        activeScale = profile.activeScale,
                        // Only the document's own reading is badged; a host or AI reading is not the
                        // machine-made one.
                        autoCreatedPhonetic = credits?.transliterationAutoCreated == true && row.providerRomanized,
                        autoCreatedTranslation = credits?.translationAutoCreated == true,
                        onTap = {
                            when (LyricsTapPolicy.onTap(row, selection.isActive, settings.tapToSeek)) {
                                is LyricsTapAction.Seek -> {
                                    // A tapped line returns to the playhead, so following resumes with the seek.
                                    scrollOwner.resume()
                                    actions.onSeek(row.timeMs)
                                }
                                LyricsTapAction.ToggleSelection -> selection = selection.toggled(row.index)
                                LyricsTapAction.None -> Unit
                            }
                        },
                        onLongPress = { selection = selection.toggled(row.index) },
                    )
                }
                credits?.takeIf { !it.isEmpty }?.let { documentCredits ->
                    item(key = "lyrics-credits", contentType = "lyrics-credits") {
                        LyricsCreditsFooter(
                            credits = documentCredits,
                            style = styles.phonetic(LyricsLineAlignment.CENTER),
                            palette = palette,
                        )
                    }
                }
            }
        }
        if (selection.isActive) {
            LyricsSelectionBar(
                count = selection.indices.size,
                onShare = {
                    sharePending = selection.sortedIndices().mapNotNull { rows.getOrNull(it) }
                    shareToken += 1
                },
                onClear = { selection = LyricsSelectionState() },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        sharePending?.let { card ->
            LyricsShareCard(
                rows = card,
                layer = shareLayer,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}
