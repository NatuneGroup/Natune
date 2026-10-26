/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.lyrics

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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsLine
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot

/** The settings the fullscreen renderer reads. The host owns persistence and supplies the current values. */
@Stable
data class LyricsSurfaceSettings(
    val mode: LyricsMode = LyricsMode.ENHANCED,
    val tapToSeek: Boolean = true,
    val syncOffsetMs: Long = 0L,
    val reduceMotion: Boolean = false,
)

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
    visible: Boolean = true,
    resumed: Boolean = true,
    listState: LazyListState = rememberLazyListState(),
) {
    val snapshot by playback.collectAsState()
    val presentation by romanization.presentation.collectAsState()
    val phonetics = if (presentation.sessionKey == sessionKey) presentation.phonetics else emptyList()
    val rows = remember(lines, phonetics) { LyricsRenderRows.build(lines, phonetics) }
    val profile = remember(settings.mode) { LyricsModeProfile.of(settings.mode) }
    val palette = lyricsPalette(profile)
    val styles = remember(profile) { LyricsRowStyles(profile) }
    val alignments = remember(rows, settings.mode) { rows.map { LyricsAlignmentPolicy.of(settings.mode, it) } }
    val scanner = remember(rows) { LyricsRenderRows.scanner(rows) }
    val frameLoop = LyricsFrameGate.runsFrameLoop(visible, resumed, settings.reduceMotion, snapshot)
    val clock = rememberLyricsClock(snapshot, settings.syncOffsetMs, LyricsFeedTiming.leadMsFor(rows), frameLoop)
    val measurer = rememberTextMeasurer()
    val shapes = remember(rows, measurer) { lyricsShapeCache(rows, measurer) }
    val activeRows by remember(scanner) { derivedStateOf { scanner.activeAt(clock.feedMs.longValue) } }
    val primary = activeRows.lastOrNull() ?: -1

    val scrollOwner = rememberLyricsScrollOwner()
    LyricsAutoFollow(
        owner = scrollOwner,
        listState = listState,
        primary = primary,
        discontinuity = clock.discontinuities.intValue,
        visible = visible,
        reduceMotion = settings.reduceMotion,
        profile = profile,
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
                    .testTag(LYRICS_LIST_TAG)
                    .onSizeChanged { rowWidthPx = (it.width - horizontalPaddingPx).coerceAtLeast(0) },
                contentPadding = PaddingValues(vertical = 160.dp, horizontal = LYRICS_ROW_PADDING),
            ) {
                items(rows, key = { it.index }) { row ->
                    val alignment = alignments[row.index]
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
                        onTap = {
                            when (LyricsTapPolicy.onTap(row, selection.isActive, settings.tapToSeek)) {
                                is LyricsTapAction.Seek -> actions.onSeek(row.timeMs)
                                LyricsTapAction.ToggleSelection -> selection = selection.toggled(row.index)
                                LyricsTapAction.None -> Unit
                            }
                        },
                        onLongPress = { selection = selection.toggled(row.index) },
                    )
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
