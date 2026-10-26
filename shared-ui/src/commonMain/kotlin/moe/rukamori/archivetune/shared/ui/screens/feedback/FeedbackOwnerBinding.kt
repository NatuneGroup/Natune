package moe.rukamori.archivetune.shared.ui.screens.feedback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.data.feedback.FeedbackReviewOwner
import moe.rukamori.archivetune.shared.domain.feedback.FeedbackPreview
import moe.rukamori.archivetune.shared.domain.feedback.FeedbackReport
import moe.rukamori.archivetune.shared.domain.feedback.FeedbackReviewState
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.screens.FeedbackReviewActions
import moe.rukamori.archivetune.shared.ui.screens.FeedbackReviewDialog
import moe.rukamori.archivetune.shared.ui.screens.FeedbackReviewStatus
import moe.rukamori.archivetune.shared.ui.screens.FeedbackReviewUi
import org.jetbrains.compose.resources.stringResource

/**
 * Presents the real [FeedbackReviewOwner] to the review dialog. The owner builds the report, holds the note and is the
 * only place that transmits. This binding forwards each action to it and reports an acknowledged send to [onSent],
 * once, from the owner's sent flow. It holds no transport, endpoint, key, diagnostics source or redactor: the host
 * builds the owner with those, and the text shown here is the owner's draft, which is the text Send posts.
 *
 * Create one binding per owner and use it only on the UI dispatcher that [scope] and the owner share. [scope] is the
 * route or app scope that holds the review surface; the binding's two listeners are children of it.
 *
 * Nothing transmits on open, dismiss, close or recomposition. Only the user's Send reaches [FeedbackReviewOwner.send].
 * Closing has one authority: the dialog is drawn while [state] holds a review, and only the owner changes that review
 * (Later, back, a delivered send, or a replacement) or [close] does. The host must not keep a second visibility flag.
 */
class FeedbackReviewOwnerBinding(
    private val owner: FeedbackReviewOwner,
    scope: CoroutineScope,
    private val onSent: () -> Unit,
) {
    private val job: Job = SupervisorJob(scope.coroutineContext[Job])
    private val work = CoroutineScope(scope.coroutineContext + job)
    private val visible = MutableStateFlow<FeedbackReviewState?>(owner.state.value)
    private var closed = false

    /** The review to draw: the owner's state while open, and null from [close] onward. */
    val state: StateFlow<FeedbackReviewState?> = visible.asStateFlow()

    /** The dialog actions. Each one reaches the owner only while this binding is open. */
    val actions: FeedbackReviewActions =
        FeedbackReviewActions(
            dismiss = { whileOpen { owner.dismiss() } },
            noteChange = { note -> whileOpen { owner.noteChange(note) } },
            send = { note -> whileOpen { owner.send(note) } },
        )

    init {
        // Both listeners attach before any caller can act. The owner's sent flow has no replay, so a send that is
        // acknowledged before its listener attaches would never reach onSent.
        work.launch(start = CoroutineStart.UNDISPATCHED) {
            owner.state.collect { visible.value = if (closed) null else it }
        }
        work.launch(start = CoroutineStart.UNDISPATCHED) {
            owner.sent.collect { if (!closed) onSent() }
        }
    }

    /** Opens a playback-error review in the owner. Ignored when [canOpen] is false. */
    fun openPlayback(errorDetails: String) {
        if (canOpen()) owner.openPlayback(errorDetails)
    }

    /** Opens a crash review from the stored capture, under the same rules as [openPlayback]. */
    fun openCrash(storedReport: String?) {
        if (canOpen()) owner.openCrash(storedReport)
    }

    /**
     * Ends this binding. The owner dismisses a review that is not sending, which never transmits. A send that is
     * already running finishes inside the owner, and its acknowledgement is dropped because nothing listens any more.
     * The owner and its transport are not cancelled here. Safe to call more than once.
     */
    fun close() {
        if (closed) return
        closed = true
        visible.value = null
        job.cancel()
        owner.dismiss()
    }

    /**
     * Opening replaces a review that is not sending, as the owner does. A running send is never replaced, because a
     * new draft would drop that send's acknowledgement.
     */
    private fun canOpen(): Boolean = !closed && owner.available && owner.state.value?.sending != true

    private fun whileOpen(action: () -> Unit) {
        if (!closed) action()
    }
}

/**
 * Draws the owner's review while the binding is open and the owner holds one. [reducedMotion] is the host's
 * accessibility preference. It is required, so a host cannot leave it out by accident.
 */
@Composable
fun BoundFeedbackReviewDialog(
    binding: FeedbackReviewOwnerBinding,
    reducedMotion: Boolean,
) {
    val review by binding.state.collectAsState()
    val current = review ?: return
    val title = stringResource(Res.string.m4_feedback_review_title)
    FeedbackReviewDialog(
        state = current.toUi(title = title, reducedMotion = reducedMotion),
        actions = binding.actions,
    )
}

private fun FeedbackReviewState.toUi(
    title: String,
    reducedMotion: Boolean,
): FeedbackReviewUi =
    FeedbackReviewUi(
        title = title,
        status =
            when (val current = preview) {
                FeedbackPreview.Preparing -> FeedbackReviewStatus.Preparing
                FeedbackPreview.Failed -> FeedbackReviewStatus.Failed
                is FeedbackPreview.Ready -> FeedbackReviewStatus.Ready(current.report)
            },
        note = note,
        noteLimit = FeedbackReport.MAX_NOTE_CHARS,
        sending = sending,
        sendError = sendError,
        reducedMotion = reducedMotion,
    )
