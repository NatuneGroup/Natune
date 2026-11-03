/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.utils

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.acoustics.AcousticAssessment
import moe.rukamori.archivetune.acoustics.AcousticAuditController
import moe.rukamori.archivetune.acoustics.AcousticAuditResult
import moe.rukamori.archivetune.acoustics.AcousticAuditStatus
import moe.rukamori.archivetune.acoustics.AcousticAuditTarget
import moe.rukamori.archivetune.acoustics.AcousticHeatAxis
import moe.rukamori.archivetune.acoustics.rememberAcousticAuditController

@Composable
fun AcousticQualitySection(
    target: AcousticAuditTarget,
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    val controller = rememberAcousticAuditController(target, active)
    AcousticQualityContent(target, controller, modifier, active)
}

@Composable
internal fun AcousticQualityContent(
    target: AcousticAuditTarget,
    controller: AcousticAuditController,
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val running =
        state.status == AcousticAuditStatus.RESOLVING ||
            state.status == AcousticAuditStatus.DECODING ||
            state.status == AcousticAuditStatus.ANALYZING
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(target.title, style = MaterialTheme.typography.titleMedium)
            if (target.artists.isNotBlank()) {
                Text(
                    target.artists,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (running) {
            val progress = state.progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (progress == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    acousticQualityStatusText(state.status),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        } else if (state.status != AcousticAuditStatus.COMPLETE) {
            Text(
                acousticQualityStatusText(state.status),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        state.message?.let {
            Text(
                acousticCodeText(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.result
            ?.takeIf { active && it.mediaId == target.mediaId }
            ?.let { result ->
                AcousticQualityResult(result)
            }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (running) {
                TextButton(onClick = controller::cancel) {
                    Text(stringResource(R.string.acoustic_quality_cancel))
                }
            } else {
                Button(onClick = controller::start, enabled = active) {
                    Text(
                        stringResource(
                            if (state.status == AcousticAuditStatus.IDLE) {
                                R.string.acoustic_quality_run
                            } else R.string.acoustic_quality_retry
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun acousticQualityStatusText(status: AcousticAuditStatus): String =
    stringResource(
        when (status) {
            AcousticAuditStatus.IDLE -> R.string.acoustic_quality_idle
            AcousticAuditStatus.RESOLVING -> R.string.acoustic_quality_resolving
            AcousticAuditStatus.DECODING -> R.string.acoustic_quality_decoding
            AcousticAuditStatus.ANALYZING -> R.string.acoustic_quality_analyzing
            AcousticAuditStatus.COMPLETE -> R.string.acoustic_quality_complete
            AcousticAuditStatus.UNAVAILABLE -> R.string.acoustic_quality_unavailable
            AcousticAuditStatus.FAILED -> R.string.acoustic_quality_failed
            AcousticAuditStatus.CANCELLED -> R.string.acoustic_quality_cancelled
        }
    )

/**
 * Notes that change how to read a result; the rest of [AcousticAuditResult.limitations] is fixed
 * method detail.
 */
private val ReaderNotes = setOf("insufficient_active_evidence", "pcm_budget_shortened_excerpt")

@Composable
private fun AcousticQualityResult(result: AcousticAuditResult) {
    val headline =
        stringResource(
            when (result.assessment) {
                AcousticAssessment.INCONCLUSIVE -> R.string.acoustic_quality_verdict_inconclusive
                AcousticAssessment.NO_SUSPICIOUS_CUTOFF -> R.string.acoustic_quality_verdict_lossless
                AcousticAssessment.SUSPECTED_UPSCALED -> R.string.acoustic_quality_verdict_upscaled
            }
        )
    val support =
        stringResource(
            when (result.assessment) {
                AcousticAssessment.INCONCLUSIVE ->
                    R.string.acoustic_quality_verdict_inconclusive_support
                AcousticAssessment.NO_SUSPICIOUS_CUTOFF ->
                    R.string.acoustic_quality_verdict_lossless_support
                AcousticAssessment.SUSPECTED_UPSCALED ->
                    R.string.acoustic_quality_verdict_upscaled_support
            }
        )
    val headlineColor =
        when (result.assessment) {
            AcousticAssessment.NO_SUSPICIOUS_CUTOFF -> MaterialTheme.colorScheme.primary
            AcousticAssessment.SUSPECTED_UPSCALED -> MaterialTheme.colorScheme.error
            AcousticAssessment.INCONCLUSIVE -> MaterialTheme.colorScheme.onSurface
        }
    val topKHz = result.nyquistHz / 1000f
    val cutoff =
        result.cutoffHz
            ?.takeIf { it.isFinite() }
            ?.let {
                stringResource(R.string.acoustic_quality_cutoff, it / 1000f, topKHz)
            } ?: stringResource(R.string.acoustic_quality_cutoff_unknown, topKHz)
    val channels =
        when (result.channelCount) {
            1 -> stringResource(R.string.acoustic_quality_mono)
            2 -> stringResource(R.string.acoustic_quality_stereo)
            else -> stringResource(R.string.acoustic_quality_channels, result.channelCount)
        }
    val details =
        stringResource(
            R.string.acoustic_quality_details,
            AcousticHeatAxis.formatClock(result.startMs / 1000.0),
            AcousticHeatAxis.formatClock((result.startMs + result.durationMs) / 1000.0),
            result.sampleRate / 1000f,
            channels,
            acousticCodeText(result.sourceLabel),
        )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                headline,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = headlineColor,
            )
            Text(
                support,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AcousticSpectrumGraph(result, "$headline. $support. $cutoff. $details")
        AcousticQualityFacts(result)
        AcousticQualityDetails(result, details, cutoff)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AcousticQualityFacts(result: AcousticAuditResult) {
    val sampleRate = stringResource(R.string.acoustic_quality_chip_sample_rate, result.sampleRate / 1000f)
    val pcmFormat =
        when {
            "decoded_pcm_float" in result.limitations ->
                stringResource(R.string.acoustic_quality_chip_pcm_float)
            "decoded_pcm16" in result.limitations ->
                stringResource(R.string.acoustic_quality_chip_pcm16)
            else -> null
        }
    val cutoffHz = result.cutoffHz?.takeIf { it.isFinite() && it > 0f }
    val cutoff =
        if (cutoffHz != null) {
            stringResource(R.string.acoustic_quality_chip_cutoff, cutoffHz / 1000f)
        } else {
            stringResource(R.string.acoustic_quality_chip_cutoff_none)
        }
    val source = acousticCodeText(result.sourceLabel)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FactChip(sampleRate)
        pcmFormat?.let { FactChip(it) }
        FactChip(cutoff)
        FactChip(source)
    }
}

@Composable
private fun FactChip(text: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun AcousticQualityDetails(
    result: AcousticAuditResult,
    details: String,
    cutoff: String,
) {
    var expanded by rememberSaveable(result.mediaId) { mutableStateOf(false) }
    val expandedDescription =
        stringResource(
            if (expanded) R.string.acoustic_quality_expanded
            else R.string.acoustic_quality_collapsed,
        )
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { expanded = !expanded }
                    .semantics { stateDescription = expandedDescription }
                    .padding(vertical = 6.dp),
        ) {
            Text(
                stringResource(R.string.acoustic_quality_details_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                painterResource(
                    if (expanded) R.drawable.expand_less else R.drawable.expand_more,
                ),
                contentDescription =
                    stringResource(
                        if (expanded) R.string.acoustic_quality_details_collapse
                        else R.string.acoustic_quality_details_expand,
                    ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(
                    stringResource(R.string.acoustic_quality_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(details, style = MaterialTheme.typography.bodySmall)
                Text(cutoff, style = MaterialTheme.typography.bodySmall)
                result.limitations
                    .distinct()
                    .filter { it in ReaderNotes }
                    .forEach {
                        Text(acousticCodeText(it), style = MaterialTheme.typography.bodySmall)
                    }
                if ("spectral_heuristic_not_provenance" in result.limitations) {
                    Text(
                        stringResource(R.string.acoustic_quality_code_spectral_heuristic),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(R.string.acoustic_quality_read_title),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    stringResource(R.string.acoustic_quality_read_axes),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    stringResource(R.string.acoustic_quality_read_curve),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    stringResource(R.string.acoustic_quality_read_cutoff),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
internal fun acousticCodeText(code: String): String =
    stringResource(
        when (code) {
            "LOCAL_FILE" -> R.string.acoustic_quality_source_local_file
            "LOCAL_CONTENT" -> R.string.acoustic_quality_source_local_content
            "TIDAL_CACHED" -> R.string.acoustic_quality_code_tidal_cached
            "QOBUZ_CACHED" -> R.string.acoustic_quality_code_qobuz_cached
            "QOBUZ_BACKUP_CACHED" -> R.string.acoustic_quality_code_qobuz_backup_cached
            "TIDAL_CURRENT" -> R.string.acoustic_quality_code_tidal_current
            "QOBUZ_CURRENT" -> R.string.acoustic_quality_code_qobuz_current
            "QOBUZ_BACKUP_CURRENT" -> R.string.acoustic_quality_code_qobuz_backup_current
            "insufficient_active_evidence" ->
                R.string.acoustic_quality_code_insufficient_active_evidence
            "pcm_budget_shortened_excerpt" ->
                R.string.acoustic_quality_code_pcm_budget_shortened_excerpt
            "spectral_heuristic_not_provenance" ->
                R.string.acoustic_quality_code_spectral_heuristic
            "deadline_exceeded" -> R.string.acoustic_quality_code_deadline_exceeded
            "analysis_busy" -> R.string.acoustic_quality_code_analysis_busy
            "source_changed" -> R.string.acoustic_quality_code_source_changed
            "input_unreadable" -> R.string.acoustic_quality_code_input_unreadable
            "local_unreadable" -> R.string.acoustic_quality_code_local_unreadable
            "input_permission_denied" -> R.string.acoustic_quality_code_input_permission_denied
            "analysis_failed" -> R.string.acoustic_quality_code_analysis_failed
            "input_closed" -> R.string.acoustic_quality_code_input_closed
            "invalid_read" -> R.string.acoustic_quality_code_invalid_read
            "read_budget_exceeded" -> R.string.acoustic_quality_code_read_budget_exceeded
            "network_budget_exceeded" -> R.string.acoustic_quality_code_network_budget_exceeded
            "no_target" -> R.string.acoustic_quality_code_no_target
            "local_authority_unsupported" ->
                R.string.acoustic_quality_code_local_authority_unsupported
            "local_not_regular" -> R.string.acoustic_quality_code_local_not_regular
            "content_unreadable" -> R.string.acoustic_quality_code_content_unreadable
            "content_not_seekable" -> R.string.acoustic_quality_code_content_not_seekable
            "provider_input_unsupported" ->
                R.string.acoustic_quality_code_provider_input_unsupported
            "telegram_unsupported" -> R.string.acoustic_quality_code_telegram_unsupported
            "current_source_unavailable" ->
                R.string.acoustic_quality_code_current_source_unavailable
            "no_safe_source" -> R.string.acoustic_quality_code_no_safe_source
            "local_changed" -> R.string.acoustic_quality_code_local_changed
            "cache_gap" -> R.string.acoustic_quality_code_cache_gap
            "cache_changed" -> R.string.acoustic_quality_code_cache_changed
            "remote_unsupported" -> R.string.acoustic_quality_code_remote_unsupported
            "source_expired" -> R.string.acoustic_quality_code_source_expired
            "remote_length_unknown" -> R.string.acoustic_quality_code_remote_length_unknown
            "remote_unreadable" -> R.string.acoustic_quality_code_remote_unreadable
            "remote_short_read" -> R.string.acoustic_quality_code_remote_short_read
            "remote_range_unsupported" -> R.string.acoustic_quality_code_remote_range_unsupported
            "drm_unsupported" -> R.string.acoustic_quality_code_drm_unsupported
            "no_audio_track" -> R.string.acoustic_quality_code_no_audio_track
            "codec_unknown" -> R.string.acoustic_quality_code_codec_unknown
            "decoder_stalled" -> R.string.acoustic_quality_code_decoder_stalled
            "decoder_unavailable" -> R.string.acoustic_quality_code_decoder_unavailable
            "encrypted_unsupported" -> R.string.acoustic_quality_code_encrypted_unsupported
            "decoded_format_unknown" -> R.string.acoustic_quality_code_decoded_format_unknown
            "decoded_format_unsupported" ->
                R.string.acoustic_quality_code_decoded_format_unsupported
            "decoded_format_changed" -> R.string.acoustic_quality_code_decoded_format_changed
            "insufficient_heap_headroom" ->
                R.string.acoustic_quality_code_insufficient_heap_headroom
            "decoded_buffer_invalid" -> R.string.acoustic_quality_code_decoded_buffer_invalid
            "decoded_timestamps_discontinuous" ->
                R.string.acoustic_quality_code_decoded_timestamps_discontinuous
            "non_finite_pcm" -> R.string.acoustic_quality_code_non_finite_pcm
            "insufficient_pcm" -> R.string.acoustic_quality_code_insufficient_pcm
            "codec_unsupported" -> R.string.acoustic_quality_code_codec_unsupported
            "container_or_codec_unsupported" ->
                R.string.acoustic_quality_code_container_or_codec_unsupported
            else -> R.string.acoustic_quality_generic
        }
    )
