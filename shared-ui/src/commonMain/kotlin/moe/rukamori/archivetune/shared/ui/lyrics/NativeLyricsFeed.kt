package moe.rukamori.archivetune.shared.ui.lyrics

import moe.rukamori.archivetune.shared.domain.playback.MonotonicClock
import moe.rukamori.archivetune.shared.domain.playback.PlaybackClock
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.playback.SystemMonotonicClock

/**
 * One piece of a row for a platform renderer. The spans of a row join to exactly its text: the text between two
 * words travels with the word before it, and text before the first word with the first word. A word is lit from
 * [startMs] to [endMs] on the lyric feed timeline.
 */
data class NativeLyricsSpan(
    val text: String,
    val startMs: Long,
    val endMs: Long,
)

/**
 * A lyric row as a platform renderer draws it. [spans] is empty for a line-only row, which lights as a whole while
 * active. [background] and [backgroundSpans] are trailing background vocals on their own smaller line.
 */
data class NativeLyricsLine(
    val index: Int,
    val timeMs: Long,
    val text: String,
    val spans: List<NativeLyricsSpan>,
    val background: String?,
    val backgroundSpans: List<NativeLyricsSpan>,
    val phonetic: String?,
    val translation: String?,
    val secondVoice: Boolean,
    val rightToLeft: Boolean,
    val instrumental: Boolean,
    /** Whether a tap on the row seeks, by the shared [LyricsTapPolicy]. */
    val seekable: Boolean,
)

/**
 * What a platform lyric view draws for one session. The rows come from [LyricsRenderRows], so a platform renderer
 * never parses lyrics, and the colours are the shared theme's, so the native view matches the player around it.
 */
data class NativeLyricsDocument(
    val sessionKey: String,
    val lines: List<NativeLyricsLine>,
    val synced: Boolean,
    val reduceMotion: Boolean,
    val depthEffects: Boolean,
    /** How long a listener's scroll holds off auto-follow, as the shared Enhanced look does. */
    val manualScrollHoldMs: Long,
    /** The sung text colour as 0xAARRGGBB. Rows not being sung draw it at [idleAlpha]. */
    val colorArgb: Int,
    val idleAlpha: Float,
) {
    companion object {
        fun from(
            sessionKey: String,
            rows: List<LyricsRenderRow>,
            settings: LyricsSurfaceSettings,
            colorArgb: Int,
        ): NativeLyricsDocument {
            val profile = LyricsModeProfile.of(settings.mode)
            return NativeLyricsDocument(
                sessionKey = sessionKey,
                lines = rows.map { row ->
                    NativeLyricsLine(
                        index = row.index,
                        timeMs = row.timeMs,
                        text = row.text,
                        spans = nativeLyricsSpans(row.text, row.words),
                        background = row.background,
                        backgroundSpans = row.background?.let { nativeLyricsSpans(it, row.backgroundWords) }.orEmpty(),
                        phonetic = row.phonetic,
                        translation = row.translation,
                        secondVoice = row.isSecondVoice,
                        rightToLeft = row.isRtl,
                        instrumental = row.isInstrumental,
                        seekable = LyricsTapPolicy.onTap(row, selectionActive = false, tapToSeek = settings.tapToSeek) is
                            LyricsTapAction.Seek,
                    )
                },
                synced = rows.any { it.isSynced },
                reduceMotion = settings.reduceMotion,
                depthEffects = settings.depthEffects,
                manualScrollHoldMs = profile.manualScrollHoldMs,
                colorArgb = colorArgb,
                idleAlpha = profile.idleAlpha,
            )
        }
    }
}

/**
 * Cuts [text] into word spans. Words without a usable range, or whose range starts before the previous word's,
 * are left out, and a row with no usable word is line-only. The spans always join back to [text].
 */
internal fun nativeLyricsSpans(text: String, words: List<LyricsWordTiming>?): List<NativeLyricsSpan> {
    if (words.isNullOrEmpty() || text.isEmpty()) return emptyList()
    val located = ArrayList<LyricsWordTiming>(words.size)
    var lastStart = -1
    for (word in words) {
        val range = word.range ?: continue
        if (range.first <= lastStart || range.first < 0 || range.first >= text.length) continue
        located += word
        lastStart = range.first
    }
    if (located.isEmpty()) return emptyList()
    return located.mapIndexed { index, word ->
        val from = if (index == 0) 0 else requireNotNull(word.range).first
        val to = located.getOrNull(index + 1)?.range?.first ?: text.length
        NativeLyricsSpan(text.substring(from, to), word.startMs, word.endMs)
    }
}

/** The feed position and active rows at one instant, for one frame of a platform lyric view. */
class NativeLyricsFrame internal constructor(
    /** The lyric feed position: playback position with the sync offset, lead and visual tuning. */
    val positionMs: Long,
    /** The active row that began most recently, or -1 between rows. */
    val primaryLine: Int,
    /** Changes on every seek, repeat or new session, so the view jumps rather than scrolls. */
    val discontinuity: Int,
    /**
     * Changes when the feed jumps on the same timeline, so the view snaps rather than animates the
     * distance. Unlike [discontinuity] it never ends a listener's scroll hold.
     */
    val reanchor: Int,
    /** False while paused, buffering or routed remotely: the position holds and the view may stop its frame loop. */
    val advancing: Boolean,
    private val active: List<Int>,
) {
    fun isLineActive(index: Int): Boolean = index in active

    /** How row [index] reads by its distance from the sung line, with the same steps as the shared renderer. */
    fun depth(index: Int, reduceMotion: Boolean): NativeLyricsDepth {
        val depth = LyricsDepth.of(LyricsDepth.distance(index, primaryLine, isLineActive(index)), reduceMotion)
        return NativeLyricsDepth(depth.scale, depth.blurDp, depth.alpha)
    }
}

/** A row's scale, blur radius in points and opacity by its distance from the sung line. */
data class NativeLyricsDepth(
    val scale: Float,
    val blurPoints: Float,
    val alpha: Float,
)

/**
 * The lyric clock for a platform renderer. It takes the same playback snapshots as the shared renderer and
 * feeds them through [LyricsClock] on the [MonotonicClock] base the snapshots use, so the native view and the
 * shared view apply the same output delay and show the same position for the same snapshot.
 */
class NativeLyricsTimeline(private val clock: MonotonicClock = SystemMonotonicClock) {
    private val lyricsClock = LyricsClock()
    private var anchor: PlaybackSnapshot? = null
    private var syncOffsetMs = 0L
    private var leadMs = 0L
    private var scanner = ActiveLineScanner(LongArray(0), LongArray(0))

    fun accept(snapshot: PlaybackSnapshot) {
        if (lyricsClock.accept(snapshot, clock.nowNanos()) != LyricsAnchorChange.STALE) anchor = snapshot
    }

    fun setRows(rows: List<LyricsRenderRow>) {
        scanner = LyricsRenderRows.scanner(rows)
        leadMs = LyricsFeedTiming.leadMsFor(rows)
        lyricsClock.retime(syncOffsetMs, leadMs)
    }

    fun setSyncOffset(offsetMs: Long) {
        syncOffsetMs = offsetMs
        lyricsClock.retime(syncOffsetMs, leadMs)
    }

    fun frame(): NativeLyricsFrame {
        lyricsClock.advance(clock.nowNanos())
        val feed = lyricsClock.feedMs.longValue
        val active = scanner.activeAt(feed)
        return NativeLyricsFrame(
            positionMs = feed,
            primaryLine = active.lastOrNull() ?: -1,
            discontinuity = lyricsClock.discontinuities.intValue,
            reanchor = lyricsClock.reanchors.intValue,
            advancing = anchor?.let(PlaybackClock::isInterpolating) == true,
            active = active,
        )
    }
}
