package moe.rukamori.archivetune.shared.ui.settings

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.shared.domain.lyrics.ai.*
import moe.rukamori.archivetune.shared.domain.repository.AiTextService
import moe.rukamori.archivetune.shared.domain.settings.*

enum class AiSettingsOperation { MODELS, TEST }
data class AiSettingsActionState(
    val running: AiSettingsOperation? = null,
    val models: List<String> = emptyList(),
    val failed: Boolean = false,
    /** The safe class of the last failure, for the hint line. Null when the last operation did not fail. */
    val failure: AiServiceFailureKind? = null,
)

/** Uses the same published service as lyric sessions; hosts supply its real engine/proxy/TLS owner. */
class AiSettingsActions(
    private val scope: CoroutineScope,
    private val settings: SettingsController,
    private val service: AiTextService,
) {
    private val lock = Mutex()
    private val state = MutableStateFlow(AiSettingsActionState())
    val actions = state.asStateFlow()
    private var generation = 0L
    private var config: AiServiceSettings? = null
    private var job: Job? = null
    private var closed = false
    private val observer = scope.launch {
        settings.store.snapshots.filterNotNull().collect {
            lock.withLock {
                val latest = settings.store.snapshots.value?.aiServiceSettings()
                if (config != latest) invalidate(latest)
            }
        }
    }

    suspend fun fetchModels(): Job? = start(AiSettingsOperation.MODELS)
    suspend fun testApi(): Job? = start(AiSettingsOperation.TEST)

    private suspend fun start(operation: AiSettingsOperation): Job? = lock.withLock {
        if (closed) return null
        val captured = settings.store.snapshots.value?.aiServiceSettings() ?: return null
        if (captured != config) invalidate(captured)
        if (job?.isActive == true || state.value.running != null) return null
        if (!captured.canCallApi || operation == AiSettingsOperation.TEST && !captured.canTestConfiguration()) return null
        if (operation == AiSettingsOperation.MODELS && captured.provider in setOf(AiProvider.NONE, AiProvider.DEEPL, AiProvider.CUSTOM)) return null
        val token = ++generation
        state.value = state.value.copy(running = operation, failed = false, failure = null)
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (operation == AiSettingsOperation.MODELS) {
                    val models = service.listModels(captured).filter { it.isNotBlank() && it.length <= 512 }.distinct()
                    lock.withLock {
                        if (current(token, captured)) state.value = state.value.copy(models = models)
                    }
                } else {
                    val failure: AiServiceFailureKind? = try { service.test(captured); null }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { failureKind(error) }
                    persistValidation(token, captured, if (failure == null) "SUCCESS" else "FAILED")
                    lock.withLock {
                        if (current(token, captured)) state.value = state.value.copy(failed = failure != null, failure = failure)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val failure = failureKind(error)
                lock.withLock {
                    if (current(token, captured)) state.value = state.value.copy(failed = true, failure = failure)
                }
            } finally {
                withContext(NonCancellable) {
                    lock.withLock {
                        if (token == generation) {
                            job = null
                            state.value = state.value.copy(running = null)
                        }
                    }
                }
            }
        }.also { it.start() }
        job
    }

    private suspend fun persistValidation(token: Long, captured: AiServiceSettings, value: String): Unit = lock.withLock {
        if (!current(token, captured)) return@withLock
        // The editor guard is inside the durable transaction. A settings observer can arrive later.
        settings.edit {
            if (snapshot().aiServiceSettings() == captured)
                this[AiApiValidationStatusKey] = value
        }
    }

    suspend fun selectModel(id: String): Boolean = lock.withLock {
        val captured = config ?: return false
        if (closed || id !in state.value.models || settings.store.snapshots.value?.aiServiceSettings() != captured) return false
        val change = settings.edit {
            if (snapshot().aiServiceSettings() == captured)
                setAiConfiguration(if (captured.provider == AiProvider.OPENROUTER) OpenRouterModelKey else AiSelectedModelKey, id)
        }
        change.after.aiServiceSettings().model == id
    }

    /**
     * Stops the running model fetch or API test as if it never started. Its generation is retired first, so a result that
     * still arrives is dropped, nothing is persisted, and the stored validation keeps its last real answer. Models already
     * listed are kept.
     */
    suspend fun cancelRunning(): Unit = lock.withLock {
        val running = job ?: return@withLock
        generation++
        job = null
        running.cancel()
        state.value = state.value.copy(running = null)
    }

    suspend fun close() {
        lock.withLock { closed = true; invalidate(null) }
        observer.cancelAndJoin()
    }

    /** Only the safe kind a host supplies is kept; any other exception reads as [AiServiceFailureKind.OTHER]. */
    private fun failureKind(error: Exception): AiServiceFailureKind =
        (error as? AiServiceFailure)?.kind ?: AiServiceFailureKind.OTHER

    private fun current(token: Long, captured: AiServiceSettings): Boolean =
        !closed && token == generation && settings.store.snapshots.value?.aiServiceSettings() == captured

    private fun invalidate(latest: AiServiceSettings?) {
        generation++
        job?.cancel()
        job = null
        config = latest
        state.value = AiSettingsActionState()
    }
}
