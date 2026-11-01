package moe.rukamori.archivetune.shared.ui.lyrics

import moe.rukamori.archivetune.shared.domain.lyrics.unicode.UnicodeBidi
import moe.rukamori.archivetune.shared.domain.lyrics.unicode.UnicodeScript
import moe.rukamori.archivetune.shared.domain.lyrics.unicode.UnicodeScripts
import kotlin.math.PI
import kotlin.math.sin

/**
 * The emphasis gate and the per-letter ripple of the refined sweep. A word earns the ripple only when
 * it is held long enough ([MIN_DURATION_MS]), short enough ([MAX_CLUSTERS] grapheme clusters) and
 * written in a script whose glyphs never join across neighbours, so cutting the word into separately
 * moving pieces cannot tear the shaping. Everything else sweeps plainly: no lift, no glow.
 */
object LyricsEmphasis {
    /** A word earns emphasis only from this duration. */
    const val MIN_DURATION_MS = 1_000L

    /** Emphasis is for short sung words; longer ones keep the plain sweep. */
    const val MAX_CLUSTERS = 7

    /** How far a bumping letter rises. */
    const val LIFT_DP = 2f

    /** How much a bumping letter swells past its set size. */
    const val SCALE_BOOST = 0.10f

    /** The stagger divisor the per-letter wave shares with the whole word's span. */
    private const val STAGGER_DIVISOR = 2.5f

    /**
     * Scripts whose letters shape the same whether their neighbours draw with them or not. Joined
     * scripts (Arabic, Indic, Thai, ...) tear when a cluster moves alone, and ideographic scripts are
     * left out by design — the ripple is a latin-alphabet emphasis.
     */
    private val SAFE_SCRIPTS =
        setOf(
            UnicodeScript.COMMON,
            UnicodeScript.INHERITED,
            UnicodeScript.LATIN,
            UnicodeScript.GREEK,
            UnicodeScript.CYRILLIC,
            UnicodeScript.ARMENIAN,
            UnicodeScript.GEORGIAN,
        )

    /** Whether a word of [clusterCount] clusters held [durationMs] earns the per-letter ripple. */
    fun isEmphasized(wordText: String, clusterCount: Int, durationMs: Long): Boolean =
        durationMs >= MIN_DURATION_MS && clusterCount in 1..MAX_CLUSTERS && supports(wordText)

    /**
     * Whether [wordText] can be cut into per-letter slices safely: no right-to-left direction and no
     * code point outside [SAFE_SCRIPTS].
     */
    fun supports(wordText: String): Boolean {
        if (wordText.isEmpty() || UnicodeBidi.isRtlText(wordText)) return false
        var index = 0
        while (index < wordText.length) {
            val codePoint = wordText.codePointAt(index)
            if (UnicodeScripts.scriptOf(codePoint) !in SAFE_SCRIPTS) return false
            index += if (codePoint >= 0x10000) 2 else 1
        }
        return true
    }

    /**
     * How much letter [index] (in sung order) of an emphasized word is bumping at [positionMs], from
     * 0 to 1. Each letter runs a full-duration sine bump delayed by a share of the word's span, so the
     * swell travels through the word like a wave. The value is a pure function of the position, so a
     * seek lands on the right frame.
     */
    fun rippleAt(
        wordStartMs: Long,
        durationMs: Long,
        clusterCount: Int,
        index: Int,
        positionMs: Long,
    ): Float {
        if (clusterCount <= 0 || index !in 0 until clusterCount) return 0f
        val duration = durationMs.coerceAtLeast(1L)
        val staggerMs = (duration / STAGGER_DIVISOR / clusterCount).toLong()
        val local = positionMs - wordStartMs - staggerMs * index
        if (local <= 0L || local >= duration) return 0f
        return sin(PI.toFloat() * (local.toFloat() / duration))
    }
}
