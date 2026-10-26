package moe.rukamori.archivetune.shared.ui.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsDetection
import moe.rukamori.archivetune.shared.domain.lyrics.ai.*
import moe.rukamori.archivetune.shared.domain.repository.AiTextService
import moe.rukamori.archivetune.shared.domain.settings.SettingsStore
import moe.rukamori.archivetune.shared.domain.settings.aiLyricsSessionSettings

/** Host lifetime, not a composable singleton. Automatic and manual actions share these two owners. */
class LyricsSessions(
    scope: CoroutineScope,
    private val store: SettingsStore,
    service: AiTextService,
    identitySalt: String,
    reportFailure: (AiLyricsOperation) -> Unit = {},
) {
    val translation = AiLyricsSessionCoordinator(
        scope, identitySalt, service::translateLines,
        { config, lines -> service.romanizeLines(config, lines, "plain text") }, reportFailure,
    )
    val romanization = AiLyricsSessionCoordinator(
        scope, identitySalt, service::translateLines,
        { config, lines -> service.romanizeLines(config, lines, "plain text") }, reportFailure,
    )
    private data class Selection(val mediaId: String, val lyrics: String, val hints: List<String>)
    private val selection = MutableStateFlow<Selection?>(null)
    private val dismissed = MutableStateFlow<Set<String>>(emptySet())
    private val actions = Mutex()
    private val appliedSettings = MutableStateFlow<AiLyricsSessionSettings?>(null)

    init {
        scope.launch {
            combine(
                selection,
                store.snapshots.filterNotNull().map { it.aiLyricsSessionSettings() }.distinctUntilChanged(),
            ) { input, settings -> input to settings }.collect { (input, settings) ->
                actions.withLock {
                    if (input != selection.value || settings != store.snapshots.value?.aiLyricsSessionSettings()) return@withLock
                    translation.updateSettings(settings)
                    romanization.updateSettings(settings)
                    appliedSettings.value = settings
                    val target = settings.translation.translatorTargetLanguage.orEmpty().ifBlank { AiTargetLanguage.DEFAULT_TARGET }
                    val request = input?.let {
                        AiLyricsSessionRequest(it.mediaId, it.lyrics, AiLyricsOperation.TRANSLATE, target, it.hints, automatic = true)
                    }
                    translation.select(request)
                    romanization.select(request?.copy(operation = AiLyricsOperation.ROMANIZE))
                    if (request != null) {
                        if (settings.translation.autoTranslate && request.mediaId !in dismissed.value &&
                            !LyricsDetection.hasTranslation(request.lyrics)
                        ) translation.submit(request, settings)
                        if (settings.romanization.auto) romanization.submit(request.copy(operation = AiLyricsOperation.ROMANIZE), settings)
                    }
                }
            }
        }
    }

    suspend fun select(mediaId: String?, lyrics: String?, hints: List<String> = emptyList()) = actions.withLock {
        val next = if (mediaId == null || lyrics == null) null else Selection(mediaId, lyrics, hints.toList())
        if (selection.value == next) return@withLock
        // Clear synchronously before the asynchronous observer can start a replacement.
        translation.select(null)
        romanization.select(null)
        selection.value = next
    }

    suspend fun manual(operation: AiLyricsOperation): Boolean = actions.withLock {
        val input = selection.value ?: return@withLock false
        val settings = store.snapshots.value?.aiLyricsSessionSettings() ?: return@withLock false
        translation.updateSettings(settings)
        romanization.updateSettings(settings)
        appliedSettings.value = settings
        val target = settings.translation.translatorTargetLanguage.orEmpty().ifBlank { AiTargetLanguage.DEFAULT_TARGET }
        val request = AiLyricsSessionRequest(input.mediaId, input.lyrics, operation, target, input.hints)
        if (operation == AiLyricsOperation.TRANSLATE) {
            dismissed.value = dismissed.value - input.mediaId
            translation.submit(request, settings)
        } else romanization.submit(request, settings)
        true
    }

    /** Called only after an actual undo commit, never from observing an old database row. */
    suspend fun translationUndone(mediaId: String) = actions.withLock {
        dismissed.value = dismissed.value + mediaId
        translation.cancel()
    }

    /** Synchronous transaction guard also sees a settings edit not yet delivered to the observer. */
    fun isCurrent(result: AiLyricsSessionResult): Boolean {
        val input = selection.value ?: return false
        val owner = if (result.request.operation == AiLyricsOperation.TRANSLATE) translation else romanization
        return input.mediaId == result.request.mediaId && input.lyrics == result.request.lyrics &&
            appliedSettings.value == store.snapshots.value?.aiLyricsSessionSettings() &&
            owner.state.value.result === result
    }
}
