package moe.rukamori.archivetune.shared.ui.lyrics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsLine
import moe.rukamori.archivetune.shared.domain.lyrics.ai.AiRomanizationPolicy
import moe.rukamori.archivetune.shared.domain.lyrics.ai.AiRomanizationSettings
import moe.rukamori.archivetune.shared.domain.lyrics.romanization.LyricsPhonetics
import moe.rukamori.archivetune.shared.domain.lyrics.romanization.LyricsRomanizationPreferences
import moe.rukamori.archivetune.shared.domain.lyrics.romanization.LyricsRomanizer

/** Where the readings in a presentation came from. */
enum class LyricsRomanizationSource {
    OFF,
    BUILT_IN,
    PROVIDER,
    AI,
}

/**
 * Phonetic readings for one lyrics session. [phonetics] is aligned with the lines by index, and a null
 * entry means that line has no reading. Renderers accept a presentation only when [sessionKey] matches the
 * lyrics on screen, so a result for an earlier track, text or setting cannot replace the current one.
 */
data class LyricsRomanizationPresentation(
    val sessionKey: String,
    val source: LyricsRomanizationSource,
    val phonetics: List<LyricsPhonetics?> = emptyList(),
) {
    companion object {
        fun off(sessionKey: String): LyricsRomanizationPresentation =
            LyricsRomanizationPresentation(sessionKey, LyricsRomanizationSource.OFF)
    }
}

/**
 * One AI romanization result for a session, keyed by the trimmed original line text as the coordinator publishes
 * it. Eligibility is not stored here: it comes from the settings and hints current when the lyrics are shown.
 */
data class AiRomanizationReadings(
    val sessionKey: String,
    val romanizedByLine: Map<String, String>,
)

/**
 * The injected presentation boundary. The romanization coordinator publishes here, off the composition
 * thread, and every lyrics surface collects it. A new publication updates visible text without a remount.
 */
class LyricsRomanizationState(initial: LyricsRomanizationPresentation) {
    private val mutable = MutableStateFlow(initial)

    val presentation: StateFlow<LyricsRomanizationPresentation> = mutable.asStateFlow()

    fun publish(presentation: LyricsRomanizationPresentation) {
        mutable.value = presentation
    }
}

/**
 * Chooses the readings a session shows. Eligibility is judged from the settings, hints and lines current when the
 * lyrics are shown, as the Android renderers do. An eligible AI setup owns the lines even before its result
 * arrives: built-in and provider readings stay off, and a line waiting for its result shows no reading rather
 * than flashing a built-in one. Ineligible or excluded AI falls back to the built-in and provider phonetics,
 * which the shared [LyricsRomanizer] aligns to words in one place.
 */
object LyricsRomanizationPresenter {
    fun present(
        sessionKey: String,
        lines: List<LyricsLine>,
        builtIn: LyricsRomanizationPreferences,
        romanizer: LyricsRomanizer,
        aiSettings: AiRomanizationSettings? = null,
        sourceLanguageHints: List<String> = emptyList(),
        ai: AiRomanizationReadings? = null,
    ): LyricsRomanizationPresentation {
        val aiOwnsLines = aiSettings != null &&
            AiRomanizationPolicy.isEligible(aiSettings, lines.map { it.text }, sourceLanguageHints)
        if (aiOwnsLines) {
            // A result for another session never fills these lines, so a track change cannot show its text here.
            val byLine = ai?.takeIf { it.sessionKey == sessionKey }?.romanizedByLine.orEmpty()
            val readings = lines.map { line ->
                AiRomanizationPolicy.candidateLine(line.text, byLine[line.text.trim()])
                    ?.let { LyricsPhonetics(line = it) }
            }
            return LyricsRomanizationPresentation(sessionKey, LyricsRomanizationSource.AI, readings)
        }
        if (!builtIn.isEnabled) return LyricsRomanizationPresentation.off(sessionKey)

        val phonetics = lines.map { line ->
            romanizer.phoneticsForLine(line, builtIn).takeIf { it.line != null || it.words != null }
        }
        if (phonetics.all { it == null }) return LyricsRomanizationPresentation.off(sessionKey)
        val providerLines = lines.any { it.providerRomanizedText != null || !it.providerRomanizedWords.isNullOrEmpty() }
        val source = if (providerLines) LyricsRomanizationSource.PROVIDER else LyricsRomanizationSource.BUILT_IN
        return LyricsRomanizationPresentation(sessionKey, source, phonetics)
    }
}
