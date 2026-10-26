package moe.rukamori.archivetune.shared.ui.screens.mediainfo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_album
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_android_output
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_android_output_note
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_artist
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_audio_offload
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_audio_specs
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_bits
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_codec
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_compressed_or_unknown
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_credit_composed_by
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_credit_lyrics_by
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_credit_note
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_credit_source_description
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_credit_source_lyrics
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_credit_written_by
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_credits
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_decoded_audio_note
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_decoded_audio_observed
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_decoded_channel_count
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_decoded_pcm_float_format
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_decoded_pcm_integer_format
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_decoded_sample_format
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_decoded_sample_rate
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_duration
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_format_id
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_hardware_output
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_identity
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_mime_type
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_more_video_details
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_no
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_no_credits
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_no_reported_format
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_not_measured
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_not_observed
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_not_reported
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_output_note
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_output_sample_format
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_overview
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_reported_bit_depth
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_reported_bitrate
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_reported_channel_count
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_reported_loudness
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_reported_perceptual_loudness
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_reported_sample_rate
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_reported_size
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_reported_stream
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_stream_note
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_title
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_title_label
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_unknown
import moe.rukamori.archivetune.shared.ui.resources.m9_track_info_yes
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private enum class TrackInfoTab(val label: StringResource) {
    Overview(Res.string.m9_track_info_overview),
    AudioSpecs(Res.string.m9_track_info_audio_specs),
}

/**
 * Track info and audio specs. [artwork] renders the cover from its URL, so the host keeps the
 * image loader; without it the header shows the title initial, as the baseline did for no artwork.
 */
@Composable
fun TrackInfoAndSpecs(
    state: TrackInfoUi,
    actions: TrackInfoActions,
    modifier: Modifier = Modifier,
    artwork: (@Composable (url: String, modifier: Modifier) -> Unit)? = null,
) {
    var selectedTabIndex by rememberSaveable(state.trackId) { mutableStateOf(TrackInfoTab.Overview.ordinal) }
    val selectedTab = TrackInfoTab.entries[selectedTabIndex]
    val resolvedTitle = state.title.ifBlank { state.videoTitle.orEmpty() }
    val resolvedArtists = state.artists.ifBlank { state.videoAuthor.orEmpty() }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            TrackInfoHeader(
                title = resolvedTitle,
                artists = resolvedArtists,
                artworkUrl = state.artworkUrl,
                artwork = artwork,
                appleExperience = state.appleExperience,
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TrackInfoTab.entries.forEach { tab ->
                    val selected = tab == selectedTab
                    TextButton(
                        onClick = { selectedTabIndex = tab.ordinal },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = stringResource(tab.label),
                            color =
                                if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                    }
                }
            }
        }
        item {
            when (selectedTab) {
                TrackInfoTab.Overview -> TrackInfoOverview(
                    title = resolvedTitle,
                    artists = resolvedArtists,
                    album = state.album,
                    durationMs = state.durationMs,
                    credits = state.credits,
                    appleExperience = state.appleExperience,
                )
                TrackInfoTab.AudioSpecs -> TrackInfoAudioSpecs(
                    format = state.format,
                    decodedPcm = state.decodedPcm,
                    reportedFormat = state.reportedFormat,
                    androidOutput = state.androidOutput,
                    appleExperience = state.appleExperience,
                )
            }
        }
        if (isYouTubeVideoId(state.trackId)) {
            item {
                TextButton(
                    onClick = actions.onShowVideoDetails,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(Res.string.m9_track_info_more_video_details))
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun TrackInfoHeader(
    title: String,
    artists: String,
    artworkUrl: String?,
    artwork: (@Composable (url: String, modifier: Modifier) -> Unit)?,
    appleExperience: Boolean,
) {
    TrackInfoCard(appleExperience) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                modifier = Modifier.size(76.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                val url = artworkUrl?.takeIf(String::isNotBlank)
                if (url != null && artwork != null) {
                    artwork(url, Modifier.size(76.dp).clip(MaterialTheme.shapes.large))
                } else {
                    Text(
                        text = title.take(1).ifBlank { "♫" },
                        modifier = Modifier.padding(20.dp),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.m9_track_info_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = title.ifBlank { stringResource(Res.string.m9_track_info_not_reported) },
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (artists.isNotBlank()) {
                    Text(
                        text = artists,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackInfoOverview(
    title: String,
    artists: String,
    album: String?,
    durationMs: Long?,
    credits: List<TrackCreditUi>,
    appleExperience: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TrackInfoCard(appleExperience) {
            Text(stringResource(Res.string.m9_track_info_identity), style = MaterialTheme.typography.titleMedium)
            TrackInfoValueRow(
                label = stringResource(Res.string.m9_track_info_title_label),
                value = title.takeIf(String::isNotBlank) ?: stringResource(Res.string.m9_track_info_not_reported),
            )
            if (artists.isNotBlank()) {
                HorizontalDivider()
                TrackInfoValueRow(stringResource(Res.string.m9_track_info_artist), artists)
            }
            if (!album.isNullOrBlank()) {
                HorizontalDivider()
                TrackInfoValueRow(stringResource(Res.string.m9_track_info_album), album)
            }
            formatTrackDuration(durationMs)?.let { duration ->
                HorizontalDivider()
                TrackInfoValueRow(stringResource(Res.string.m9_track_info_duration), duration)
            }
        }
        TrackInfoCard(appleExperience) {
            Text(stringResource(Res.string.m9_track_info_credits), style = MaterialTheme.typography.titleMedium)
            if (credits.isEmpty()) {
                Text(
                    text = stringResource(Res.string.m9_track_info_no_credits),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                credits.forEachIndexed { index, credit ->
                    if (index > 0) HorizontalDivider()
                    TrackCreditRow(credit)
                }
            }
            Text(
                text = stringResource(Res.string.m9_track_info_credit_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TrackInfoAudioSpecs(
    format: MediaInfoStreamFormat?,
    decodedPcm: DecodedPcmUi?,
    reportedFormat: ReportedAudioFormatUi?,
    androidOutput: AndroidOutputUi?,
    appleExperience: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TrackInfoCard(appleExperience) {
            Text(stringResource(Res.string.m9_track_info_reported_stream), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(Res.string.m9_track_info_stream_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (format == null && reportedFormat == null) {
                Text(
                    text = stringResource(Res.string.m9_track_info_no_reported_format),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val details = buildList<Pair<StringResource, String>> {
                    format?.codecs?.takeIf(String::isNotBlank)?.let {
                        add(Res.string.m9_track_info_codec to it)
                    }
                    format?.mimeType?.takeIf(String::isNotBlank)?.let {
                        add(Res.string.m9_track_info_mime_type to it)
                    }
                    format?.itag?.takeIf { it > 0 }?.let {
                        add(Res.string.m9_track_info_format_id to it.toString())
                    }
                    formatReportedBitrate(format?.bitrateBps)?.let {
                        add(Res.string.m9_track_info_reported_bitrate to it)
                    }
                    val sampleRate = reportedAudioSampleRateHz(reportedFormat, format?.sampleRateHz)
                    formatReportedSampleRate(sampleRate)?.let {
                        add(Res.string.m9_track_info_reported_sample_rate to it)
                    }
                    formatReportedByteCount(format?.contentLengthBytes)?.let {
                        add(Res.string.m9_track_info_reported_size to it)
                    }
                    formatReportedLoudness(format?.loudnessDb)?.let {
                        add(Res.string.m9_track_info_reported_loudness to it)
                    }
                    formatReportedLoudness(format?.perceptualLoudnessDb)?.let {
                        add(Res.string.m9_track_info_reported_perceptual_loudness to it)
                    }
                }
                if (details.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.m9_track_info_no_reported_format),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    details.forEachIndexed { index, (label, value) ->
                        if (index > 0) HorizontalDivider()
                        TrackInfoValueRow(stringResource(label), value)
                    }
                }
            }
            HorizontalDivider()
            TrackInfoValueRow(
                stringResource(Res.string.m9_track_info_reported_bit_depth),
                reportedFormat?.bitDepth?.let { stringResource(Res.string.m9_track_info_bits, it) }
                    ?: stringResource(Res.string.m9_track_info_unknown),
            )
            HorizontalDivider()
            TrackInfoValueRow(
                stringResource(Res.string.m9_track_info_reported_channel_count),
                reportedFormat?.channelCount?.toString() ?: stringResource(Res.string.m9_track_info_unknown),
            )
        }
        TrackInfoCard(appleExperience) {
            Text(
                stringResource(Res.string.m9_track_info_decoded_audio_observed),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(Res.string.m9_track_info_decoded_audio_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val unknownValue = stringResource(Res.string.m9_track_info_unknown)
            val notMeasured = stringResource(Res.string.m9_track_info_not_measured)
            val sampleRate =
                decodedPcm?.let { formatReportedSampleRate(it.sampleRateHz) }
                    ?: if (decodedPcm == null) notMeasured else unknownValue
            val channelCount =
                when {
                    decodedPcm == null -> notMeasured
                    decodedPcm.channelCount > 0 -> decodedPcm.channelCount.toString()
                    else -> unknownValue
                }
            val bitDepth = decodedPcm?.bitsPerSample?.takeIf { it > 0 }
            val sampleFormat =
                when {
                    decodedPcm == null -> notMeasured
                    bitDepth == null -> unknownValue
                    decodedPcm.isFloatingPoint ->
                        stringResource(Res.string.m9_track_info_decoded_pcm_float_format, bitDepth)
                    else -> stringResource(Res.string.m9_track_info_decoded_pcm_integer_format, bitDepth)
                }
            TrackInfoValueRow(stringResource(Res.string.m9_track_info_decoded_sample_rate), sampleRate)
            HorizontalDivider()
            TrackInfoValueRow(stringResource(Res.string.m9_track_info_decoded_channel_count), channelCount)
            HorizontalDivider()
            TrackInfoValueRow(stringResource(Res.string.m9_track_info_decoded_sample_format), sampleFormat)
        }
        TrackInfoCard(appleExperience) {
            Text(stringResource(Res.string.m9_track_info_android_output), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(Res.string.m9_track_info_android_output_note), style = MaterialTheme.typography.bodySmall)
            val unobserved = stringResource(Res.string.m9_track_info_not_observed)
            val outputDepth = androidOutput?.pcmBitsPerSample
            TrackInfoValueRow(
                stringResource(Res.string.m9_track_info_decoded_sample_rate),
                androidOutput?.let { formatReportedSampleRate(it.sampleRateHz) } ?: unobserved,
            )
            HorizontalDivider()
            TrackInfoValueRow(
                stringResource(Res.string.m9_track_info_decoded_channel_count),
                androidOutput?.channelCount?.takeIf { it > 0 }?.toString() ?: unobserved,
            )
            HorizontalDivider()
            TrackInfoValueRow(
                stringResource(Res.string.m9_track_info_output_sample_format),
                when {
                    androidOutput == null -> unobserved
                    outputDepth == null -> stringResource(Res.string.m9_track_info_compressed_or_unknown)
                    androidOutput.pcmIsFloatingPoint ->
                        stringResource(Res.string.m9_track_info_decoded_pcm_float_format, outputDepth)
                    else -> stringResource(Res.string.m9_track_info_decoded_pcm_integer_format, outputDepth)
                },
            )
            HorizontalDivider()
            TrackInfoValueRow(
                stringResource(Res.string.m9_track_info_audio_offload),
                when (androidOutput?.offload) {
                    true -> stringResource(Res.string.m9_track_info_yes)
                    false -> stringResource(Res.string.m9_track_info_no)
                    null -> unobserved
                },
            )
            HorizontalDivider()
            TrackInfoValueRow(
                label = stringResource(Res.string.m9_track_info_hardware_output),
                value = stringResource(Res.string.m9_track_info_not_measured),
            )
            Text(
                text = stringResource(Res.string.m9_track_info_output_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TrackCreditRow(credit: TrackCreditUi) {
    val role =
        when (credit.role) {
            TrackCreditRole.WRITTEN_BY -> stringResource(Res.string.m9_track_info_credit_written_by)
            TrackCreditRole.COMPOSED_BY -> stringResource(Res.string.m9_track_info_credit_composed_by)
            TrackCreditRole.LYRICS_BY -> stringResource(Res.string.m9_track_info_credit_lyrics_by)
        }
    val source =
        when (credit.source) {
            TrackCreditSource.VIDEO_DESCRIPTION -> stringResource(Res.string.m9_track_info_credit_source_description)
            TrackCreditSource.LYRICS_METADATA -> stringResource(Res.string.m9_track_info_credit_source_lyrics)
        }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TrackInfoValueRow(role, credit.names)
        Text(source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TrackInfoValueRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.9f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.1f),
        )
    }
}

@Composable
private fun TrackInfoCard(
    appleExperience: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape =
            if (appleExperience) {
                RoundedCornerShape(28.dp)
            } else {
                MaterialTheme.shapes.medium
            },
        colors =
            CardDefaults.elevatedCardColors(
                containerColor =
                    if (appleExperience) {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
            ),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        },
    )
}
