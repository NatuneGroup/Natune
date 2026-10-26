package moe.rukamori.archivetune.shared.domain.lyrics.romanization

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.shared.domain.settings.LyricsRomanizeJapaneseKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceEditor
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.autoAttemptedKey
import moe.rukamori.archivetune.shared.domain.settings.removedKey

/**
 * One host-owned pack lifetime. Pass M6 SettingsStore.snapshots and { store.edit(it); Unit }:
 * null is not a default snapshot, and editSettings MUST execute its editor atomically on current data.
 * All potentially expensive work uses workerDispatcher; construction/foreground never await startup IO.
 */
class JapaneseLanguagePackManager(
    scope: CoroutineScope,
    workerDispatcher: CoroutineDispatcher,
    private val snapshots: StateFlow<PreferenceSnapshot?>,
    private val editSettings: suspend (PreferenceEditor.() -> Unit) -> Unit,
    private val io: JapanesePackIo,
    private val primaryDeviceRegion: () -> String?,
    private val networkAvailable: suspend () -> Boolean,
) {
    private val worker = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + workerDispatcher)
    private val gate = Mutex()
    private val files = Mutex()
    private val mutableState = MutableStateFlow<JapaneseLanguagePackState>(JapaneseLanguagePackState.Checking)
    val state: StateFlow<JapaneseLanguagePackState> = mutableState.asStateFlow()
    private val mutableTokenizer = MutableStateFlow<JapaneseTokenizer?>(null)
    val tokenizer: StateFlow<JapaneseTokenizer?> = mutableTokenizer.asStateFlow()
    private var loaded: JapaneseTokenizer? = null
    private var generation = 0L
    private var consideredForeground = false
    private var closed = false
    private var operation: Job? = null
    private val initialization: Job

    init {
        initialization = worker.launch {
            files.withLock {
                try {
                    // Baseline ignores a failed stale-partial deletion; it must not delete a valid target.
                    try { io.discardStaged() } catch (error: CancellationException) { throw error } catch (_: Exception) { }
                    val bytes = io.readInstalled()
                    val ready = if (bytes == null) null else requireNotNull(openJapanesePack(bytes, io::inflateRaw))
                    gate.withLock {
                        if (!closed && mutableState.value != JapaneseLanguagePackState.Removing &&
                            (mutableState.value as? JapaneseLanguagePackState.Failed)?.removalFailed != true) {
                            loaded = ready
                            publishTokenizer()
                            if (mutableState.value == JapaneseLanguagePackState.Checking) {
                                mutableState.value = if (ready == null) JapaneseLanguagePackState.NotInstalled else installed()
                            }
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    runCatching { io.removeInstalled() }
                    gate.withLock {
                        if (!closed && mutableState.value == JapaneseLanguagePackState.Checking) {
                            mutableState.value = JapaneseLanguagePackState.Failed(JapanesePackFailure.INVALID)
                        }
                    }
                }
            }
        }
        worker.launch {
            snapshots.filterNotNull().collect {
                gate.withLock { if (!closed) publishTokenizer() }
            }
        }
    }

    /** Renderers only read already-ready memory. Explicit off detaches immediately, even before collection. */
    fun tokenizerOrNull(): JapaneseTokenizer? =
        if (snapshots.value?.get(LyricsRomanizeJapaneseKey) == false) null else tokenizer.value

    /** Call only from the real foreground event; no network watcher or startup caller. */
    suspend fun onForeground(): Job? = gate.withLock {
        if (closed || consideredForeground) return@withLock null
        consideredForeground = true
        worker.launch {
            initialization.join()
            snapshots.filterNotNull().first()
            try {
                val region = primaryDeviceRegion()
                var eligible = false
                val considerationGeneration = gate.withLock {
                    if (closed) return@withLock null
                    // Recheck INSIDE the owner's atomic edit, not against an obsolete flow snapshot.
                    editSettings {
                        if (japanesePackAutomaticEligible(region, loaded != null, this[LyricsRomanizeJapaneseKey],
                                this[removedKey] == true, this[autoAttemptedKey] == true)) {
                            this[autoAttemptedKey] = true
                            eligible = true
                        }
                    }
                    generation
                }
                // Offline consumes the one persisted consideration. Wi-Fi/cellular are equally eligible.
                if (eligible && considerationGeneration != null && networkAvailable()) {
                    requestInstall(automatic = true, expectedGeneration = considerationGeneration)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // The Android foreground consideration is best effort, not an install-failure UI.
            }
        }
    }

    suspend fun install(): Job? = requestInstall(automatic = false)

    private suspend fun requestInstall(automatic: Boolean, expectedGeneration: Long? = null): Job? = gate.withLock {
        if (closed || (expectedGeneration != null && expectedGeneration != generation) ||
            (automatic && (mutableState.value as? JapaneseLanguagePackState.Failed)?.removalFailed == true) ||
            operation?.isActive == true || loaded != null ||
            mutableState.value == JapaneseLanguagePackState.Removing) return@withLock null
        val id = ++generation
        mutableState.value = JapaneseLanguagePackState.Downloading()
        worker.launch(start = CoroutineStart.LAZY) {
            initialization.join()
            snapshots.filterNotNull().first()
            files.withLock {
                var promoted = false
                var published = false
                try {
                    currentCoroutineContext().ensureActive()
                    var starting: Boolean? = null
                    val proceed = gate.withLock {
                        if (!current(id)) false
                        else if (loaded != null) {
                            mutableState.value = installed()
                            false
                        } else {
                            val prefs = requireNotNull(snapshots.value)
                            starting = prefs[LyricsRomanizeJapaneseKey]
                            !automatic || (starting != false && prefs[removedKey] != true)
                        }
                    }
                    if (!proceed) {
                        gate.withLock { if (current(id) && loaded == null) mutableState.value = JapaneseLanguagePackState.NotInstalled }
                        return@withLock
                    }
                    io.prepare()
                    coroutineScope {
                        val progress = Channel<Pair<Long, Long>>(Channel.CONFLATED)
                        val observer = launch {
                            for ((done, total) in progress) {
                                require(done in 0..KuromojiJapaneseTokenizerFactory.ARCHIVE_SIZE_BYTES.toLong())
                                require(total < 0 || total == KuromojiJapaneseTokenizerFactory.ARCHIVE_SIZE_BYTES.toLong())
                                gate.withLock {
                                    if (current(id)) mutableState.value = JapaneseLanguagePackState.Downloading(japanesePackProgress(done, total))
                                }
                            }
                        }
                        try {
                            withTimeout(180_000) { io.download { done, total -> progress.trySend(done to total) } }
                        } finally {
                            progress.close()
                            observer.join()
                        }
                    }
                    gate.withLock { if (current(id)) mutableState.value = JapaneseLanguagePackState.Downloading() }
                    val ready = requireNotNull(openJapanesePack(io.readStaged(), io::inflateRaw))
                    currentCoroutineContext().ensureActive()
                    gate.withLock { check(current(id)) }
                    // Account for a completed atomic rename even if cancellation arrives on dispatcher return.
                    withContext(NonCancellable) {
                        io.promote()
                        promoted = true
                    }
                    gate.withLock {
                        if (current(id)) {
                            editSettings {
                                if (!automatic) this[removedKey] = false
                                if (japanesePackShouldEnable(automatic, starting, this[LyricsRomanizeJapaneseKey], this[removedKey] == true)) {
                                    this[LyricsRomanizeJapaneseKey] = true
                                }
                            }
                            currentCoroutineContext().ensureActive()
                            loaded = ready
                            publishTokenizer()
                            published = true
                            mutableState.value = installed()
                        }
                    }
                } catch (error: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    gate.withLock { if (current(id)) mutableState.value = JapaneseLanguagePackState.Failed(JapanesePackFailure.INSTALL) }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    gate.withLock { if (current(id)) mutableState.value = JapaneseLanguagePackState.Failed(JapanesePackFailure.INSTALL) }
                } finally {
                    withContext(NonCancellable) {
                        var cleanupFailed = false
                        try { io.discardStaged() } catch (_: Exception) { cleanupFailed = true }
                        if (promoted && !published) {
                            try { io.removeInstalled() } catch (_: Exception) { cleanupFailed = true }
                        }
                        gate.withLock {
                            if (current(id) && (mutableState.value is JapaneseLanguagePackState.Downloading || cleanupFailed)) {
                                mutableState.value = if (cleanupFailed) JapaneseLanguagePackState.Failed(JapanesePackFailure.INSTALL)
                                    else if (loaded == null) JapaneseLanguagePackState.NotInstalled else installed()
                            }
                        }
                    }
                }
            }
        }.also { operation = it; it.start() }
    }

    suspend fun cancel() = gate.withLock {
        if (mutableState.value is JapaneseLanguagePackState.Downloading) {
            ++generation
            operation?.cancel()
            mutableState.value = if (loaded == null) JapaneseLanguagePackState.NotInstalled else installed()
        }
    }

    suspend fun remove(): Job? = gate.withLock {
        if (closed || mutableState.value == JapaneseLanguagePackState.Removing) return@withLock null
        val previous = operation
        val id = ++generation
        loaded = null
        mutableTokenizer.value = null
        previous?.cancel()
        mutableState.value = JapaneseLanguagePackState.Removing
        worker.launch(start = CoroutineStart.LAZY) {
            try {
                gate.withLock {
                    if (current(id)) editSettings {
                        this[removedKey] = true
                        this[LyricsRomanizeJapaneseKey] = false
                    }
                }
                previous?.join()
                initialization.join()
                files.withLock { io.removeInstalled(); io.discardStaged() }
                gate.withLock { if (current(id)) mutableState.value = JapaneseLanguagePackState.NotInstalled }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                gate.withLock { if (current(id)) mutableState.value = JapaneseLanguagePackState.Failed(JapanesePackFailure.REMOVE, true) }
            }
        }.also { operation = it; it.start() }
    }

    suspend fun close() {
        gate.withLock {
            if (!closed) {
                closed = true
                ++generation
                loaded = null
                mutableTokenizer.value = null
                worker.cancel()
            }
        }
        worker.coroutineContext[Job]?.join()
    }

    private fun current(id: Long): Boolean = !closed && id == generation
    private fun publishTokenizer() {
        mutableTokenizer.value = if (snapshots.value == null || snapshots.value?.get(LyricsRomanizeJapaneseKey) == false) null else loaded
    }
    private fun installed() = JapaneseLanguagePackState.Installed(KuromojiJapaneseTokenizerFactory.ARCHIVE_SIZE_BYTES.toLong())
}
