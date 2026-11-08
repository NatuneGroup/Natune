package moe.rukamori.archivetune.ui.component

// SPDX-License-Identifier: GPL-3.0-only
// Portions © Rukamori — github.com/rukamori
// Portions © vossgraves — github.com/vossgraves

import android.app.Activity
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import moe.rukamori.archivetune.LocalAnimationsDisabled
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.ui.player.LocalLyricsScrollListener
import moe.rukamori.archivetune.ui.player.LocalLyricsSelectionListener
import moe.rukamori.archivetune.constants.LyricsClickKey
import moe.rukamori.archivetune.constants.LyricsLineBlurKey
import moe.rukamori.archivetune.constants.LyricsTextSizeKey
import moe.rukamori.archivetune.db.entities.LyricsEntity
import moe.rukamori.archivetune.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import moe.rukamori.archivetune.lyrics.LyricsDeadlineSchedule
import moe.rukamori.archivetune.lyrics.LyricsUtils.isLineSyncedLrc
import moe.rukamori.archivetune.lyrics.LyricsUtils.isTtml
import moe.rukamori.archivetune.lyrics.LyricsRenderTiming
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsParser
import moe.rukamori.archivetune.shared.ui.lyrics.LyricsSweepGeometry
import moe.rukamori.archivetune.shared.ui.lyrics.lyricsIdleParkMs
import moe.rukamori.archivetune.ui.component.shimmer.ShimmerHost
import moe.rukamori.archivetune.ui.component.shimmer.TextPlaceholder
import moe.rukamori.archivetune.ui.theme.rememberArchiveTuneLyricsFontFamily
import moe.rukamori.archivetune.utils.rememberPreference
import kotlin.math.abs

private const val LYRIC_VISUAL_TUNING_OFFSET_MS = LyricsRenderTiming.VISUAL_TUNING_OFFSET_MS

/** The slowest idle re-poll. */
private const val IDLE_POLL_MS = 250L
private const val MANUAL_SCROLL_TIMEOUT_MS = 4500L
private const val MANUAL_SCROLL_DEBOUNCE_MS = 50L
// Sentinels for the first-focus collector: the user took over the list, or no line index is
// published yet.
private const val LYRIC_FOCUS_TARGET_MANUAL = -2
private const val LYRIC_FOCUS_TARGET_PENDING = -1
// The lines stay transparent until the active one has been placed, then fade in
// over this long. See `awaitingFirstFocus`.
private const val LYRIC_FIRST_FOCUS_FADE_MS = 200
// Hard ceiling on how long the lines may stay hidden waiting for that placement.
private const val LYRIC_FIRST_FOCUS_TIMEOUT_MS = 900L
// Minimum position jump in either direction (in ms) that we treat as a "reset"
// trigger — large enough to ride through ExoPlayer's normal position jitter
// (which is well under 200ms even on a stuttering device) and minor playback
// corrections, small enough to catch any real seek or REPEAT_MODE_ONE wrap
// (which is typically a jump from near the end of the song back to 0, i.e. minutes).
private const val POSITION_RESET_THRESHOLD_MS = 1000L

/**
 * The existing Spotify and compact TikTok lyrics renderer.
 *
 * @param singleActiveLine when true, renders ONLY the active line cluster — the
 *   main karaoke line (word-timed sweep), its per-word phonetic (romanisation)
 *   and its translation — cross-fading between lines as playback advances.
 *   Designed for compact surfaces like the TikTok player strip, where previous
 *   and upcoming lines must not be visible and no line-list scrolling applies.
 */
@OptIn(
    ExperimentalFoundationApi::class,
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
)
@Composable
fun LyricsAlternate(
    sliderPositionProvider: () -> Long?,
    lyricsSyncOffset: Int,
    modifier: Modifier = Modifier,
    textColorOverride: Color? = null,
    lyricsLineBlurOverride: Boolean? = null,
    spotifyStyle: Boolean = false,
    // Non-null when the caller is not a full screen. The SimpMusic style embeds this in a 300dp
    // card, where the user's full-screen size fits about four words in the box.
    textSizeOverride: Float? = null,
    singleActiveLine: Boolean = false,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val player = playerConnection.player
    val context = LocalContext.current
    val animationsDisabled = LocalAnimationsDisabled.current

    // collectAsStateWithLifecycle: pauses StateFlow collection when the
    // composable's lifecycle drops below STARTED (e.g. user navigates away
    // from the player, or the app goes to background). Without this, these
    // flows continue pushing values into state during background playback,
    // causing recompositions that compete with the karaoke frame loop for
    // main-thread time when the user returns. This is especially important
    // for `currentLyrics` below, which can emit during background lyrics
    // prefetch / AI translation completion.
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val playbackParameters by playerConnection.playbackParameters.collectAsStateWithLifecycle()

    val (lyricsClick) = rememberPreference(LyricsClickKey, defaultValue = true)
    val (lyricsTextSizePreference) = rememberPreference(LyricsTextSizeKey, defaultValue = 26f)
    val lyricsTextSize = textSizeOverride ?: lyricsTextSizePreference
    // Per-line RenderEffect blur (see useBlurEffect below) is the single heaviest
    // per-frame cost in the karaoke view -- default it OFF so word-synced lyrics
    // are smooth out of the box; users who want the effect can re-enable it in
    // Settings > Lyrics.
    val (lyricsLineBlurPreference) = rememberPreference(LyricsLineBlurKey, defaultValue = false)
    val lyricsFontFamily = rememberArchiveTuneLyricsFontFamily()

    // Apple Music style and all lyrics backgrounds always use white text so lyrics stay
    // readable on the dark blurred backdrop regardless of system theme.
    val textColor = textColorOverride ?: Color.White
    val lyricsLineBlur = (lyricsLineBlurOverride ?: lyricsLineBlurPreference) && moe.rukamori.archivetune.LocalBlurPolicy.current.any

    var isSelectionModeActive by rememberSaveable { mutableStateOf(false) }
    val selectedLineKeys = remember { mutableStateListOf<String>() }
    var showMaxSelectionToast by remember { mutableStateOf(false) }
    val maxSelectionLimit = 5
    var showShareDialog by remember { mutableStateOf(false) }
    var shareDialogData by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var showShareImageDialog by remember { mutableStateOf(false) }

    val currentLyrics by playerConnection.currentLyrics.collectAsStateWithLifecycle(initialValue = null)
    val lyrics =
        remember(currentLyrics, mediaMetadata?.id) {
            currentLyrics
                ?.takeIf { lyricsEntity -> lyricsEntity.id == mediaMetadata?.id }
                ?.lyrics
        }
    val showTranslations =
        remember(currentLyrics?.source) {
            currentLyrics?.source in setOf(LyricsEntity.Source.AI_TRANSLATION.value, LyricsEntity.Source.TRANSLATION.value)
        }

    // Restart tick: bumps when the SAME song restarts (auto-repeat, manual replay, seek-to-zero)
    // so the karaoke subtree tears down and re-animates from the beginning. Without this,
    // mediaId + lyrics text are unchanged on restart, so lyricsSessionKey stays the same and
    // all per-session Animatable instances retain their settled values — no intro animation replays.
    val playbackState by playerConnection.playbackState.collectAsStateWithLifecycle()
    var restartTick by remember { mutableIntStateOf(0) }
    var lastPlaybackState by remember { mutableStateOf(Player.STATE_READY) }
    LaunchedEffect(mediaMetadata?.id, playbackState) {
        if (playbackState == Player.STATE_READY &&
            lastPlaybackState == Player.STATE_ENDED &&
            player.currentPosition < 1_000L
        ) {
            restartTick++
        } else if (playbackState == Player.STATE_READY &&
            lastPlaybackState == Player.STATE_BUFFERING &&
            player.currentPosition < 500L &&
            restartTick > 0
        ) {
            // Manual replay from the queue / notification while the song was already playing.
            // Only count it as a restart if we've already started once (restartTick > 0)
            // to avoid firing on the very first load.
            restartTick++
        }
        lastPlaybackState = playbackState
    }
    val lyricsSessionKey =
        remember(mediaMetadata?.id, lyrics, restartTick) {
            Triple(mediaMetadata?.id.orEmpty(), lyrics, restartTick)
        }

    val isSynced = remember(lyrics) { lyrics != null && (isLineSyncedLrc(lyrics!!) || isTtml(lyrics!!)) }
    val isTtmlFormat = remember(lyrics) { lyrics != null && isTtml(lyrics!!) }
    // The document's credits: songwriters close the list; machine-made lines carry the badge.
    val lyricsCredits =
        remember(lyrics) {
            lyrics?.takeIf { isTtml(it) }?.let { LyricsParser.parseTtmlCredits(it) }
        }

    val data = rememberAlternateLyricsData(lyrics, mediaMetadata?.id)
    val parsedEntries = data.entries
    val lyricsEntries = parsedEntries.orEmpty()
    val syncedLyrics = data.lyrics
    val karaokeGeneration = data.generation
    val romanizationPreferences = data.romanizationPreferences

    // The lead follows the timing shape, not the container: enhanced LRC with per-word stamps lines
    // up with the audio like TTML, so it takes no LRC lead (which used to run its sweep ~450 ms early).
    val leadMs = remember(lyricsEntries) { LyricsRenderTiming.leadMsFor(lyricsEntries) }

    val latestSliderPositionProvider = rememberUpdatedState(sliderPositionProvider)
    val latestLyricsSyncOffset = rememberUpdatedState(lyricsSyncOffset)
    val latestLeadMs = rememberUpdatedState(leadMs)
    val latestPlaybackSpeed = rememberUpdatedState(playbackParameters.speed)
    val playbackPositionMs =
        remember(player) {
            mutableLongStateOf(player.currentPosition.coerceAtLeast(0L))
        }
    // What the pane draws from. The loop anchors this clock once per frame and the pane projects it at
    // draw time, so a frame shows the position of the instant it is painted at rather than the value
    // the frame before it published. See LyricsPositionProjection.
    val drawClock = remember(player) { EnhancedLyricsDrawClock() }
    // Bumped once per frame the loop wants drawn; it is the draw-phase subscription, so an unchanged
    // parked frame asks for no frame at all.
    val frameTick = remember(player) { mutableIntStateOf(0) }
    val playbackSyncPosition: () -> Int =
        remember(drawClock, frameTick) {
            {
                frameTick.intValue
                (
                    drawClock.positionMs() +
                        latestLyricsSyncOffset.value.toLong() +
                        latestLeadMs.value +
                        LYRIC_VISUAL_TUNING_OFFSET_MS
                ).coerceIn(0L, Int.MAX_VALUE.toLong())
                    .toInt()
            }
        }
    val currentLineIndexState = remember { mutableIntStateOf(-1) }

    // Single-active-line mode (TikTok strip): pick the currently-sung line out of the
    // fully-built karaoke model. Metadata lines (header at start<0, footer at start>=1h) are
    // skipped so the strip only ever shows real lyrics; before the first line and after the
    // last one it renders nothing.
    val activeKaraokeLine =
        remember(syncedLyrics, currentLineIndexState.intValue, singleActiveLine) {
            if (!singleActiveLine) {
                null
            } else {
                val index = currentLineIndexState.intValue
                val line = syncedLyrics.lines.getOrNull(index)
                if (line != null && line.start >= 0 && line.start < 86_400_000) line else null
            }
        }

    // rememberUpdatedState so the playback loop sees the latest syncedLyrics (which can be
    // rebuilt when romanization finishes) without re-launching the loop and stuttering the
    // 60 Hz position interpolation.
    val latestSyncedLyrics = rememberUpdatedState(syncedLyrics)
    // Bumped on a backward position jump of over a second (a REPEAT_MODE_ONE wrap or a backward seek). The list state,
    // the glide and the sweep are keyed on it, so they restart at the current line rather than the one before the jump.
    var positionResetCounter by remember { mutableIntStateOf(0) }
    var isManualScrolling by remember { mutableStateOf(false) }
    var lastManualScrollTime by remember { mutableLongStateOf(0L) }
    // The scroll state is recreated together with the subtree that owns it.
    val listState = key(lyricsSessionKey, positionResetCounter, karaokeGeneration) { rememberLazyListState() }

    // ── First-frame placement ── A fresh LazyListState starts at line 0 and the list walks it to
    // the active line; the lines stay hidden until the collector below sees it settle.
    var awaitingFirstFocus by
        remember(lyricsSessionKey, positionResetCounter, karaokeGeneration) {
            mutableStateOf(isSynced)
        }
    // Safety net: nothing may keep the lyrics hidden. If the placement hasn't
    // happened by the time this fires (no viewport, auto-scroll preference off,
    // an index that never lands in range) the lines are shown as they are.
    LaunchedEffect(awaitingFirstFocus) {
        if (!awaitingFirstFocus) return@LaunchedEffect
        delay(LYRIC_FIRST_FOCUS_TIMEOUT_MS)
        awaitingFirstFocus = false
    }
    // Read only from the draw phase (graphicsLayer), so the fade never
    // recomposes the karaoke view.
    val firstFocusAlpha =
        animateFloatAsState(
            targetValue = if (awaitingFirstFocus) 0f else 1f,
            animationSpec =
                tween(
                    durationMillis = if (awaitingFirstFocus) 0 else LYRIC_FIRST_FOCUS_FADE_MS,
                    easing = LinearEasing,
                ),
            label = "lyrics-first-focus-alpha",
        )

    LaunchedEffect(lyricsSessionKey) {
        playbackPositionMs.longValue = player.currentPosition.coerceAtLeast(0L)
        // A new track starts from its own position instead of projecting from the last one's anchor.
        drawClock.anchor(
            positionMs = playbackPositionMs.longValue,
            atNanos = System.nanoTime(),
            speed = latestPlaybackSpeed.value,
            advancing = false,
        )
        isManualScrolling = false
        lastManualScrollTime = 0L
        isSelectionModeActive = false
        selectedLineKeys.clear()
        // Reset the cached line index so first focus waits for the new track's active line
        // instead of inheriting the previous track's index.
        currentLineIndexState.intValue = -1
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    // The word/line boundaries below which the position loop sleeps instead of frame-stepping.
    val deadlineSchedule = remember(lyricsEntries) { LyricsDeadlineSchedule.of(lyricsEntries, LyricsSweepGeometry.MIN_SWEEP_MS) }
    LaunchedEffect(player, lyricsSessionKey, animationsDisabled, playbackParameters.speed, deadlineSchedule) {
        var wasSliderActive = false
        var anchorPlayerPositionMs = player.currentPosition.coerceAtLeast(0L)
        var anchorFrameNanos = 0L
        var lastRawPositionMs = player.currentPosition.coerceAtLeast(0L)
        // Cache the current line index + activation boundary timestamps to avoid
        // the binary search every frame.
        var lastLyricsRef: SyncedLyrics? = null
        var cachedLineIdx = -1
        var cachedCurrentLineStart = Int.MIN_VALUE
        var cachedNextLineStart = Int.MAX_VALUE
        var cachedActivationStarts: IntArray? = null
        val interludeGate = EnhancedInterludeGate { latestSyncedLyrics.value }
        // Parks the frame loop while the screen is not resumed, so a paused or backgrounded lyrics view stops polling.
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                val sliderPosition = latestSliderPositionProvider.value()
                val isSliderActive = sliderPosition != null
                if (isSliderActive && !wasSliderActive) {
                    isManualScrolling = false
                }
                wasSliderActive = isSliderActive

                val rawPosition = (sliderPosition ?: player.currentPosition).coerceAtLeast(0L)
                // Detect a position discontinuity in either direction, measured
                // against the PLAYER's clock rather than the slider-provided value. This
                // catches REPEAT_MODE_ONE wraps (duration -> 0) and explicit seeks both
                // backward and forward, while the 1s threshold excludes playback jitter
                // and minor ExoPlayer position corrections. A forward jump re-keys the
                // list the same way so the new line snaps in rather than scrolling the gap.
                //
                // Do NOT gate this on `sliderPosition == null`: the Apple Music player always installs
                // a slider provider, so the gate would disable restart detection there and the lyrics
                // would snap back to the first line without re-animating until the overlay was
                // reopened.
                val rawPlayerPosition = player.currentPosition.coerceAtLeast(0L)
                if (abs(rawPlayerPosition - lastRawPositionMs) > POSITION_RESET_THRESHOLD_MS) {
                    positionResetCounter += 1
                }
                lastRawPositionMs = rawPlayerPosition
                // effectivePositionMs is the synced position (offset + lead +
                // tuning) that we'd otherwise compute via playbackSyncPosition().
                // Computing it here inline avoids a redundant State read of
                // playbackPositionMs.longValue (we already have the value in
                // `rawPosition` / `nextPosition`).
                val effectivePositionMs: Long
                // The anchor the pane projects at draw time, and whether it keeps advancing from there.
                var drawAnchorPositionMs = 0L
                var drawAnchorNanos = 0L
                var drawAdvancing = false
                // Between word and line boundaries the drawn state is static, so the loop parks until the next deadline.
                var parkMs = 0L
                val idleFeedEstimateMs =
                    (rawPosition + latestLyricsSyncOffset.value.toLong() +
                        latestLeadMs.value + LYRIC_VISUAL_TUNING_OFFSET_MS)
                        .coerceIn(0L, Int.MAX_VALUE.toLong())
                // The library view lifts each syllable for 700 ms past its start, and both lists breathe their interlude
                // dots from the position; the word schedule knows neither, so those get a fresh position every frame.
                if (!enhancedLibraryViewActive && sliderPosition == null && player.isPlaying && !animationsDisabled &&
                    !deadlineSchedule.isLive(idleFeedEstimateMs) && !interludeGate.covers(idleFeedEstimateMs)
                ) {
                    // Publish before parking, so a sweep or lift that just left its live span shows its final frame now.
                    val next = deadlineSchedule.nextChangeAfter(idleFeedEstimateMs)
                    parkMs = lyricsIdleParkMs(next, idleFeedEstimateMs, latestPlaybackSpeed.value, 16L, IDLE_POLL_MS)
                    anchorPlayerPositionMs = rawPosition
                    anchorFrameNanos = 0L
                    if (playbackPositionMs.longValue != rawPosition) {
                        playbackPositionMs.longValue = rawPosition
                    }
                    effectivePositionMs = idleFeedEstimateMs
                    drawAnchorPositionMs = playbackPositionMs.longValue
                    drawAnchorNanos = System.nanoTime()
                } else if (sliderPosition != null || !player.isPlaying || animationsDisabled) {
                    anchorPlayerPositionMs = rawPosition
                    anchorFrameNanos = 0L
                    if (playbackPositionMs.longValue != rawPosition) {
                        playbackPositionMs.longValue = rawPosition
                    }
                    drawAnchorPositionMs = rawPosition
                    drawAnchorNanos = System.nanoTime()
                    effectivePositionMs =
                        (rawPosition + latestLyricsSyncOffset.value.toLong() +
                            latestLeadMs.value + LYRIC_VISUAL_TUNING_OFFSET_MS)
                            .coerceIn(0L, Int.MAX_VALUE.toLong())
                    if (sliderPosition == null) {
                        delay(100L)
                    } else {
                        withFrameNanos { }
                    }
                } else {
                    val frameNanos = withFrameNanos { frameTimeNanos -> frameTimeNanos }
                    if (anchorFrameNanos == 0L) {
                        anchorFrameNanos = frameNanos
                        anchorPlayerPositionMs = rawPosition
                    }

                    // Shared with the pane's own projection (LyricsPositionProjection), so the position
                    // the loop indexes and the position the frame paints come from one curve.
                    val elapsedNanos = frameNanos - anchorFrameNanos
                    val projectedPosition =
                        LyricsPositionProjection.project(anchorPlayerPositionMs, elapsedNanos, latestPlaybackSpeed.value)
                    if (LyricsPositionProjection.shouldReanchor(rawPosition, projectedPosition)) {
                        anchorPlayerPositionMs = rawPosition
                        anchorFrameNanos = frameNanos
                    }
                    val nextPosition =
                        LyricsPositionProjection
                            .corrected(anchorPlayerPositionMs, frameNanos - anchorFrameNanos, latestPlaybackSpeed.value, rawPosition)
                            .coerceAtLeast(0L)

                    if (playbackPositionMs.longValue != nextPosition) {
                        playbackPositionMs.longValue = nextPosition
                    }
                    // The anchor is this frame's own vsync instant, so whatever the draw phase does
                    // with it — before or after this continuation runs — the projection lands on the
                    // position of the instant it paints.
                    drawAnchorPositionMs = nextPosition
                    drawAnchorNanos = frameNanos
                    drawAdvancing = true
                    effectivePositionMs =
                        (nextPosition + latestLyricsSyncOffset.value.toLong() +
                            latestLeadMs.value + LYRIC_VISUAL_TUNING_OFFSET_MS)
                            .coerceIn(0L, Int.MAX_VALUE.toLong())
                }

                // Line-index tracking: only re-search when the position crosses a
                // line boundary OR the lyrics object identity changed (romanization
                // finished). This is the key per-frame optimization — the binary
                // search is skipped entirely on the vast majority of frames, and
                // we reuse `effectivePositionMs` instead of calling the
                // `playbackSyncPosition()` lambda (which would do 3 more State
                // reads + arithmetic on every frame).
                val syncedLyricsNow = latestSyncedLyrics.value
                if (syncedLyricsNow.lines.isNotEmpty()) {
                    val lyricsChanged = syncedLyricsNow !== lastLyricsRef
                    if (lyricsChanged) {
                        lastLyricsRef = syncedLyricsNow
                        cachedLineIdx = -1
                        cachedActivationStarts = null
                    }
                    val pos = effectivePositionMs.toInt()
                    val needsResearch =
                        cachedLineIdx == -1 ||
                            pos >= cachedNextLineStart ||
                            pos < cachedCurrentLineStart
                    if (needsResearch) {
                        // Follows whichever list draws, so the first-focus gate and the strip never run ahead of it.
                        val activationStarts =
                            cachedActivationStarts
                                ?: enhancedPaneLineStarts(syncedLyricsNow).also { cachedActivationStarts = it }
                        val newLineIdx = activeSyncedLineIndex(activationStarts, pos)
                        cachedLineIdx = newLineIdx
                        cachedCurrentLineStart =
                            if (newLineIdx >= 0) activationStarts[newLineIdx] else Int.MIN_VALUE
                        cachedNextLineStart =
                            activationStarts.getOrNull(newLineIdx + 1) ?: Int.MAX_VALUE
                        if (newLineIdx != currentLineIndexState.intValue) {
                            currentLineIndexState.intValue = newLineIdx
                        }
                    }
                }
                // One subscription for the whole pane: the frame after this publishes paints from the
                // anchor it just set, and a parked iteration still repaints the frame it published.
                drawClock.anchor(
                    positionMs = drawAnchorPositionMs,
                    atNanos = drawAnchorNanos,
                    speed = latestPlaybackSpeed.value,
                    advancing = drawAdvancing,
                )
                frameTick.intValue++
                if (parkMs > 0L) delay(parkMs)
            }
        }
    }

    val nestedScrollConnection =
        remember {
            var lastUserScrollEventMs = 0L
            object : NestedScrollConnection {
                private fun markManualScroll() {
                    val now = System.currentTimeMillis()
                    if (now - lastUserScrollEventMs >= MANUAL_SCROLL_DEBOUNCE_MS) {
                        isManualScrolling = true
                        lastManualScrollTime = now
                        lastUserScrollEventMs = now
                    }
                }

                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (!isSelectionModeActive && source == NestedScrollSource.UserInput) {
                        markManualScroll()
                    }
                    return Offset.Zero
                }

                override suspend fun onPostFling(
                    consumed: Velocity,
                    available: Velocity,
                ): Velocity {
                    if (!isSelectionModeActive && isManualScrolling) {
                        lastManualScrollTime = System.currentTimeMillis()
                    }
                    return Velocity.Zero
                }
            }
        }

    LaunchedEffect(isManualScrolling, lastManualScrollTime) {
        if (isManualScrolling) {
            delay(MANUAL_SCROLL_TIMEOUT_MS)
            isManualScrolling = false
        }
    }

    // Forward the user-scroll signal up to LyricsScreen via LocalLyricsScrollListener so the
    // Apple Music-style bottom controls can slide in when the user scrolls lyrics.
    val onLyricsScroll = LocalLyricsScrollListener.current
    LaunchedEffect(isManualScrolling) {
        onLyricsScroll(isManualScrolling)
    }

    // Selection pauses the page's controls auto-hide too: tucking the controls away must not
    // dismiss the sheet the user is picking lines in.
    val onSelectionActive = LocalLyricsSelectionListener.current
    LaunchedEffect(isSelectionModeActive) {
        onSelectionActive(isSelectionModeActive)
    }
    DisposableEffect(Unit) {
        onDispose { onSelectionActive(false) }
    }

    // Deliberately not a key on `syncedLyrics` — the value is read through rememberUpdatedState
    // below instead, so a lyrics update does not restart the collector.
    val latestSyncedLyricsForScroll = rememberUpdatedState(syncedLyrics)
    // Restart this collector after a repeat, or after a romanisation re-key. The karaoke view is
    // re-keyed at the same time, and the fresh collector resets its focus state before it
    // observes the first new active line. Keeping listState stable means the collector
    // always targets the list currently on screen.
    LaunchedEffect(
        lyricsSessionKey,
        isSynced,
        positionResetCounter,
        karaokeGeneration,
    ) {
        if (!isSynced || singleActiveLine) {
            // Single-line mode never scrolls a lyric list, so there is no first focus to wait
            // for — show the strip straight away.
            awaitingFirstFocus = false
            return@LaunchedEffect
        }
        // An empty line list here means "the off-thread build hasn't published yet", not "this
        // track has no synced lyrics" — buildSyncedLyrics runs on Dispatchers.Default and lands a
        // frame or two after the first composition. Bailing out on it (which is what this used to
        // do) disarmed the first-focus gate before there was anything to place, so the lines were
        // drawn at line 0 and then visibly walked to the active one. Wait for the content instead;
        // the LYRIC_FIRST_FOCUS_TIMEOUT_MS safety net above covers the case where it never arrives.
        snapshotFlow { latestSyncedLyricsForScroll.value.lines.isNotEmpty() }.first { it }

        // The pane's list (KaraokeLyricsView, or BasicSyncedLyricsList below API 29) is the only auto-scroll owner. A
        // second app-side scroller fought it and clipped the top active line of an accompaniment/duet aggregate. This
        // collector only decides when the hidden list may fade in.
        snapshotFlow {
            if (isManualScrolling || isSelectionModeActive) {
                LYRIC_FOCUS_TARGET_MANUAL
            } else {
                currentLineIndexState.intValue
                    .takeIf { index -> index in latestSyncedLyricsForScroll.value.lines.indices }
                    ?: LYRIC_FOCUS_TARGET_PENDING
            }
        }.distinctUntilChanged()
            .collectLatest { target ->
                if (target == LYRIC_FOCUS_TARGET_MANUAL) {
                    // The user took over the list, so there is nothing left to hide.
                    awaitingFirstFocus = false
                    return@collectLatest
                }
                // No index published yet: releasing here would fade the list in at line 0 and then
                // show the list walking to the active line. LYRIC_FIRST_FOCUS_TIMEOUT_MS still
                // releases it if no line ever becomes active (long intro).
                if (target == LYRIC_FOCUS_TARGET_PENDING) return@collectLatest
                if (awaitingFirstFocus) {
                    listState.awaitKaraokeFirstFocus(target)
                    awaitingFirstFocus = false
                }
            }
    }

    // Clear the tracked active line after a backward discontinuity so the re-keyed
    // first-focus collector above waits for the first line of the new play-through
    // instead of treating the previous line index as still current.
    //
    // No explicit scroll here: [listState] is recreated alongside the karaoke subtree on
    // the same counter, so the fresh list already starts at the top. Scrolling the old
    // state was both redundant and a way to touch a state whose layout was being
    // disposed.
    LaunchedEffect(positionResetCounter) {
        if (positionResetCounter > 0) {
            currentLineIndexState.intValue = -1
        }
    }

    BackHandler(enabled = isSelectionModeActive) {
        isSelectionModeActive = false
        selectedLineKeys.clear()
    }

    LaunchedEffect(showMaxSelectionToast) {
        if (showMaxSelectionToast) {
            Toast
                .makeText(
                    context,
                    context.getString(R.string.max_selection_limit, maxSelectionLimit),
                    Toast.LENGTH_SHORT,
                ).show()
            showMaxSelectionToast = false
        }
    }

    val activity = context as? Activity
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    // Remembered: fresh TextStyle identities every recomposition forced the karaoke view to
    // re-measure all lines on unrelated state changes (scroll flags, selection, etc.).
    val typography = MaterialTheme.typography
    val normalTextStyle =
        remember(typography, lyricsTextSize, lyricsFontFamily) {
            typography.headlineMedium.copy(
                fontSize = lyricsTextSize.sp,
                fontWeight = FontWeight.Bold, fontFamily = lyricsFontFamily ?: typography.headlineMedium.fontFamily,
                // headlineMedium's 36sp leading is tuned for its 28sp font: scale it or the sweep bands slice glyph ink.
                lineHeight = (lyricsTextSize * 9f / 7f).coerceAtLeast(36f).sp,
            )
        }
    val accompanimentTextStyle =
        remember(typography, lyricsTextSize, lyricsFontFamily) {
            typography.titleLarge.copy(
                fontSize = (lyricsTextSize * 0.82f).sp, fontFamily = lyricsFontFamily ?: typography.titleLarge.fontFamily,
                lineHeight = (lyricsTextSize * 0.82f * 14f / 11f).coerceAtLeast(28f).sp,
            )
        }
    val phoneticTextStyle =
        remember(typography, lyricsTextSize) {
            typography.bodyMedium.copy(
                fontSize = (lyricsTextSize * 0.55f).sp, fontWeight = FontWeight.Normal,
                lineHeight = (lyricsTextSize * 0.55f * 1.25f).coerceAtLeast(20f).sp,
            )
        }
    val plainLyrics =
        remember(lyricsEntries, isSynced) {
            AlternatePlainLyrics(
                items =
                    if (isSynced) {
                        emptyList()
                    } else {
                        lyricsEntries.mapIndexedNotNull { index, entry ->
                            val text = entry.text.trim()
                            if (text.isBlank()) {
                                null
                            } else {
                                val selectionId = "plain:$index:${text.hashCode()}"
                                AlternatePlainLyricLine(
                                    itemId = "$selectionId#$index",
                                    selectionId = selectionId,
                                    text = text,
                                )
                            }
                        }
                    },
            )
        }
    val selectionLines =
        remember(isSynced, syncedLyrics, plainLyrics) {
            if (isSynced) {
                syncedLyrics.lines.mapIndexedNotNull { index, line ->
                    val text = line.alternateLineText()
                    if (text.isBlank()) {
                        null
                    } else {
                        val selectionId = line.alternateSelectionKey(text)
                        AlternateLyricSelectionLine(
                            itemId = "$selectionId#$index",
                            selectionId = selectionId,
                            text = text,
                        )
                    }
                }
            } else {
                plainLyrics.items.map { line ->
                    AlternateLyricSelectionLine(
                        itemId = line.itemId,
                        selectionId = line.selectionId,
                        text = line.text,
                    )
                }
            }
        }
    val selectedLineKeySnapshot = selectedLineKeys.toList()
    val selectedLineKeySet = remember(selectedLineKeySnapshot) { selectedLineKeySnapshot.toSet() }
    val dismissSelection = {
        isSelectionModeActive = false
        selectedLineKeys.clear()
    }
    val toggleSelectedLine: (String) -> Unit = { lineKey ->
        if (selectedLineKeys.contains(lineKey)) {
            selectedLineKeys.remove(lineKey)
            if (selectedLineKeys.isEmpty()) isSelectionModeActive = false
        } else if (selectedLineKeys.size < maxSelectionLimit) {
            selectedLineKeys.add(lineKey)
        } else {
            showMaxSelectionToast = true
        }
    }
    val shareSelectedLyrics: () -> Unit = {
        val metadata = mediaMetadata
        if (metadata != null) {
            val selectedLyricsText =
                selectionLines
                    .filter { line -> line.selectionId in selectedLineKeySet }
                    .joinToString("\n") { line -> line.text }
            if (selectedLyricsText.isNotBlank()) {
                shareDialogData =
                    Triple(
                        selectedLyricsText,
                        metadata.title,
                        metadata.artists.joinToString { it.name },
                    )
                showShareDialog = true
            }
        }
        dismissSelection()
    }

    Box(
        contentAlignment = Alignment.TopCenter,
        modifier =
            modifier
                .fillMaxSize()
                .padding(bottom = 12.dp)
                // last of those lets the first two flash at full opacity and vanish when the lines
                // take over. When there is nothing to place (plain lyrics, no lyrics, not-found) the
                // gate is never armed and this is a no-op.
                .graphicsLayer { alpha = firstFocusAlpha.value },
    ) {
        when {
            lyrics == LYRICS_NOT_FOUND -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.lyrics_not_found),
                        style = MaterialTheme.typography.bodyLarge,
                        color = textColor.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            // parsedEntries == null means the off-thread parse above hasn't published yet.
            // Shimmer on that state — without it the two "lyrics not found" branches below
            // would flash for the frames the parse takes.
            lyrics == null || parsedEntries == null -> {
                ShimmerHost {
                    repeat(6) { TextPlaceholder() }
                }
            }

            // Same window, one hop later: the parse has landed but buildSyncedLyrics (also on
            // Dispatchers.Default) hasn't published its result, so `lines` is empty even though
            // this track definitely has synced lyrics. Keep the shimmer up rather than claiming
            // they don't exist.
            isSynced && syncedLyrics.lines.isEmpty() && lyricsEntries.isNotEmpty() -> {
                ShimmerHost {
                    repeat(6) { TextPlaceholder() }
                }
            }

            isSynced && syncedLyrics.lines.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.lyrics_not_found),
                        style = MaterialTheme.typography.bodyLarge,
                        color = textColor.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            !isSynced && plainLyrics.items.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.lyrics_not_found),
                        style = MaterialTheme.typography.bodyLarge,
                        color = textColor.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            !isSynced -> {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val lyricsContentWidth =
                        minOf(maxWidth, if (spotifyStyle) 760.dp else 860.dp)
                    PlainLyricsView(
                        lines = plainLyrics,
                        listState = listState,
                        selectedLineKeys = selectedLineKeySet,
                        textColor = textColor,
                        textStyle = normalTextStyle,
                        onLineClicked = { lineKey ->
                            if (isSelectionModeActive) toggleSelectedLine(lineKey)
                        },
                        onLinePressed = { lineKey ->
                            if (!isSelectionModeActive) {
                                isSelectionModeActive = true
                                if (!selectedLineKeys.contains(lineKey)) {
                                    selectedLineKeys.add(lineKey)
                                }
                            } else if (!selectedLineKeys.contains(lineKey)) {
                                toggleSelectedLine(lineKey)
                            }
                        },
                        modifier =
                            Modifier
                                .widthIn(max = lyricsContentWidth)
                                .fillMaxWidth()
                                .fillMaxHeight()
                                .align(Alignment.TopCenter),
                    )
                }
            }

            // TikTok-style strip: only the active line cluster is ever composed — no line list,
            // no scrolling, no previous/upcoming lines.
            singleActiveLine && isSynced -> {
                key(lyricsSessionKey, positionResetCounter, karaokeGeneration) {
                    CompositionLocalProvider(LocalTextStyle provides phoneticTextStyle) {
                        AlternateSingleActiveKaraokeLine(
                            activeLine = activeKaraokeLine,
                            currentPosition = playbackSyncPosition,
                            textColor = textColor,
                            normalTextStyle = normalTextStyle,
                            accompanimentTextStyle = accompanimentTextStyle,
                            phoneticTextStyle = phoneticTextStyle,
                            showTranslation = showTranslations,
                            onLineClick = { line ->
                                if (lyricsClick && line.start > 0) {
                                    player.seekTo(line.start.toLong())
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            else -> {
                // Keyed on the session and a position-reset counter: when the song repeats (REPEAT_MODE_ONE wraps the
                // position to 0) or the user seeks backward by more than 1 second, the list is recreated, so the new
                // position snaps into place rather than scrolling back from the line that was active before the jump.
                key(lyricsSessionKey, positionResetCounter, karaokeGeneration) {
                    EnhancedKaraokePane(
                        listState = listState,
                        lyrics = syncedLyrics,
                        currentPosition = playbackSyncPosition,
                        onLineClicked = { line ->
                            if (isSelectionModeActive) {
                                toggleSelectedLine(line.alternateSelectionKey())
                            } else if (lyricsClick && isSynced && line.start > 0) {
                                // A tap is a seek, so following resumes at once instead of waiting out the scroll hold.
                                isManualScrolling = false
                                lastManualScrollTime = 0L
                                player.seekTo(line.start.toLong())
                            }
                        },
                        onLinePressed = { line ->
                            val lineKey = line.alternateSelectionKey()
                            if (!isSelectionModeActive) {
                                isSelectionModeActive = true
                                if (!selectedLineKeys.contains(lineKey)) {
                                    selectedLineKeys.add(lineKey)
                                }
                            } else if (!selectedLineKeys.contains(lineKey)) {
                                toggleSelectedLine(lineKey)
                            }
                        },
                        textColor = textColor,
                        normalLineTextStyle = normalTextStyle,
                        accompanimentLineTextStyle = accompanimentTextStyle,
                        phoneticTextStyle = phoneticTextStyle,
                        // The pane gates the distance blur on motion and on what the device can draw. This passes only
                        // the user's line-blur choice.
                        useBlurEffect = lyricsLineBlur,
                        manualScrolling = isManualScrolling,
                        credits = lyricsCredits,
                        showTranslation = showTranslations,
                        showPhonetic = romanizationPreferences.showsRomanization,
                        maxContentWidth = if (spotifyStyle) 760.dp else 860.dp,
                        modifier = Modifier.fillMaxSize().nestedScroll(nestedScrollConnection),
                    )
                }
            }
        }
    }

    if (isSelectionModeActive && selectionLines.isNotEmpty()) {
        AlternateLyricsSelectionBottomSheet(
            lines = selectionLines,
            selectedLineKeys = selectedLineKeySet,
            onToggleLine = toggleSelectedLine,
            onDismissRequest = dismissSelection,
            onShareSelected = shareSelectedLyrics,
        )
    }

    if (showShareDialog && shareDialogData != null) {
        AlternateLyricsShareDialog(
            data = shareDialogData!!,
            mediaMetadata = mediaMetadata,
            onDismiss = { showShareDialog = false },
            onShareImage = {
                showShareImageDialog = true
                showShareDialog = false
            },
        )
    }

    if (showShareImageDialog && shareDialogData != null) {
        val (lyricsText, songTitle, artists) = shareDialogData!!
        LyricsShareImageDialog(
            mediaMetadata = mediaMetadata,
            payload = LyricsSharePayload(lyricsText, songTitle, artists),
            onDismissRequest = { showShareImageDialog = false },
        )
    }
}
