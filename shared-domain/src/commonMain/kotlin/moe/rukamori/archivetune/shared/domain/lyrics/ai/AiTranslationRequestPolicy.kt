package moe.rukamori.archivetune.shared.domain.lyrics.ai

import moe.rukamori.archivetune.shared.domain.lyrics.language.LyricsLanguagePolicy

/** Target-language spelling used for cache identity and request identity, ported from the Android helpers. */
object AiTargetLanguage {
    const val DEFAULT_TARGET = "ENGLISH"

    /** The target a request uses when the caller supplies none. */
    fun requested(raw: String): String = raw.ifBlank { DEFAULT_TARGET }

    /**
     * Canonical spelling for cache identity: "ENGLISH" becomes "English", and "CHINESE_SIMPLIFIED" becomes
     * "Chinese simplified". Two spellings of one target therefore share cached translations.
     */
    fun canonical(language: String): String =
        language
            .ifBlank { DEFAULT_TARGET }
            .replace('_', ' ')
            .lowercase()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}

/**
 * Everything that makes a translation request distinct from another one in flight. When a value here
 * changes, a request that captured an older identity must not write its result over the newer one.
 */
data class AiTranslationRequestIdentity(
    val configIdentity: String,
    val autoTranslate: Boolean,
    val effectiveTarget: String,
    val requestTarget: String,
    val excludedLanguages: Set<String>,
)

/** Why a translation may not start. [Ready] is the only outcome that lets a request go out. */
enum class AiTranslationPreflight {
    Ready,
    BlankLyrics,
    TargetChanged,
    NotConfigured,
    UnsupportedDeepLTarget,
    AutomaticDisabled,
    AutomaticExcluded,
}

/**
 * Full-settings snapshot read at request time. Every field the Android `translationIdentity` reads is here,
 * so eligibility and staleness are decided from one consistent view of the preferences.
 *
 * [translatorTargetLanguage] is null or blank when unset. The picker's separate language key is not part
 * of this snapshot, because it does not govern lyric freshness.
 */
data class AiTranslationSettingsSnapshot(
    val config: AiServiceSettings,
    val autoTranslate: Boolean,
    val translatorTargetLanguage: String?,
    val excludedLanguages: Set<String>,
) {
    /** The target the request actually uses: the stored one when set, else the request's own target. */
    fun effectiveTarget(requestTarget: String): String =
        translatorTargetLanguage?.ifBlank { requestTarget } ?: requestTarget

    fun identity(
        requestTarget: String,
        salt: String,
    ): AiTranslationRequestIdentity =
        AiTranslationRequestIdentity(
            configIdentity = config.cacheIdentity(salt),
            autoTranslate = autoTranslate,
            effectiveTarget = effectiveTarget(requestTarget),
            requestTarget = requestTarget,
            excludedLanguages = excludedLanguages,
        )

    /** True when a stored target is set and differs from this request's target. */
    fun isTargetChanged(requestTarget: String): Boolean =
        translatorTargetLanguage?.takeIf { it.isNotBlank() }?.let { it != requestTarget } == true

    /**
     * Decides whether a request may start, in the Android order. For a manual request, an
     * [AiTranslationPreflight.UnsupportedDeepLTarget] result is reported to the user. An automatic request
     * stays silent on it, which is the caller's contract.
     */
    fun preflight(
        lyrics: String,
        requestTarget: String,
        requestHints: List<String>,
        automatic: Boolean,
    ): AiTranslationPreflight {
        if (lyrics.isBlank()) return AiTranslationPreflight.BlankLyrics
        if (isTargetChanged(requestTarget)) return AiTranslationPreflight.TargetChanged
        if (!config.canCallApi) return AiTranslationPreflight.NotConfigured
        if (config.provider == AiProvider.DEEPL && DeepLTargets.targetCode(requestTarget) == null) {
            return AiTranslationPreflight.UnsupportedDeepLTarget
        }
        if (automatic) {
            if (!autoTranslate) return AiTranslationPreflight.AutomaticDisabled
            if (!LyricsLanguagePolicy.isAutomaticLanguageEligible(lyrics, excludedLanguages, requestHints)) {
                return AiTranslationPreflight.AutomaticExcluded
            }
        }
        return AiTranslationPreflight.Ready
    }
}

/** Result of checking one batch of segments against what the provider returned for it. */
sealed interface AiSegmentBatchCheck {
    data class Accepted(
        val segments: List<String>,
    ) : AiSegmentBatchCheck

    /** A count mismatch or a blank segment. The batch is never repaired by substituting originals. */
    data class Incomplete(
        val usableSegments: Int,
        val expectedSegments: Int,
    ) : AiSegmentBatchCheck
}

object AiSegmentBatchPolicy {
    const val MAX_ITEMS_PER_BATCH = 80
    const val MAX_CHARS_PER_BATCH = 6_000

    fun validate(
        expected: Int,
        received: List<String>,
    ): AiSegmentBatchCheck =
        if (received.size != expected || received.any { it.isBlank() }) {
            AiSegmentBatchCheck.Incomplete(
                usableSegments = received.count { it.isNotBlank() }.coerceAtMost(expected),
                expectedSegments = expected,
            )
        } else {
            AiSegmentBatchCheck.Accepted(received)
        }

    /**
     * Splits segments, given by their lengths, into contiguous batches that respect both bounds. A batch
     * opens a new one before it would exceed either limit, and a single over-long segment still gets a
     * batch of its own.
     */
    fun chunkByBudget(lengths: List<Int>): List<IntRange> {
        val chunks = ArrayList<IntRange>()
        var start = 0
        var count = 0
        var chars = 0
        lengths.forEachIndexed { index, length ->
            val next = chars + length
            if (count > 0 && (count >= MAX_ITEMS_PER_BATCH || next > MAX_CHARS_PER_BATCH)) {
                chunks.add(start until index)
                start = index
                count = 0
                chars = 0
            }
            count++
            chars += length
        }
        if (count > 0) chunks.add(start until lengths.size)
        return chunks
    }
}
