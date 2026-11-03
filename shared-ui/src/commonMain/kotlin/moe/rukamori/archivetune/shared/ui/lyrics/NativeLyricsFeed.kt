package moe.rukamori.archivetune.shared.ui.lyrics

import moe.rukamori.archivetune.shared.domain.lyrics.unicode.GraphemeClusterSegmenter
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
    /**
     * The grapheme clusters of an emphasized word inside [text], in sung order, or null for a word
     * that sweeps plainly. [LyricsEmphasis] cuts them the same way the shared sweep does: a held,
     * short word in an unjoined script. [lettersPrefix] is the slice of [text] before the letters;
     * what follows the joined letters stays with the word's sweep, so
     * `text == lettersPrefix + letters.joinToString("") + remainder`.
     */
    val letters: List<String>? = null,
    val lettersPrefix: String = "",
)

/**
 * A lyric row as a platform renderer draws it. [spans] is empty for a line-only row, which lights as a whole while
 * active. [background] and [backgroundSpans] are trailing background vocals on their own smaller line.
 * [secondVoice] carries the row's end-side alignment for the look the document was built with, from the
 * shared [LyricsAlignmentPolicy], so a duet reads the same on every surface.
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
    /** The silence this row's item shows the interlude dots over, or -1s when it shows none. */
    val interludeStartMs: Long = -1L,
    val interludeEndMs: Long = -1L,
    /** Machine-made reading or translation per the document's credits; the row badges it. */
    val phoneticAutoCreated: Boolean = false,
    val translationAutoCreated: Boolean = false,
)

/**
 * The document credits a platform lyric view draws. [writtenBy] is the formatted writers line and
 * [autoCreatedLabel] the localized name of the machine-made badge; each is null when nothing is to show.
 * [translationAutoCreated]/[transliterationAutoCreated] drive the per-line badges of a row.
 */
data class NativeLyricsCredits(
    val writtenBy: String?,
    val autoCreatedLabel: String?,
    val translationAutoCreated: Boolean,
    val transliterationAutoCreated: Boolean,
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
    /** The sung-line size the saved lyrics text size resolves to; the native view treats it as points. */
    val mainSizeSp: Float = LyricsModeProfile.TEXT_SIZE_DEFAULT_SP,
    /** The quick scale the sung line pops to while it is sung; 1 keeps its size. */
    val activeScale: Float = 1f,
    /** Guards a zero-length word sweep; every other word fills over its own span. */
    val minSweepMs: Long = LyricsSweepGeometry.MIN_SWEEP_MS,
    /** The bloom colour behind an emphasized word's bumping letter, as 0xAARRGGBB. */
    val glowArgb: Int = colorArgb,
    /** The writers/auto-created credits: the footer text and the per-line machine-made badges. */
    val credits: NativeLyricsCredits? = null,
) {
    companion object {
        fun from(
            sessionKey: String,
            rows: List<LyricsRenderRow>,
            settings: LyricsSurfaceSettings,
            colorArgb: Int,
            glowArgb: Int = colorArgb,
            credits: NativeLyricsCredits? = null,
        ): NativeLyricsDocument {
            val profile = LyricsModeProfile.of(settings.mode, settings.textSizeSp)
            val interludes = if (profile.refinedMotion) LyricsInterludes.byRow(rows) else emptyMap()
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
                        secondVoice = LyricsAlignmentPolicy.of(settings.mode, row) == LyricsLineAlignment.END,
                        rightToLeft = row.isRtl,
                        instrumental = row.isInstrumental,
                        seekable = LyricsTapPolicy.onTap(row, selectionActive = false, tapToSeek = settings.tapToSeek) is
                            LyricsTapAction.Seek,
                        interludeStartMs = interludes[row.index]?.startMs ?: -1L,
                        interludeEndMs = interludes[row.index]?.endMs ?: -1L,
                        phoneticAutoCreated =
                            credits?.transliterationAutoCreated == true && row.providerRomanized,
                        translationAutoCreated = credits?.translationAutoCreated == true,
                    )
                },
                synced = rows.any { it.isSynced },
                reduceMotion = settings.reduceMotion,
                depthEffects = settings.depthEffects,
                manualScrollHoldMs = profile.manualScrollHoldMs,
                colorArgb = colorArgb,
                idleAlpha = profile.idleAlpha,
                mainSizeSp = profile.mainSizeSp,
                activeScale = profile.activeScale,
                minSweepMs = profile.minSweepMs,
                glowArgb = glowArgb,
                credits = credits,
            )
        }
    }
}

/**
 * Cuts [text] into word spans. Words without a usable range, or whose range starts before the previous word's,
 * are left out, and a row with no usable word is line-only. The spans always join back to [text]. A word that
 * earns the [LyricsEmphasis] ripple also carries its cluster letters, so the platform renderer can move them
 * one at a time without re-segmenting the text.
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
    val clusterStarts = GraphemeClusterSegmenter.clusterStarts(text)
    return located.mapIndexed { index, word ->
        val range = requireNotNull(word.range)
        val from = if (index == 0) 0 else range.first
        val to = located.getOrNull(index + 1)?.range?.first ?: text.length
        val cut = emphasisLetters(text, from, to, range, word.endMs - word.startMs, clusterStarts)
        if (cut == null) {
            NativeLyricsSpan(text.substring(from, to), word.startMs, word.endMs)
        } else {
            val (lettersFrom, letters) = cut
            NativeLyricsSpan(
                text = text.substring(from, to),
                startMs = word.startMs,
                endMs = word.endMs,
                letters = letters,
                lettersPrefix = text.substring(from, lettersFrom),
            )
        }
    }
}

/**
 * The cluster letters of a word that earned the [LyricsEmphasis] ripple: the word's range widened to whole
 * clusters, the whitespace edge clusters trimmed and the emphasis gate applied — the same decision the
 * shared sweep makes in `emphasisLetters`, without the layout the Compose path measures against. A platform
 * renderer does its own single-line check: a word whose letters wrap to a second visual line draws plainly.
 * Returns the absolute start of the letters inside [text] with the cluster strings, or null for a plain word.
 * A letters region that would not sit inside [from]..[to] (a misaligned provider range) also stays plain.
 */
private fun emphasisLetters(
    text: String,
    from: Int,
    to: Int,
    range: IntRange,
    durationMs: Long,
    clusterStarts: IntArray,
): Pair<Int, List<String>>? {
    val start = clusterStarts.lastOrNull { it <= range.first } ?: 0
    val end = clusterStarts.firstOrNull { it > range.last } ?: text.length
    var first = start
    var last = end
    // Edge clusters that only carry whitespace leave no letter to move.
    while (first < last && text[first].isWhitespace()) first++
    while (last > first && text[last - 1].isWhitespace()) last--
    if (first >= last || first < from || last > to) return null
    val count = clusterStarts.count { it in first until last }
    if (!LyricsEmphasis.isEmphasized(text.substring(first, last), count, durationMs)) return null
    val letters = ArrayList<String>(count)
    var lettersFrom = -1
    for (clusterStart in clusterStarts) {
        if (clusterStart < first || clusterStart >= last) continue
        val clusterEnd = clusterStarts.firstOrNull { it > clusterStart }?.let { minOf(it, last) } ?: last
        if (lettersFrom < 0) lettersFrom = clusterStart
        letters += text.substring(clusterStart, clusterEnd)
    }
    return lettersFrom to letters
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

    /**
     * How row [index] reads by its distance from the sung line, with the same steps as the shared renderer.
     * A platform lyric view stands in for the refined Enhanced look, so the softening is the uniform veil
     * rather than the per-step ramp, reduced motion drops the blur but keeps the fade, and [staggerMs] is
     * the delay before the row follows a line change, as the shared ripple does.
     */
    fun depth(index: Int, reduceMotion: Boolean): NativeLyricsDepth {
        val distance = LyricsDepth.distance(index, primaryLine, isLineActive(index))
        val depth = LyricsDepth.of(distance, reduceMotion, uniformBlurDp = LyricsDepth.UNIFORM_BLUR_DP)
        val softened = if (reduceMotion) depth.copy(blurDp = 0f) else depth
        return NativeLyricsDepth(
            scale = softened.scale,
            blurPoints = softened.blurDp,
            alpha = softened.alpha,
            staggerMs = LyricsDepth.staggerMs(distance),
        )
    }
}

/**
 * A row's scale, blur radius in points and opacity by its distance from the sung line. [staggerMs] is how
 * long the row waits before it follows a line change, so the change ripples outward from the sung line.
 */
data class NativeLyricsDepth(
    val scale: Float,
    val blurPoints: Float,
    val alpha: Float,
    val staggerMs: Long = 0L,
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
