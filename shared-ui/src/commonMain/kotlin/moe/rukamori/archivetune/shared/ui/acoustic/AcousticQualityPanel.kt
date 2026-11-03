package moe.rukamori.archivetune.shared.ui.acoustic

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import moe.rukamori.archivetune.shared.domain.dsp.acoustic.AcousticAssessment
import moe.rukamori.archivetune.shared.domain.dsp.acoustic.AcousticAuditOwner
import moe.rukamori.archivetune.shared.domain.dsp.acoustic.AcousticAuditResult
import moe.rukamori.archivetune.shared.domain.dsp.acoustic.AcousticAuditStatus
import moe.rukamori.archivetune.shared.domain.dsp.acoustic.AcousticAuditTarget
import moe.rukamori.archivetune.shared.domain.dsp.acoustic.AcousticHeatAxis
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.m6aq_analyzing
import moe.rukamori.archivetune.shared.ui.resources.m6aq_cancel
import moe.rukamori.archivetune.shared.ui.resources.m6aq_cancelled
import moe.rukamori.archivetune.shared.ui.resources.m6aq_channels
import moe.rukamori.archivetune.shared.ui.resources.m6aq_close
import moe.rukamori.archivetune.shared.ui.resources.m6aq_code_analysis_busy
import moe.rukamori.archivetune.shared.ui.resources.m6aq_code_analysis_failed
import moe.rukamori.archivetune.shared.ui.resources.m6aq_code_codec_unknown
import moe.rukamori.archivetune.shared.ui.resources.m6aq_code_current_source_unavailable
import moe.rukamori.archivetune.shared.ui.resources.m6aq_code_deadline_exceeded
import moe.rukamori.archivetune.shared.ui.resources.m6aq_code_input_unreadable
import moe.rukamori.archivetune.shared.ui.resources.m6aq_code_insufficient_active_evidence
import moe.rukamori.archivetune.shared.ui.resources.m6aq_code_insufficient_pcm
import moe.rukamori.archivetune.shared.ui.resources.m6aq_code_no_current_source
import moe.rukamori.archivetune.shared.ui.resources.m6aq_code_no_safe_source
import moe.rukamori.archivetune.shared.ui.resources.m6aq_code_pcm_budget_shortened_excerpt
import moe.rukamori.archivetune.shared.ui.resources.m6aq_code_remote_unsupported
import moe.rukamori.archivetune.shared.ui.resources.m6aq_complete
import moe.rukamori.archivetune.shared.ui.resources.m6aq_cutoff
import moe.rukamori.archivetune.shared.ui.resources.m6aq_cutoff_unknown
import moe.rukamori.archivetune.shared.ui.resources.m6aq_decoding
import moe.rukamori.archivetune.shared.ui.resources.m6aq_details
import moe.rukamori.archivetune.shared.ui.resources.m6aq_explanation
import moe.rukamori.archivetune.shared.ui.resources.m6aq_failed
import moe.rukamori.archivetune.shared.ui.resources.m6aq_generic
import moe.rukamori.archivetune.shared.ui.resources.m6aq_idle
import moe.rukamori.archivetune.shared.ui.resources.m6aq_inconclusive
import moe.rukamori.archivetune.shared.ui.resources.m6aq_mono
import moe.rukamori.archivetune.shared.ui.resources.m6aq_no_cutoff
import moe.rukamori.archivetune.shared.ui.resources.m6aq_progress
import moe.rukamori.archivetune.shared.ui.resources.m6aq_read_axes
import moe.rukamori.archivetune.shared.ui.resources.m6aq_read_colour
import moe.rukamori.archivetune.shared.ui.resources.m6aq_read_cutoff
import moe.rukamori.archivetune.shared.ui.resources.m6aq_read_title
import moe.rukamori.archivetune.shared.ui.resources.m6aq_resolving
import moe.rukamori.archivetune.shared.ui.resources.m6aq_retry
import moe.rukamori.archivetune.shared.ui.resources.m6aq_run
import moe.rukamori.archivetune.shared.ui.resources.m6aq_source_local_file
import moe.rukamori.archivetune.shared.ui.resources.m6aq_source_remote_stream
import moe.rukamori.archivetune.shared.ui.resources.m6aq_stereo
import moe.rukamori.archivetune.shared.ui.resources.m6aq_suspected
import moe.rukamori.archivetune.shared.ui.resources.m6aq_title
import moe.rukamori.archivetune.shared.ui.resources.m6aq_unavailable
import org.jetbrains.compose.resources.stringResource

/**
 * The acoustic quality audit panel, shared by every host: the title and explanation, the live status,
 * the result with its heat graph once a run completes, and the one Run/Cancel control. [active] marks
 * the surface as mounted; a host that unmounts it or hides it passes false, which cancels a run in
 * flight, the same way leaving the Android settings page does.
 */
@Composable
fun AcousticQualityPanel(
    target: AcousticAuditTarget,
    owner: AcousticAuditOwner,
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    val state by owner.state.collectAsState()
    val running =
        state.status == AcousticAuditStatus.RESOLVING ||
            state.status == AcousticAuditStatus.DECODING ||
            state.status == AcousticAuditStatus.ANALYZING
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(target.title, style = MaterialTheme.typography.titleMedium)
        if (target.artists.isNotBlank())
            Text(target.artists, style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(Res.string.m6aq_explanation),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(
                when (state.status) {
                    AcousticAuditStatus.IDLE -> Res.string.m6aq_idle
                    AcousticAuditStatus.RESOLVING -> Res.string.m6aq_resolving
                    AcousticAuditStatus.DECODING -> Res.string.m6aq_decoding
                    AcousticAuditStatus.ANALYZING -> Res.string.m6aq_analyzing
                    AcousticAuditStatus.COMPLETE -> Res.string.m6aq_complete
                    AcousticAuditStatus.UNAVAILABLE -> Res.string.m6aq_unavailable
                    AcousticAuditStatus.FAILED -> Res.string.m6aq_failed
                    AcousticAuditStatus.CANCELLED -> Res.string.m6aq_cancelled
                }
            ),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (running) {
            state.progress
                ?.takeIf { it.isFinite() }
                ?.let {
                    Text(
                        stringResource(
                            Res.string.m6aq_progress,
                            (it.coerceIn(0f, 1f) * 100).roundToInt(),
                        )
                    )
                }
        }
        state.message?.let { Text(acousticCodeText(it)) }
        state.result
            ?.takeIf {
                active && it.mediaId == target.mediaId &&
                    (target.requestKey.isEmpty() || it.requestKey == target.requestKey)
            }
            ?.let { result ->
                AcousticQualityResult(result)
            }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (running) {
                TextButton(onClick = owner::cancel) {
                    Text(stringResource(Res.string.m6aq_cancel))
                }
            } else {
                Button(onClick = { owner.start(target) }, enabled = active) {
                    Text(
                        stringResource(
                            if (state.status == AcousticAuditStatus.IDLE) {
                                Res.string.m6aq_run
                            } else Res.string.m6aq_retry
                        )
                    )
                }
            }
        }
    }
}

/**
 * Notes that change how to read a result; the rest of [AcousticAuditResult.limitations] is fixed
 * method detail.
 */
private val ReaderNotes = setOf("insufficient_active_evidence", "pcm_budget_shortened_excerpt")

@Composable
private fun AcousticQualityResult(result: AcousticAuditResult) {
    val source = acousticCodeText(result.sourceLabel)
    val channels =
        when (result.channelCount) {
            1 -> stringResource(Res.string.m6aq_mono)
            2 -> stringResource(Res.string.m6aq_stereo)
            else -> stringResource(Res.string.m6aq_channels, result.channelCount)
        }
    val details =
        stringResource(
            Res.string.m6aq_details,
            AcousticHeatAxis.formatClock(result.startMs / 1000.0),
            AcousticHeatAxis.formatClock((result.startMs + result.durationMs) / 1000.0),
            result.sampleRate / 1000f,
            channels,
            source,
        )
    val topKHz = result.nyquistHz / 1000f
    val cutoff =
        result.cutoffHz
            ?.takeIf { it.isFinite() }
            ?.let {
                stringResource(Res.string.m6aq_cutoff, it / 1000f, topKHz)
            } ?: stringResource(Res.string.m6aq_cutoff_unknown, topKHz)
    val assessment =
        stringResource(
            when (result.assessment) {
                AcousticAssessment.INCONCLUSIVE -> Res.string.m6aq_inconclusive
                AcousticAssessment.NO_SUSPICIOUS_CUTOFF -> Res.string.m6aq_no_cutoff
                AcousticAssessment.SUSPECTED_UPSCALED -> Res.string.m6aq_suspected
            }
        )
    Text(assessment, style = MaterialTheme.typography.titleSmall)
    Text(cutoff)
    AcousticHeatGraph(result, "$assessment. $cutoff. $details")
    Text(details, style = MaterialTheme.typography.bodySmall)
    result.limitations
        .distinct()
        .filter { it in ReaderNotes }
        .forEach {
            Text(acousticCodeText(it), style = MaterialTheme.typography.bodySmall)
        }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(Res.string.m6aq_read_title),
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            stringResource(Res.string.m6aq_read_axes),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(Res.string.m6aq_read_colour),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(Res.string.m6aq_read_cutoff),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** The audit panel in a dismissable dialog, for the menu-row surface every host mounts. */
@Composable
fun AcousticQualityDialog(
    target: AcousticAuditTarget,
    owner: AcousticAuditOwner,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.m6aq_close)) }
        },
        title = { Text(stringResource(Res.string.m6aq_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                AcousticQualityPanel(target, owner)
            }
        },
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
    )
}

/** The audit surface a host mounts for [AcousticAuditOwner.dialogTarget], or nothing while none is open. */
@Composable
fun AcousticQualityOverlay(owner: AcousticAuditOwner) {
    val target by owner.dialogTarget.collectAsState()
    val current = target ?: return
    AcousticQualityDialog(current, owner, onDismiss = owner::closeDialog)
}

@Composable
internal fun acousticCodeText(code: String): String =
    stringResource(
        when (code) {
            "LOCAL_FILE" -> Res.string.m6aq_source_local_file
            "REMOTE_STREAM" -> Res.string.m6aq_source_remote_stream
            "insufficient_active_evidence" ->
                Res.string.m6aq_code_insufficient_active_evidence
            "pcm_budget_shortened_excerpt" ->
                Res.string.m6aq_code_pcm_budget_shortened_excerpt
            "deadline_exceeded" -> Res.string.m6aq_code_deadline_exceeded
            "analysis_busy" -> Res.string.m6aq_code_analysis_busy
            "input_unreadable" -> Res.string.m6aq_code_input_unreadable
            "analysis_failed" -> Res.string.m6aq_code_analysis_failed
            "no_current_source" -> Res.string.m6aq_code_no_current_source
            "current_source_unavailable" ->
                Res.string.m6aq_code_current_source_unavailable
            "no_safe_source" -> Res.string.m6aq_code_no_safe_source
            "remote_unsupported" -> Res.string.m6aq_code_remote_unsupported
            "codec_unknown" -> Res.string.m6aq_code_codec_unknown
            "insufficient_pcm" -> Res.string.m6aq_code_insufficient_pcm
            else -> Res.string.m6aq_generic
        }
    )
