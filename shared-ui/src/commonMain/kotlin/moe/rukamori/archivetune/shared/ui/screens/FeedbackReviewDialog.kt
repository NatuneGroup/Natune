package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.glass.BusyCircularProgress
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Owner-prepared review state. Only [Ready] carries text, and that text is exactly what Send transmits.
 */
sealed interface FeedbackReviewStatus {
    /** The owner is still assembling the report off the UI thread. */
    data object Preparing : FeedbackReviewStatus
    /** The report could not be assembled; Send stays disabled. */
    data object Failed : FeedbackReviewStatus
    data class Ready(val report: String) : FeedbackReviewStatus
}

/**
 * [noteLimit] is the owner's bound in characters for the optional note; the owner still enforces the byte
 * size of the envelope. [sendError] is the owner's failure detail from the last attempt, or null.
 * [reducedMotion] is the host's accessibility preference; it removes the status transitions.
 */
data class FeedbackReviewUi(
    val title: String,
    val status: FeedbackReviewStatus,
    val note: String,
    val noteLimit: Int,
    val sending: Boolean,
    val sendError: String?,
    val reducedMotion: Boolean,
)

/**
 * [dismiss] runs for Later, the scrim and system back, and never transmits. [noteChange] receives the note
 * already bounded to [FeedbackReviewUi.noteLimit]. [send] carries the note the user reviewed. The owner builds
 * the envelope from that same reviewed draft and, after a delivered report, closes the dialog through its own
 * completion callback; a failed send keeps the draft on screen with [FeedbackReviewUi.sendError].
 */
data class FeedbackReviewActions(
    val dismiss: () -> Unit,
    val noteChange: (String) -> Unit,
    val send: (String) -> Unit,
)

/**
 * The single review-before-send surface for every feedback report. The preview is shown verbatim so the user
 * reads exactly the text that Send posts. Closing is never a send, and dismissal is blocked while a send runs.
 */
@Composable
fun FeedbackReviewDialog(state: FeedbackReviewUi, actions: FeedbackReviewActions) {
    AlertDialog(
        onDismissRequest = { if (!state.sending) actions.dismiss() },
        modifier = Modifier.testTag("feedback-review-dialog"),
        title = { Text(state.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.Start) {
                AnimatedContent(
                    targetState = state.status,
                    transitionSpec = {
                        if (state.reducedMotion) {
                            EnterTransition.None togetherWith ExitTransition.None
                        } else {
                            (fadeIn(tween(180)) togetherWith fadeOut(tween(120))).using(SizeTransform(clip = false))
                        }
                    },
                    label = "feedbackReviewStatus",
                ) { current ->
                    when (current) {
                        FeedbackReviewStatus.Failed -> Text(
                            text = stringResource(Res.string.m4_feedback_building_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        FeedbackReviewStatus.Preparing -> Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BusyCircularProgress(modifier = Modifier.size(16.dp))
                            Text(
                                text = stringResource(Res.string.m4_feedback_building),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        is FeedbackReviewStatus.Ready -> FeedbackReportPreview(
                            report = current.report,
                            note = state.note,
                            noteLimit = state.noteLimit,
                            noteChange = actions.noteChange,
                        )
                    }
                }
                state.sendError?.let { message ->
                    Text(
                        text = stringResource(Res.string.m4_feedback_send_failed, message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !state.sending, onClick = actions.dismiss) {
                Text(stringResource(Res.string.m4_feedback_later))
            }
        },
        confirmButton = {
            if (state.sending) {
                BusyCircularProgress(modifier = Modifier.size(24.dp))
            } else {
                Button(
                    enabled = state.status is FeedbackReviewStatus.Ready,
                    onClick = { actions.send(state.note) },
                    modifier = Modifier.testTag("feedback-review-send"),
                ) {
                    Text(stringResource(Res.string.m4_feedback_send))
                }
            }
        },
    )
}

@Composable
private fun FeedbackReportPreview(
    report: String,
    note: String,
    noteLimit: Int,
    noteChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            SelectionContainer(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                Text(text = report, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
        OutlinedTextField(
            value = note,
            onValueChange = { noteChange(it.take(noteLimit)) },
            singleLine = true,
            placeholder = { Text(stringResource(Res.string.m4_feedback_note_hint)) },
            supportingText = { Text(stringResource(Res.string.m4_feedback_note_limit, noteLimit)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
