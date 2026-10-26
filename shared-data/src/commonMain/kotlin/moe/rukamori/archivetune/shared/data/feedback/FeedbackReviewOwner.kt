package moe.rukamori.archivetune.shared.data.feedback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.feedback.FeedbackDiagnostics
import moe.rukamori.archivetune.shared.domain.feedback.FeedbackDraft
import moe.rukamori.archivetune.shared.domain.feedback.FeedbackDrafts
import moe.rukamori.archivetune.shared.domain.feedback.FeedbackPreview
import moe.rukamori.archivetune.shared.domain.feedback.FeedbackReport
import moe.rukamori.archivetune.shared.domain.feedback.FeedbackReviewState
import moe.rukamori.archivetune.shared.domain.feedback.FeedbackTransport

/**
 * The single review-before-send owner for feedback. The draft on screen is the draft Send posts: it is built once
 * when the review opens, and Send transmits that exact report with the typed note. Only [send] transmits. [dismiss]
 * never does, and a failed send keeps the draft open for another attempt.
 *
 * Mutations run on the dispatcher of [scope]; the host passes its UI dispatcher so the draft and generation fields
 * are never touched from two threads at once.
 */
class FeedbackReviewOwner(
    private val diagnostics: FeedbackDiagnostics,
    private val transport: FeedbackTransport,
    private val scope: CoroutineScope,
    private val app: FeedbackReport.AppIdentity,
    private val device: FeedbackReport.DeviceIdentity,
    private val forkDirty: Boolean,
    /** Wall-clock timestamp for the envelope, supplied by the host so the owner stays platform-free. */
    private val sentAt: () -> String,
) {
    private val published = MutableStateFlow<FeedbackReviewState?>(null)
    val state: StateFlow<FeedbackReviewState?> = published.asStateFlow()

    private val deliveries = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits once after a report is delivered. The review has already closed by then. */
    val sent: SharedFlow<Unit> = deliveries.asSharedFlow()

    /** Whether the host has a feedback endpoint and key. When false the review must not be offered. */
    val available: Boolean get() = transport.available

    private var draft: FeedbackDraft? = null
    private var generation = 0L
    private var building: Job? = null

    /** Opens a playback-error review. The diagnostic log is read off the caller and redacted with the draft. */
    fun openPlayback(errorDetails: String) {
        open { FeedbackDrafts.playback(app, device, forkDirty, errorDetails, readDiagnostics()) }
    }

    /** Opens a crash review from the stored capture. A missing capture shows the build as failed. */
    fun openCrash(storedReport: String?) {
        open { storedReport?.let { FeedbackDrafts.crash(it, readDiagnostics()) } }
    }

    fun noteChange(note: String) {
        val bounded = note.take(FeedbackReport.MAX_NOTE_CHARS)
        published.update { current ->
            if (current == null || current.sending) current else current.copy(note = bounded)
        }
    }

    /** Sends the reviewed draft with [note]. Ignored unless the draft is ready and no send is already running. */
    fun send(note: String) {
        val current = published.value ?: return
        val ready = draft ?: return
        if (current.sending || current.preview !is FeedbackPreview.Ready) return
        val bounded = note.take(FeedbackReport.MAX_NOTE_CHARS)
        val token = generation
        published.value = current.copy(note = bounded, sending = true, sendError = null)
        scope.launch {
            val envelope = FeedbackDrafts.envelope(ready, bounded, sentAt(), app, device)
            val result = transport.send(envelope)
            if (token != generation) return@launch
            result
                .onSuccess {
                    draft = null
                    generation += 1
                    published.value = null
                    deliveries.tryEmit(Unit)
                }.onFailure { failure ->
                    published.update { it?.copy(sending = false, sendError = failure.message ?: REPORT_NOT_DELIVERED) }
                }
        }
    }

    /** Closes the review without sending. Ignored while a send is running, as the dialog cannot close then. */
    fun dismiss() {
        val current = published.value ?: return
        if (current.sending) return
        generation += 1
        draft = null
        building?.cancel()
        published.value = null
    }

    private fun open(build: suspend () -> FeedbackDraft?) {
        generation += 1
        val token = generation
        draft = null
        building?.cancel()
        published.value =
            FeedbackReviewState(preview = FeedbackPreview.Preparing, note = "", sending = false, sendError = null)
        building =
            scope.launch {
                val built =
                    try {
                        build()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        null
                    }
                if (token != generation) return@launch
                draft = built
                published.update { current ->
                    current?.copy(preview = built?.let { FeedbackPreview.Ready(it.report) } ?: FeedbackPreview.Failed)
                }
            }
    }

    /** A failed read becomes the "(unavailable)" section; the rest of the report still builds. */
    private suspend fun readDiagnostics(): String? =
        try {
            diagnostics.unredactedReport()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unreadable: Exception) {
            null
        }

    private companion object {
        const val REPORT_NOT_DELIVERED = "Report was not delivered"
    }
}
