package moe.rukamori.archivetune.shared.domain.dsp.acoustic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * The acoustic audit state machine, shared by every host. A run decodes a bounded PCM window through the
 * host's probe — [decode] receives the target and a liveness check, and returns an [AcousticWindow] —
 * then analyzes it and publishes the result. Publishing is generation-gated, so a cancelled or superseded
 * run never overwrites newer state, and a completed result stays visible until the next start.
 *
 * The owner never touches playback: the probe captures the source the target maps to at run start, so a
 * mid-decode track change cannot corrupt the result. [setActive] false cancels a run in flight, the same
 * way leaving the settings page does on Android.
 */
class AcousticAuditOwner(
    private val scope: CoroutineScope,
    private val decode: suspend (target: AcousticAuditTarget, checkActive: () -> Unit) -> AcousticWindow,
) {
    private val lock = Any()
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

    /** Closes the mounted surface; a completed result keeps its state. */
    fun closeDialog() {
        mutableDialogTarget.value = null
    }

    /** Starts one bounded run. A run already in flight blocks another start. */
    fun start(target: AcousticAuditTarget) {
        val token: Long
        synchronized(lock) {
            if (job?.isActive == true) return
            token = ++generation
            publish(token, AcousticAuditState(AcousticAuditStatus.RESOLVING, 0f))
            job = scope.launch {
                try {
                    val window = withTimeout(AcousticAuditLimits.DEADLINE_MS) {
                        decode(target) {
                            if (generation != token || job?.isActive != true) throw CancellationException()
                        }
                    }
                    when (window) {
                        is AcousticWindow.Refused ->
                            publish(token, AcousticAuditState(AcousticAuditStatus.UNAVAILABLE, message = window.code))
                        is AcousticWindow.Decoded -> {
                            publish(token, AcousticAuditState(AcousticAuditStatus.ANALYZING, 0f))
                            val result = AcousticSpectrogramAnalyzer.analyze(
                                target.mediaId, window.sourceLabel, window.advertisedSampleRate,
                                window.claimedLossless, window.limitations, window.pcm,
                                checkActive = {
                                    if (generation != token || job?.isActive != true) throw CancellationException()
                                },
                            ) { progress ->
                                publish(token, AcousticAuditState(AcousticAuditStatus.ANALYZING, progress))
                            }
                            publish(token, AcousticAuditState(AcousticAuditStatus.COMPLETE, 1f, result))
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
        }
    }

    fun cancel() {
        synchronized(lock) {
            ++generation
            job?.cancel()
            job = null
            mutableState.value = AcousticAuditState(AcousticAuditStatus.CANCELLED)
        }
    }

    /** A settings surface mounts active and unmounts inactive; an inactive owner cancels a run in flight. */
    fun setActive(value: Boolean) {
        if (!value) cancel()
    }

    private fun publish(token: Long, value: AcousticAuditState) {
        synchronized(lock) {
            if (token == generation) mutableState.value = value
        }
    }

    fun close() {
        cancel()
        mutableDialogTarget.value = null
    }
}
