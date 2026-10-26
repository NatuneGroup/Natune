package moe.rukamori.archivetune.shared.data.recognition

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.shared.domain.recognition.MusicRecognitionFailure
import moe.rukamori.archivetune.shared.domain.recognition.RecognitionBackgroundSettingPort
import moe.rukamori.archivetune.shared.domain.recognition.RecognitionCapturePort
import moe.rukamori.archivetune.shared.domain.recognition.RecognitionClock
import moe.rukamori.archivetune.shared.domain.recognition.RecognitionDiagnostics
import moe.rukamori.archivetune.shared.domain.recognition.RecognitionHistoryEntry
import moe.rukamori.archivetune.shared.domain.recognition.RecognitionHistoryLoad
import moe.rukamori.archivetune.shared.domain.recognition.RecognitionHistoryPort
import moe.rukamori.archivetune.shared.domain.recognition.RecognitionMatchOutcome
import moe.rukamori.archivetune.shared.domain.recognition.RecognitionMatchPort
import moe.rukamori.archivetune.shared.domain.recognition.RecognitionPermissionPort
import moe.rukamori.archivetune.shared.domain.recognition.RecognitionPhase
import moe.rukamori.archivetune.shared.domain.recognition.RecognizedTrack
import moe.rukamori.archivetune.shared.domain.recognition.filterRecognitionHistory
import moe.rukamori.archivetune.shared.domain.recognition.toHistoryEntry
import moe.rukamori.archivetune.shared.domain.recognition.toRecognizedTrack

/**
 * What the recognition surface may show. [Recognized] is the one-shot hand-off to details: the host opens details
 * once for it, and [MusicRecognitionOwner.onDetailsOpened] moves the status back to [Idle].
 */
sealed interface RecognitionStatus {
    data object Idle : RecognitionStatus

    data class Running(val phase: RecognitionPhase) : RecognitionStatus

    data class Recognized(val track: RecognizedTrack) : RecognitionStatus

    data class Failed(val failure: MusicRecognitionFailure) : RecognitionStatus
}

data class MusicRecognitionOwnerState(
    val backgroundRecognitionAvailable: Boolean,
    val status: RecognitionStatus = RecognitionStatus.Idle,
    /** Every saved recognition, newest first. */
    val history: List<RecognitionHistoryEntry> = emptyList(),
    /** [history] under [historyQuery], using the shared matching policy. */
    val filteredHistory: List<RecognitionHistoryEntry> = emptyList(),
    val historyVisible: Boolean = false,
    val historyQuery: String = "",
    val historyLoadFailed: Boolean = false,
    val historySaveFailed: Boolean = false,
    val settingsVisible: Boolean = false,
    val backgroundRecognitionEnabled: Boolean = false,
    val backgroundRecognitionSaveFailed: Boolean = false,
    /** The last match in this owner. Kept after the status moves on so its details still resolve. */
    val lastRecognized: RecognizedTrack? = null,
) {
    /** True when the saved history could not be read or the latest save failed. The surface says so, not an empty list. */
    val historyUnavailable: Boolean
        get() = historyLoadFailed || historySaveFailed

    /**
     * The details for a saved identity. Returns the last match when its identity matches, else the saved entry, and
     * null when neither holds [stableKey]. No positional identity is involved.
     */
    fun detailsFor(stableKey: String): RecognizedTrack? {
        val recognized = lastRecognized
        if (recognized != null && recognized.stableKey == stableKey) return recognized
        return history.firstOrNull { it.stableKey == stableKey }?.toRecognizedTrack()
    }
}

/**
 * Owns one recognition surface: a single active request, cancellation, stale-result rejection, the saved history
 * and the background setting. It never reads the microphone, fingerprints audio or stores history itself; those
 * are injected ports.
 *
 * Create one owner per recognition route on the UI dispatcher, and call [close] when the route leaves. [close] cancels
 * only this owner's request and listeners. The injected ports are borrowed and are never closed here.
 */
class MusicRecognitionOwner(
    scope: CoroutineScope,
    private val capture: RecognitionCapturePort,
    private val matcher: RecognitionMatchPort,
    private val permission: RecognitionPermissionPort,
    private val historyStore: RecognitionHistoryPort,
    private val backgroundSettings: RecognitionBackgroundSettingPort,
    private val clock: RecognitionClock,
    private val diagnostics: RecognitionDiagnostics,
    backgroundRecognitionAvailable: Boolean,
) {
    private val ownerJob: Job = SupervisorJob(scope.coroutineContext[Job])
    private val work = CoroutineScope(scope.coroutineContext + ownerJob)
    private val settingsMutex = Mutex()
    private val mutableState =
        MutableStateFlow(MusicRecognitionOwnerState(backgroundRecognitionAvailable = backgroundRecognitionAvailable))

    val state: StateFlow<MusicRecognitionOwnerState> = mutableState.asStateFlow()

    private var closed = false
    private var requestSerial = 0L
    private var activeRequest = NO_REQUEST
    private var requestJob: Job? = null
    private var settingSerial = 0L
    private var persistedBackgroundEnabled = false

    init {
        work.launch {
            historyStore.observe().collect { load -> applyHistoryLoad(load) }
        }
        work.launch {
            backgroundSettings.observeEnabled().collect { enabled -> applyPersistedBackground(enabled) }
        }
    }

    /**
     * Starts one listen. Ignored while a request is active, including while the microphone prompt is open, and after
     * [close]. Capture starts only after the permission outcome is granted.
     */
    fun listen() {
        if (closed || requestJob?.isActive == true) return
        val request = ++requestSerial
        activeRequest = request
        requestJob = work.launch { runRequest(request) }
    }

    /** Cancels the active request. Its late results are rejected, and a running status returns to idle. */
    fun cancel() {
        val job = requestJob
        requestJob = null
        activeRequest = NO_REQUEST
        job?.cancel()
        mutableState.update { current ->
            if (current.status is RecognitionStatus.Running) {
                current.copy(status = RecognitionStatus.Idle)
            } else {
                current
            }
        }
    }

    /** Hiding the history clears its query, so the next open starts unfiltered. */
    fun setHistoryVisible(visible: Boolean) {
        if (closed) return
        mutableState.update { current ->
            if (visible) {
                current.copy(historyVisible = true)
            } else {
                current.copy(historyVisible = false, historyQuery = "", filteredHistory = current.history)
            }
        }
    }

    fun setHistoryQuery(query: String) {
        if (closed) return
        mutableState.update { current ->
            current.copy(
                historyQuery = query,
                filteredHistory = filterRecognitionHistory(current.history, query),
            )
        }
    }

    fun setSettingsVisible(visible: Boolean) {
        if (closed) return
        mutableState.update { current -> current.copy(settingsVisible = visible) }
    }

    /**
     * Changes the background setting. Ignored when the native capability is unavailable. The value shows at once, and
     * a failed save restores the last persisted value and reports the failure in state. Saves run in order.
     */
    fun setBackgroundRecognitionEnabled(enabled: Boolean) {
        if (closed || !mutableState.value.backgroundRecognitionAvailable) return
        val serial = ++settingSerial
        mutableState.update { current -> current.copy(backgroundRecognitionEnabled = enabled) }
        work.launch {
            settingsMutex.withLock {
                try {
                    backgroundSettings.setEnabled(enabled)
                    if (serial == settingSerial) {
                        mutableState.update { current -> current.copy(backgroundRecognitionSaveFailed = false) }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    diagnostics.report("Failed to update background recognition setting", throwable)
                    if (serial == settingSerial) {
                        mutableState.update { current ->
                            current.copy(
                                backgroundRecognitionEnabled = persistedBackgroundEnabled,
                                backgroundRecognitionSaveFailed = true,
                            )
                        }
                    }
                }
            }
        }
    }

    /** Resolves the details for a saved identity. Null when the owner holds no result for [stableKey]. */
    fun detailsFor(stableKey: String): RecognizedTrack? = mutableState.value.detailsFor(stableKey)

    /**
     * Called when the host opens details for a [RecognitionStatus.Recognized] result. Moves the status off Recognized, so
     * re-entering the route cannot admit the same result again.
     */
    fun onDetailsOpened(stableKey: String) {
        if (closed) return
        mutableState.update { current ->
            val status = current.status
            if (status is RecognitionStatus.Recognized && status.track.stableKey == stableKey) {
                current.copy(status = RecognitionStatus.Idle)
            } else {
                current
            }
        }
    }

    /** Ends this owner. Cancels its own request and listeners and settles the status. Safe to call more than once. */
    fun close() {
        if (closed) return
        closed = true
        requestJob = null
        activeRequest = NO_REQUEST
        ownerJob.cancel()
        mutableState.update { current ->
            current.copy(
                status = RecognitionStatus.Idle,
                historyVisible = false,
                historyQuery = "",
                filteredHistory = current.history,
                settingsVisible = false,
            )
        }
    }

    private suspend fun runRequest(request: Long) {
        val granted =
            try {
                permission.requestMicrophonePermission()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                failRequest(request, MusicRecognitionFailure.RecordingFailed, throwable)
                return
            }
        if (!granted) {
            failRequest(request, MusicRecognitionFailure.PermissionRequired, cause = null)
            return
        }

        setRunning(request, RecognitionPhase.Listening)
        val samples =
            try {
                capture.captureSamples()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                failRequest(request, MusicRecognitionFailure.RecordingFailed, throwable)
                return
            }
        if (samples.isEmpty()) {
            failRequest(request, MusicRecognitionFailure.RecordingFailed, cause = null)
            return
        }

        setRunning(request, RecognitionPhase.Processing)
        val outcome =
            try {
                matcher.match(samples)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                failRequest(request, MusicRecognitionFailure.RecognitionFailed, throwable)
                return
            }
        when (outcome) {
            is RecognitionMatchOutcome.Matched -> completeMatch(request, outcome.track)
            RecognitionMatchOutcome.NoMatch -> failRequest(request, MusicRecognitionFailure.NoMatch, cause = null)
            RecognitionMatchOutcome.SignatureUnavailable ->
                failRequest(request, MusicRecognitionFailure.SignatureFailed, cause = null)
        }
    }

    /**
     * Saves the match, then publishes it only when this request is still current. A failed save still shows the match,
     * because the baseline did, but the saved history is reported as unavailable instead of hidden.
     */
    private suspend fun completeMatch(
        request: Long,
        track: RecognizedTrack,
    ) {
        val entry = track.toHistoryEntry(clock.nowEpochMillis())
        val saveFailure: Throwable? =
            try {
                historyStore.edit { current -> mergeRecognitionHistory(current, entry) }
                null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                throwable
            }
        if (saveFailure != null) {
            diagnostics.report("Failed to persist music recognition history", saveFailure)
        }
        if (!isCurrent(request)) return
        requestJob = null
        mutableState.update { current ->
            current.copy(
                status = RecognitionStatus.Recognized(track),
                lastRecognized = track,
                historySaveFailed = saveFailure != null,
            )
        }
    }

    private fun setRunning(
        request: Long,
        phase: RecognitionPhase,
    ) {
        if (!isCurrent(request)) return
        mutableState.update { current -> current.copy(status = RecognitionStatus.Running(phase)) }
    }

    private fun failRequest(
        request: Long,
        failure: MusicRecognitionFailure,
        cause: Throwable?,
    ) {
        if (cause != null) diagnostics.report("Music recognition failed: ${failure.name}", cause)
        if (!isCurrent(request)) return
        requestJob = null
        mutableState.update { current -> current.copy(status = RecognitionStatus.Failed(failure)) }
    }

    private fun applyHistoryLoad(load: RecognitionHistoryLoad) {
        if (closed) return
        when (load) {
            is RecognitionHistoryLoad.Loaded -> {
                val entries = sortRecognitionHistoryNewestFirst(load.entries)
                mutableState.update { current ->
                    current.copy(
                        history = entries,
                        filteredHistory = filterRecognitionHistory(entries, current.historyQuery),
                        historyLoadFailed = false,
                    )
                }
            }

            is RecognitionHistoryLoad.Unavailable -> {
                diagnostics.report("Failed to load music recognition history", load.cause)
                mutableState.update { current -> current.copy(historyLoadFailed = true) }
            }
        }
    }

    private fun applyPersistedBackground(enabled: Boolean) {
        persistedBackgroundEnabled = mutableState.value.backgroundRecognitionAvailable && enabled
        if (closed) return
        mutableState.update { current -> current.copy(backgroundRecognitionEnabled = persistedBackgroundEnabled) }
    }

    private fun isCurrent(request: Long): Boolean = !closed && request == activeRequest

    private companion object {
        const val NO_REQUEST = 0L
    }
}
