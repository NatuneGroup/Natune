package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.dsp.spectral.SpectralVerdict
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.QueueState
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_acoustic_channels
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_acoustic_codec
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_acoustic_confidence
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_acoustic_cutoff
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_acoustic_lossless_claim
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_acoustic_no_song
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_acoustic_pcm_rate
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_acoustic_run
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_acoustic_running
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_acoustic_suspicion_note
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_acoustic_unmeasurable
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_acoustic_unsupported
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_acoustic_verdict
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_acoustic_verdict_clean
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_acoustic_verdict_unknown
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_acoustic_verdict_upscaled
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_bitrate_label
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_buffer_ahead
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_buffer_health_label
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_codec_label
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_content_length_label
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_discord_integration
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_field_unavailable
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_last_end
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_last_start
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_media_id_label
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_nerd_stats_title
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_no
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_no_track_playing
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_playback_speed_label
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_presence_running
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_presence_stopped
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_real_time_stats
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_sample_rate_label
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_state_buffering
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_state_ended
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_state_idle
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_state_label
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_state_ready
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_status_active
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_status_inactive
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_stream_source_label
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_track_label
import moe.rukamori.archivetune.shared.ui.resources.m6_dev_yes
import moe.rukamori.archivetune.shared.ui.screens.mediainfo.formatReportedBitrate
import moe.rukamori.archivetune.shared.ui.screens.mediainfo.formatReportedByteCount
import moe.rukamori.archivetune.shared.ui.screens.mediainfo.formatReportedSampleRate
import org.jetbrains.compose.resources.stringResource

// ----------------------------------------------------------------------------- models

/** What the developer Discord card honestly shows. [lastStartMs]/[lastEndMs] are the track window the last
 * presence update carried; null means no timed presence has been sent this session. */
data class DiscordDebugState(
    val running: Boolean = false,
    val lastStartMs: Long? = null,
    val lastEndMs: Long? = null,
)

/** The engine-facing state a snapshot supports, named like the Android player's states. */
enum class NerdPlaybackState { IDLE, BUFFERING, READY, ENDED }

/** Encoded-format facts for the current track. A per-field null renders "unavailable", never a guess. */
data class NerdStatsFormat(
    val codec: String? = null,
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null,
    val contentLengthBytes: Long? = null,
)

/** One poll of the nerd-stats card. Snapshot-derived values are always present once a track loads; [format] is
 * whatever the host can honestly report, or null when it has no format evidence at all. */
data class NerdStatsState(
    val mediaId: String? = null,
    val title: String? = null,
    val artists: String? = null,
    val sourceLabel: String? = null,
    val format: NerdStatsFormat? = null,
    val positionMs: Long? = null,
    val bufferedPositionMs: Long? = null,
    val durationMs: Long? = null,
    val playbackSpeed: Float? = null,
    val state: NerdPlaybackState = NerdPlaybackState.IDLE,
)

/** The song or episode the acoustic check would measure. */
data class AcousticTarget(
    val mediaId: String,
    val provider: String,
    val title: String,
    val artists: String?,
)

/** Fields of one finished acoustic measurement. Per-field null stays "unavailable": an inconclusive verdict
 * and missing measurements are honest results, not hidden ones. */
data class AcousticRunFields(
    val verdict: SpectralVerdict? = null,
    val confidencePercent: Int? = null,
    val cutoffHz: Int? = null,
    val pcmSampleRateHz: Int? = null,
    val channelCount: Int? = null,
    val codecName: String? = null,
    val claimedLossless: Boolean? = null,
)

sealed interface AcousticMeasurement {
    /** The check decoded and analysed the source. */
    data class Measured(val fields: AcousticRunFields) : AcousticMeasurement

    /** The host can measure stored audio, but this source has no readable file or stream for the probe. */
    data object Unmeasurable : AcousticMeasurement
}

// ----------------------------------------------------------------------------- mapping

internal fun nerdStatsStateOf(
    snapshot: PlaybackSnapshot?,
    item: ContentItem?,
    format: NerdStatsFormat?,
    positionMs: Long?,
): NerdStatsState = NerdStatsState(
    mediaId = item?.ref?.id,
    title = item?.title?.takeIf { it.isNotBlank() },
    artists = item?.artists?.joinToString(", ") { it.name }?.takeIf { it.isNotBlank() }
        ?: item?.subtitle?.takeIf { it.isNotBlank() },
    sourceLabel = item?.ref?.provider?.let(::nerdSourceLabel),
    format = format,
    positionMs = positionMs,
    bufferedPositionMs = snapshot?.bufferedPositionMs,
    durationMs = snapshot?.durationMs,
    playbackSpeed = snapshot?.playbackSpeed,
    state = when {
        snapshot == null -> NerdPlaybackState.IDLE
        snapshot.isEnded -> NerdPlaybackState.ENDED
        snapshot.isBuffering -> NerdPlaybackState.BUFFERING
        else -> NerdPlaybackState.READY
    },
)

/** The occurrence the snapshot names, as the acoustic check's target. Null while nothing plays. */
internal fun acousticTargetOf(snapshot: PlaybackSnapshot?, queue: QueueState): AcousticTarget? {
    val item = queue.entries.firstOrNull { it.occurrenceId == snapshot?.occurrenceId }?.item ?: return null
    return AcousticTarget(
        mediaId = item.ref.id,
        provider = item.ref.provider,
        title = item.title,
        artists = item.artists.joinToString(", ") { it.name }.takeIf { it.isNotBlank() }
            ?: item.subtitle.takeIf { it.isNotBlank() },
    )
}

/** The provider name a nerd-stats source row shows. Unknown ids pass through rather than hiding the row. */
internal fun nerdSourceLabel(providerId: String): String = when (providerId) {
    ContentProvider.LOCAL.id -> "Local"
    ContentProvider.YOUTUBE.id -> "YouTube"
    ContentProvider.SPOTIFY_CATALOG.id -> "Spotify"
    ContentProvider.SPOTIFY_STREAMING.id -> "Spotify (streaming)"
    ContentProvider.TIDAL.id -> "Tidal"
    ContentProvider.QOBUZ.id -> "Qobuz"
    ContentProvider.QOBUZ_BACKUP.id -> "Qobuz (backup)"
    ContentProvider.DEEZER.id -> "Deezer"
    ContentProvider.APPLE.id -> "Apple Music"
    ContentProvider.JIOSAAVN.id -> "JioSaavn"
    ContentProvider.AMAZON.id -> "Amazon"
    ContentProvider.QQ.id -> "QQ Music"
    ContentProvider.TELEGRAM.id -> "Telegram"
    ContentProvider.LASTFM.id -> "Last.fm"
    ContentProvider.DISCORD.id -> "Discord"
    else -> providerId
}

/** Buffered share of the known duration, rounded down like the player's own percentage. */
internal fun nerdStatsBufferPercent(bufferedPositionMs: Long?, durationMs: Long?): Int? {
    val buffered = bufferedPositionMs ?: return null
    val duration = durationMs?.takeIf { it > 0 } ?: return null
    return (buffered * 100L / duration).toInt().coerceIn(0, 100)
}

// ----------------------------------------------------------------------------- Discord debug

/**
 * The developer Discord card, fed by the host's presence owner. [running] means a live identified gateway,
 * not merely a signed-in account.
 */
@Composable
fun HostDiscordDebugSection(
    debug: StateFlow<DiscordDebugState>,
    formatTime: (Long) -> String,
    modifier: Modifier = Modifier,
) {
    val state by debug.collectAsState()
    val running = state.running
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(Res.string.m6_dev_discord_integration),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 2)
                    Text(
                        text = stringResource(
                            if (running) Res.string.m6_dev_presence_running else Res.string.m6_dev_presence_stopped),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = (if (running) RUNNING_GREEN else MaterialTheme.colorScheme.error).copy(alpha = 0.2f),
                ) {
                    Text(
                        text = stringResource(
                            if (running) Res.string.m6_dev_status_active else Res.string.m6_dev_status_inactive),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (running) RUNNING_GREEN else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                DevTimestampItem(stringResource(Res.string.m6_dev_last_start), state.lastStartMs?.let(formatTime))
                DevTimestampItem(stringResource(Res.string.m6_dev_last_end), state.lastEndMs?.let(formatTime))
            }
        }
    }
}

@Composable
private fun DevTimestampItem(label: String, value: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value ?: "—", style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
    }
}

// ----------------------------------------------------------------------------- nerd stats

/**
 * The developer nerd-stats card. It polls the player's own snapshot like the Android card does — composition
 * bounds the 500 ms loop, so an unmounted card asks the engine for nothing. [formatFor] supplies whatever
 * encoded-format facts the host honestly has for one content ref; it must return null when it has none.
 */
@Composable
fun HostNerdStatsSection(
    player: MusicPlayerController,
    queue: StateFlow<QueueState>,
    formatFor: suspend (ContentRef) -> NerdStatsFormat?,
    modifier: Modifier = Modifier,
) {
    var stats by remember { mutableStateOf(NerdStatsState()) }
    val currentFormatFor by rememberUpdatedState(formatFor)
    LaunchedEffect(player, queue) {
        var formatKey: ContentRef? = null
        var format: NerdStatsFormat? = null
        while (true) {
            val snapshot = player.snapshot.value
            val item = queue.value.entries.firstOrNull { it.occurrenceId == snapshot?.occurrenceId }?.item
            val ref = item?.ref
            if (ref != formatKey) {
                formatKey = ref
                format = ref?.let {
                    try {
                        currentFormatFor(it)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            stats = nerdStatsStateOf(snapshot, item, format, player.positionMs())
            delay(NERD_STATS_POLL_MS)
        }
    }
    val format = stats.format
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column {
                Text(stringResource(Res.string.m6_dev_nerd_stats_title),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(stringResource(Res.string.m6_dev_real_time_stats),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (stats.mediaId == null) {
                Text(stringResource(Res.string.m6_dev_no_track_playing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                DevFieldRow(stringResource(Res.string.m6_dev_track_label),
                    listOfNotNull(stats.title, stats.artists).joinToString(" — ").ifBlank { null })
                DevFieldRow(stringResource(Res.string.m6_dev_stream_source_label), stats.sourceLabel)
                DevFieldRow(stringResource(Res.string.m6_dev_codec_label), format?.codec)
                DevFieldRow(stringResource(Res.string.m6_dev_bitrate_label),
                    format?.bitrateKbps?.let { formatReportedBitrate(it * 1_000) })
                DevFieldRow(stringResource(Res.string.m6_dev_sample_rate_label),
                    formatReportedSampleRate(format?.sampleRateHz))
                DevFieldRow(stringResource(Res.string.m6_dev_content_length_label),
                    formatReportedByteCount(format?.contentLengthBytes))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val percent = nerdStatsBufferPercent(stats.bufferedPositionMs, stats.durationMs)
                val aheadSeconds = stats.bufferedPositionMs?.let { buffered ->
                    ((buffered - (stats.positionMs ?: buffered)) / 1_000L).coerceAtLeast(0L)
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DevFieldRow(stringResource(Res.string.m6_dev_buffer_health_label),
                        if (percent != null && aheadSeconds != null) {
                            stringResource(Res.string.m6_dev_buffer_ahead, percent, aheadSeconds)
                        } else null)
                    if (percent != null) {
                        LinearProgressIndicator(
                            progress = { percent / 100f },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                            color = when {
                                percent > 70 -> RUNNING_GREEN
                                percent > 30 -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.error
                            },
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        DevFieldRow(stringResource(Res.string.m6_dev_state_label), stringResource(when (stats.state) {
                            NerdPlaybackState.IDLE -> Res.string.m6_dev_state_idle
                            NerdPlaybackState.BUFFERING -> Res.string.m6_dev_state_buffering
                            NerdPlaybackState.READY -> Res.string.m6_dev_state_ready
                            NerdPlaybackState.ENDED -> Res.string.m6_dev_state_ended
                        }))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        DevFieldRow(stringResource(Res.string.m6_dev_playback_speed_label),
                            stats.playbackSpeed?.let { "${it}x" })
                    }
                }
                DevFieldRow(stringResource(Res.string.m6_dev_media_id_label),
                    stats.mediaId?.let { if (it.length > 16) it.take(16) + "..." else it })
            }
        }
    }
}

// ----------------------------------------------------------------------------- acoustic quality

private sealed interface AcousticPhase {
    data object Idle : AcousticPhase
    data object Running : AcousticPhase
    data class Measured(val fields: AcousticRunFields) : AcousticPhase
    data object Unmeasurable : AcousticPhase
}

/**
 * The developer acoustic check. It runs only on Run, is cancelled when the section deactivates, and shows every
 * measured field or an honest "unavailable" — the check reports spectral suspicion, never proof of provenance.
 * A null [measure] means this host cannot decode audio for the check at all, so Run stays disabled instead of
 * hiding the panel.
 */
@Composable
fun HostAcousticQualitySection(
    active: Boolean,
    player: MusicPlayerController,
    queue: StateFlow<QueueState>,
    measure: (suspend (AcousticTarget) -> AcousticMeasurement)?,
    modifier: Modifier = Modifier,
) {
    val snapshot by player.snapshot.collectAsState()
    val queueState by queue.collectAsState()
    val target = acousticTargetOf(snapshot, queueState)
    // A result stays bound to the track it measured; the runner itself may be recomposed.
    var phase by remember(target?.mediaId) { mutableStateOf<AcousticPhase>(AcousticPhase.Idle) }
    val runner by rememberUpdatedState(measure)
    val scope = rememberCoroutineScope()
    var runJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) { onDispose { runJob?.cancel() } }
    LaunchedEffect(active) {
        if (!active) {
            runJob?.cancel()
            runJob = null
            phase = AcousticPhase.Idle
        }
    }
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (target == null) {
            Text(stringResource(Res.string.m6_dev_acoustic_no_song),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = {}, enabled = false) { Text(stringResource(Res.string.m6_dev_acoustic_run)) }
        } else {
            DevFieldRow(stringResource(Res.string.m6_dev_track_label),
                listOfNotNull(target.title.takeIf { it.isNotBlank() }, target.artists).joinToString(" — ")
                    .ifBlank { null })
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = measure != null && phase !is AcousticPhase.Running,
                    onClick = {
                        val runner = runner ?: return@Button
                        val targetNow = target
                        phase = AcousticPhase.Running
                        runJob = scope.launch {
                            val result = try {
                                runner(targetNow)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                AcousticMeasurement.Unmeasurable
                            }
                            phase = when (result) {
                                is AcousticMeasurement.Measured -> AcousticPhase.Measured(result.fields)
                                AcousticMeasurement.Unmeasurable -> AcousticPhase.Unmeasurable
                            }
                        }
                    },
                ) {
                    Text(stringResource(Res.string.m6_dev_acoustic_run))
                }
                if (phase is AcousticPhase.Running) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text(stringResource(Res.string.m6_dev_acoustic_running),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (measure == null) {
                Text(stringResource(Res.string.m6_dev_acoustic_unsupported),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (phase is AcousticPhase.Unmeasurable) {
                Text(stringResource(Res.string.m6_dev_acoustic_unmeasurable),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val fields = (phase as? AcousticPhase.Measured)?.fields
            DevFieldRow(stringResource(Res.string.m6_dev_acoustic_verdict), fields?.verdict?.let {
                stringResource(when (it) {
                    SpectralVerdict.UNKNOWN -> Res.string.m6_dev_acoustic_verdict_unknown
                    SpectralVerdict.NO_SUSPICIOUS_CUTOFF -> Res.string.m6_dev_acoustic_verdict_clean
                    SpectralVerdict.SUSPECTED_UPSCALED -> Res.string.m6_dev_acoustic_verdict_upscaled
                })
            })
            DevFieldRow(stringResource(Res.string.m6_dev_acoustic_confidence), fields?.confidencePercent?.let { "$it%" })
            DevFieldRow(stringResource(Res.string.m6_dev_acoustic_cutoff), fields?.cutoffHz?.let { "$it Hz" })
            DevFieldRow(stringResource(Res.string.m6_dev_acoustic_pcm_rate),
                formatReportedSampleRate(fields?.pcmSampleRateHz))
            DevFieldRow(stringResource(Res.string.m6_dev_acoustic_channels), fields?.channelCount?.toString())
            DevFieldRow(stringResource(Res.string.m6_dev_acoustic_codec), fields?.codecName)
            DevFieldRow(stringResource(Res.string.m6_dev_acoustic_lossless_claim), fields?.claimedLossless?.let {
                stringResource(if (it) Res.string.m6_dev_yes else Res.string.m6_dev_no)
            })
            Text(stringResource(Res.string.m6_dev_acoustic_suspicion_note),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ----------------------------------------------------------------------------- shared rows

@Composable
private fun DevFieldRow(label: String, value: String?, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value ?: stringResource(Res.string.m6_dev_field_unavailable),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = if (value == null) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private val RUNNING_GREEN = androidx.compose.ui.graphics.Color(0xFF43B581)

private const val NERD_STATS_POLL_MS = 500L
