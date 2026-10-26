package moe.rukamori.archivetune.shared.domain.lyrics.ai

import moe.rukamori.archivetune.shared.domain.lyrics.LyricsNormalizer

/**
 * FNV-1a 64-bit over the UTF-8 bytes of a text. It is deterministic and cheap, which is all a cache
 * identity needs. It is not collision-resistant against hostile input, so the cache also compares the
 * normalised length, and a collision could only ever surface a translation of other lyrics of that length.
 */
internal object LyricsFingerprint {
    private val OFFSET_BASIS: Long = 0xcbf29ce484222325uL.toLong()
    private const val PRIME: Long = 0x100000001b3L

    fun of(text: String): Long {
        var hash = OFFSET_BASIS
        for (byte in text.encodeToByteArray()) {
            hash = (hash xor (byte.toLong() and 0xffL)) * PRIME
        }
        return hash
    }
}

/**
 * Identity of one cached translation: the provider configuration (a salted digest, never the credential),
 * the canonical target language, and the normalised lyrics. Changing any of them addresses a different entry.
 */
data class AiTranslationCacheKey(
    val configIdentity: String,
    val targetLanguage: String,
    val lyricsFingerprint: Long,
    val lyricsLength: Int,
) {
    companion object {
        fun of(
            configIdentity: String,
            targetLanguage: String,
            lyrics: String,
        ): AiTranslationCacheKey {
            val normalized = LyricsNormalizer.normalize(lyrics)
            return AiTranslationCacheKey(
                configIdentity = configIdentity,
                targetLanguage = AiTargetLanguage.canonical(targetLanguage),
                lyricsFingerprint = LyricsFingerprint.of(normalized),
                lyricsLength = normalized.length,
            )
        }
    }
}

enum class AiTranslationFailure {
    NO_TRANSLATABLE_SEGMENTS,
    PROVIDER_REJECTED,
    OBSOLETE_REQUEST,
}

/** The outcome of one translation attempt. Only [Complete] can ever become a cache entry. */
sealed interface AiTranslationAttempt {
    /** Every segment came back non-blank and the document was rebuilt around them. */
    data class Complete(
        val translatedLyrics: String,
    ) : AiTranslationAttempt

    /** Some segments came back and others did not. Partial results are never a success. */
    data class Partial(
        val translatedSegments: Int,
        val expectedSegments: Int,
    ) : AiTranslationAttempt

    data class Failed(
        val reason: AiTranslationFailure,
    ) : AiTranslationAttempt
}

/**
 * A cached successful translation. The constructor is private, so the only way to create one is
 * [successOrNull], which refuses every response that is not a complete, non-blank result.
 */
class AiTranslationCacheEntry private constructor(
    val key: AiTranslationCacheKey,
    val translatedLyrics: String,
) {
    override fun equals(other: Any?): Boolean =
        other is AiTranslationCacheEntry && other.key == key && other.translatedLyrics == translatedLyrics

    override fun hashCode(): Int = 31 * key.hashCode() + translatedLyrics.hashCode()

    companion object {
        /**
         * An entry for a complete, non-blank attempt, or null. Partial, failed, obsolete and blank responses
         * are never stored, so the next request retries them instead of replaying a broken result.
         */
        fun successOrNull(
            key: AiTranslationCacheKey,
            attempt: AiTranslationAttempt,
        ): AiTranslationCacheEntry? {
            val complete = attempt as? AiTranslationAttempt.Complete ?: return null
            if (complete.translatedLyrics.isBlank()) return null
            return AiTranslationCacheEntry(key, complete.translatedLyrics)
        }
    }
}

enum class AiCacheInvalidation {
    KEEP,
    INVALIDATE_TARGET_CHANGED,
}

object AiTranslationCachePolicy {
    /** An entry stays usable only for the target it was translated into. */
    fun isValidFor(
        entry: AiTranslationCacheEntry,
        currentTarget: String,
    ): Boolean = entry.key.targetLanguage == AiTargetLanguage.canonical(currentTarget)

    /**
     * Whether a target change drops the cached translations. Targets compare canonically, so "ENGLISH" and
     * "English" are the same request. The request identity compares the raw strings, since that governs
     * cancelling an in-flight request, not what the cache holds.
     */
    fun invalidationDecision(
        previousTarget: String,
        newTarget: String,
    ): AiCacheInvalidation =
        if (AiTargetLanguage.canonical(previousTarget) == AiTargetLanguage.canonical(newTarget)) {
            AiCacheInvalidation.KEEP
        } else {
            AiCacheInvalidation.INVALIDATE_TARGET_CHANGED
        }

    fun successEntryOrNull(
        key: AiTranslationCacheKey,
        attempt: AiTranslationAttempt,
    ): AiTranslationCacheEntry? = AiTranslationCacheEntry.successOrNull(key, attempt)
}
