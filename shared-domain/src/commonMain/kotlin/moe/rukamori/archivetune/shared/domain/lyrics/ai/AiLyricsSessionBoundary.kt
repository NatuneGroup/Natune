/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.lyrics.ai

enum class AiLyricsOperation { TRANSLATE, ROMANIZE }

data class AiLyricsSessionRequest(
    val mediaId: String,
    val lyrics: String,
    val operation: AiLyricsOperation,
    val targetLanguage: String = AiTargetLanguage.DEFAULT_TARGET,
    val sourceLanguageHints: List<String> = emptyList(),
    val automatic: Boolean = false,
)

data class AiLyricsSessionSettings(
    val translation: AiTranslationSettingsSnapshot,
    val romanization: AiRomanizationSettings,
)

/** Original text addresses romanization, not a renderer's filtered/reordered row indices. */
data class AiLyricsSessionResult(
    val request: AiLyricsSessionRequest,
    val translatedLyrics: String? = null,
    val romanizedByLine: Map<String, String> = emptyMap(),
)

enum class AiLyricsSessionFailure { INELIGIBLE, INCOMPLETE, PROVIDER }

data class AiLyricsSessionState(
    val request: AiLyricsSessionRequest? = null,
    val running: Boolean = false,
    val result: AiLyricsSessionResult? = null,
    val failure: AiLyricsSessionFailure? = null,
)
