package moe.rukamori.archivetune.shared.domain.dsp.acoustic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * The acoustic audit state machine, shared by every host. A run decodes a bounded PCM window through the
 * host's probe — [decode] receives the target and a liveness check, and returns an [AcousticWindow] —
 * then analyzes it and publishes the result. Publishing is generation-gated, so a cancelled or superseded
 * run never overwrites newer state, and a completed result stays visible until the next start.
 *
 * The owner never touches playback: the probe captures the source the target maps to at run start, so a
 * mid-decode track change cannot corrupt the result. [setActive] false and [closeDialog] both cancel a run
 * in flight, the same way leaving the settings page does on Android.
 *
 * All shared state moves under [lock]: the host scope is multi-threaded (Dispatchers.Default), so plain
 * reads and writes would race without it. The non-suspend entry points callers reach from Compose launch
 * onto [scope] and take the lock there, which preserves their call order; the liveness check a decode or
 * analyze step receives is bound to the launched job itself, which is thread-safe by construction.
 */
class AcousticAuditOwner(
    private val scope: CoroutineScope,
    private val decode: suspend (target: AcousticAuditTarget, checkActive: () -> Unit) -> AcousticWindow,
) {
    private val lock = Mutex()
    private val mutableState = MutableStateFlow(AcousticAuditState())

    /** The current run's published state: status, decode/analysis progress, and the result or a reason code. */
    val state: StateFlow<AcousticAuditState> = mutableState.asStateFlow()

    /** The target an audit surface (dialog or sheet) is open for, or null while none is mounted. */
    private val mutableDialogTarget = MutableStateFlow<AcousticAuditTarget?>(null)
    val dialogTarget: StateFlow<AcousticAuditTarget?> = mutableDialogTarget.asStateFlow()

    private var generation = 0L
    private var job: Job? = null

    /** Opens a host audit surface for [target]; the surface calls [start] itself when the user asks to run. */
    fun openDialog(target: AcousticAuditTarget) {
        mutableDialogTarget.value = target
    }

    /** Closes the mounted surface and cancels a run in flight: a dismissed audit never keeps decoding. */
    fun closeDialog() {
        mutableDialogTarget.value = null
        cancel()
    }

    /** Starts one bounded run. A run already in flight blocks another start. */
    fun start(target: AcousticAuditTarget) {
        scope.launch {
            lock.withLock {
                if (job?.isActive == true) return@withLock
                val token = ++generation
                // The job is assigned before it starts, so a liveness check that fires first can never
                // run against a null handle; the LAZY start orders assignment before the body runs.
                val launched = scope.launch(start = CoroutineStart.LAZY) { runAudit(token, target) }
                job = launched
                mutableState.value = AcousticAuditState(AcousticAuditStatus.RESOLVING, 0f)
                launched.start()
            }
        }
    }

    private suspend fun runAudit(token: Long, target: AcousticAuditTarget) {
        // The launched job is the liveness token the non-suspend check shares: Job.isActive is atomic,
        // so the check stays true without touching locked state from an arbitrary thread.
        val self = currentCoroutineContext().job
        fun checkActive() {
            if (!self.isActive) throw CancellationException()
        }
        try {
            val window = withTimeout(AcousticAuditLimits.DEADLINE_MS) { decode(target, ::checkActive) }
            when (window) {
                is AcousticWindow.Refused ->
                    publish(token, AcousticAuditState(AcousticAuditStatus.UNAVAILABLE, message = window.code))
                is AcousticWindow.Decoded -> {
                    if (target.requestKey.isNotEmpty() && window.requestKey != target.requestKey) {
                        // The request the target named is not the request the probe decoded.
                        publish(token, AcousticAuditState(AcousticAuditStatus.UNAVAILABLE, message = "current_source_unavailable"))
                        return
                    }
                    publish(token, AcousticAuditState(AcousticAuditStatus.ANALYZING, 0f))
                    val result = AcousticSpectrogramAnalyzer.analyze(
                        target.mediaId, window.sourceLabel, window.advertisedSampleRate,
                        window.claimedLossless, window.limitations, window.pcm,
                        checkActive = ::checkActive,
                    ) { progress ->
                        publishNow(token, AcousticAuditState(AcousticAuditStatus.ANALYZING, progress))
                    }
                    publish(token, AcousticAuditState(AcousticAuditStatus.COMPLETE, 1f, result.copy(requestKey = window.requestKey)))
                }
            }
        } catch (_: TimeoutCancellationException) {
            publish(token, AcousticAuditState(AcousticAuditStatus.UNAVAILABLE, message = "deadline_exceeded"))
        } catch (_: CancellationException) {
            publish(token, AcousticAuditState(AcousticAuditStatus.CANCELLED))
        } catch (error: AcousticUnavailable) {
            publish(token, AcousticAuditState(AcousticAuditStatus.UNAVAILABLE, message = error.code))
        } catch (_: Exception) {
            // Probe and decoder messages can carry source URIs; only the code reaches state.
            publish(token, AcousticAuditState(AcousticAuditStatus.FAILED, message = "analysis_failed"))
        }
    }

    fun cancel() {
        scope.launch {
            lock.withLock {
                ++generation
                job?.cancel()
                job = null
                mutableState.value = AcousticAuditState(AcousticAuditStatus.CANCELLED)
            }
        }
    }

    /** A settings surface mounts active and unmounts inactive; an inactive owner cancels a run in flight. */
    fun setActive(value: Boolean) {
        if (!value) cancel()
    }

    /** Suspend publish for status transitions inside a run; a superseded token never lands. */
    private suspend fun publish(token: Long, value: AcousticAuditState) =
        lock.withLock {
            if (token == generation) mutableState.value = value
        }

    /**
     * Non-suspend publish for progress ticks from inside the analyzer. A tick that cannot take the lock —
     * because a cancel is being applied — is a tick the run was never going to publish anyway, so it drops.
     */
    private fun publishNow(token: Long, value: AcousticAuditState) {
        if (lock.tryLock()) {
            try {
                if (token == generation) mutableState.value = value
            } finally {
                lock.unlock()
            }
        }
    }

    fun close() {
        cancel()
        mutableDialogTarget.value = null
    }
}
