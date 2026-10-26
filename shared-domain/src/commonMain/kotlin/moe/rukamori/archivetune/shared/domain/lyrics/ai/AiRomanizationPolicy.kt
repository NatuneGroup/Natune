package moe.rukamori.archivetune.shared.domain.lyrics.ai

import moe.rukamori.archivetune.shared.domain.lyrics.language.LyricsLanguagePolicy
import moe.rukamori.archivetune.shared.domain.lyrics.romanization.hasRomanizableScript

/** The AI romanisation settings, as the lyrics renderers read them. */
data class AiRomanizationSettings(
    val enabled: Boolean,
    val auto: Boolean,
    val excludedLanguages: Set<String>,
    val config: AiServiceSettings,
) {
    /**
     * True when AI romanisation takes over from the built-in engines. It is deliberately independent of
     * [auto], so a configured but not automatic setup shows AI results only on demand.
     */
    val active: Boolean
        get() = enabled && config.supportsRomanization
}

/** Eligibility, session identity and cacheability for AI romanisation, ported from `AiLyricsRomanization`. */
object AiRomanizationPolicy {
    /** Shared fallback policy: excluded or ineligible AI must not suppress the built-in phonetics. */
    fun isEligible(
        settings: AiRomanizationSettings,
        lines: List<String>,
        sourceLanguageHints: List<String> = emptyList(),
    ): Boolean {
        if (!settings.active || lines.isEmpty()) return false
        return LyricsLanguagePolicy.isAutomaticLanguageEligible(
            lines.joinToString("\n"),
            settings.excludedLanguages,
            sourceLanguageHints,
        )
    }

    /** Whether any line has a script worth a request. Latin-only lyrics never spend one. */
    fun needsRequest(lines: List<String>): Boolean = lines.any { hasRomanizableScript(it) }

    fun hintIdentity(hints: List<String>): String = LyricsLanguagePolicy.normalizedSourceLanguageHints(hints).joinToString(",")

    /**
     * Identity of one track's lyrics as currently parsed. A refetch, an edit, or a changed hint produces a
     * different key, and a replay of the same text reuses it.
     */
    fun sessionKey(
        mediaId: String?,
        lyrics: String?,
        sourceLanguageHints: List<String> = LyricsLanguagePolicy.sourceLanguageHints(lyrics),
    ): String =
        "${mediaId.orEmpty()}|${lyrics?.length ?: 0}|${LyricsFingerprint.of(lyrics.orEmpty())}|" +
            hintIdentity(sourceLanguageHints)

    /** Per-line candidate rule: an unchanged echo carries no information, so it counts as no romanisation. */
    fun candidateLine(
        original: String,
        candidate: String?,
    ): String? = candidate?.trim()?.takeIf { it.isNotEmpty() && !it.equals(original.trim(), ignoreCase = true) }

    /** Romanizer-level cache rule: only a complete set of romanizable lines may be cached as a success. */
    fun isCacheable(
        lines: List<String>,
        results: List<String?>,
    ): Boolean {
        val indexed = lines.indices.filter { lines[it].isNotBlank() }
        return indexed.isNotEmpty() && indexed.all { results.getOrNull(it) != null }
    }

    /** Session-level publication rule over the per-line map: every romanizable line must be present. */
    fun isPublishable(
        lines: List<String>,
        byLine: Map<String, String>,
    ): Boolean = byLine.isNotEmpty() && lines.filter { hasRomanizableScript(it) }.all { byLine.containsKey(it.trim()) }
}
