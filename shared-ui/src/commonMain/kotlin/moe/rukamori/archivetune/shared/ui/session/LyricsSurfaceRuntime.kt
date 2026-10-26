package moe.rukamori.archivetune.shared.ui.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.lyrics.ai.AiLyricsOperation
import moe.rukamori.archivetune.shared.domain.lyrics.ai.AiLyricsSessionResult
import moe.rukamori.archivetune.shared.domain.lyrics.ai.AiRomanizationPolicy
import moe.rukamori.archivetune.shared.domain.lyrics.language.LyricsLanguagePolicy
import moe.rukamori.archivetune.shared.domain.lyrics.romanization.JapaneseTokenizer
import moe.rukamori.archivetune.shared.domain.lyrics.romanization.LyricsRomanizationPreferences
import moe.rukamori.archivetune.shared.domain.lyrics.romanization.LyricsRomanizer
import moe.rukamori.archivetune.shared.domain.lyrics.romanization.LyricsTransliterator
import moe.rukamori.archivetune.shared.domain.repository.AiTextService
import moe.rukamori.archivetune.shared.domain.settings.LyricsRomanizeChineseKey
import moe.rukamori.archivetune.shared.domain.settings.LyricsRomanizeHindiKey
import moe.rukamori.archivetune.shared.domain.settings.LyricsRomanizeJapaneseKey
import moe.rukamori.archivetune.shared.domain.settings.LyricsRomanizeKoreanKey
import moe.rukamori.archivetune.shared.domain.settings.LyricsRomanizeOtherLanguagesKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.SettingsStore
import moe.rukamori.archivetune.shared.domain.settings.aiLyricsSessionSettings
import moe.rukamori.archivetune.shared.ui.host.HostLyricsDocument
import moe.rukamori.archivetune.shared.ui.lyrics.AiRomanizationReadings
import moe.rukamori.archivetune.shared.ui.lyrics.LyricsRomanizationPresentation
import moe.rukamori.archivetune.shared.ui.lyrics.LyricsRomanizationPresenter
import moe.rukamori.archivetune.shared.ui.lyrics.LyricsRomanizationState

/**
 * The host lifetime owner of the AI lyric sessions and of every lyric surface's phonetic readings.
 * It selects the AI session the lyrics owner reports, applies the persisted automatic flags the way
 * [LyricsSessions] schedules them, and publishes the presentation the renderers accept for the
 * document currently on screen. Constructed once per session graph; [start]/[close] follow it.
 */
class LyricsSurfaceRuntime(
    private val scope: CoroutineScope,
    private val store: SettingsStore,
    service: AiTextService,
    private val lyrics: StateFlow<HostLyricsDocument?>,
    packTokenizer: Flow<JapaneseTokenizer?>,
    transliterator: LyricsTransliterator?,
    identitySalt: String,
    private val report: (String) -> Unit,
) {
    /** Manual and automatic translation/romanization owners; surfaces and menus drive these. */
    val sessions = LyricsSessions(
        scope = scope,
        store = store,
        service = service,
        identitySalt = identitySalt,
        reportFailure = { operation -> report("ai lyrics ${operation.name.lowercase()} request failed") },
    )

    private val romanizer = packTokenizer.map { LyricsRomanizer(it, transliterator) }

    /** Published off the composition thread; every lyrics surface collects it. */
    val romanization = LyricsRomanizationState(
        LyricsRomanizationPresentation.off(sessionKey(null)),
    )

    private var driver: Job? = null

    fun start() {
        driver = scope.launch {
            launch {
                lyrics.collect { document ->
                    try {
                        sessions.select(document?.occurrenceId, document?.raw?.takeIf { it.isNotEmpty() }, hints(document))
                    } catch (failure: Exception) {
                        report("ai lyrics session selection failed: ${failure::class.simpleName}")
                    }
                }
            }
            launch {
                combine(
                    lyrics,
                    store.snapshots.filterNotNull(),
                    romanizer,
                    sessions.romanization.state,
                ) { document, preferences, engine, ai -> publication(document, preferences, engine, ai.result) }
                    .collect { romanization.publish(it) }
            }
        }
    }

    fun close() {
        driver?.cancel()
    }

    /**
     * The key every surface passes to the renderer for [document]. It covers the track, the raw text
     * and the language hints, so a refetch, an edit or a translation invalidates stale readings.
     */
    fun sessionKey(document: HostLyricsDocument?): String =
        document?.let { AiRomanizationPolicy.sessionKey(it.occurrenceId, it.raw, hints(it)) }
            ?: NO_LYRICS_SESSION_KEY

    private fun hints(document: HostLyricsDocument?): List<String> =
        LyricsLanguagePolicy.sourceLanguageHints(document?.raw?.takeIf { it.isNotEmpty() })

    private fun publication(
        document: HostLyricsDocument?,
        preferences: PreferenceSnapshot,
        engine: LyricsRomanizer,
        aiResult: AiLyricsSessionResult?,
    ): LyricsRomanizationPresentation {
        val key = sessionKey(document)
        if (document == null) return LyricsRomanizationPresentation.off(key)
        val aiSettings = preferences.aiLyricsSessionSettings().romanization
        val readings = aiResult
            ?.takeIf {
                it.request.operation == AiLyricsOperation.ROMANIZE &&
                    it.request.mediaId == document.occurrenceId && it.request.lyrics == document.raw
            }
            ?.let { AiRomanizationReadings(key, it.romanizedByLine) }
        return LyricsRomanizationPresenter.present(
            sessionKey = key,
            lines = document.lines,
            builtIn = preferences.lyricsRomanizationPreferences(),
            romanizer = engine,
            aiSettings = aiSettings,
            sourceLanguageHints = hints(document),
            ai = readings,
        )
    }

    private companion object {
        const val NO_LYRICS_SESSION_KEY = "no-lyrics"
    }
}

/** The five built-in switches exactly as the renderer reads them; AI ownership never suppresses them here. */
internal fun PreferenceSnapshot.lyricsRomanizationPreferences() = LyricsRomanizationPreferences(
    romanizeJapanese = this[LyricsRomanizeJapaneseKey, false],
    romanizeKorean = this[LyricsRomanizeKoreanKey, true],
    romanizeChinese = this[LyricsRomanizeChineseKey, true],
    romanizeHindi = this[LyricsRomanizeHindiKey, true],
    romanizeOther = this[LyricsRomanizeOtherLanguagesKey, true],
)
