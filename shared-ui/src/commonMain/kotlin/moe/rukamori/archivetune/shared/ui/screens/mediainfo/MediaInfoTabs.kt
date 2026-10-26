package moe.rukamori.archivetune.shared.ui.screens.mediainfo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_copy
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_credits_written_by
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_description
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_dislikes
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_edit
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_likes
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_media_id
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_please_wait
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_solar_dislike
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_solar_eye
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_solar_hash
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_solar_heart
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_solar_music_note
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_solar_text
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_solar_users
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_song_artists
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_song_title
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_subscribers
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_tab_numbers
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_ti_duration
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_timer
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_unknown
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_views
import org.jetbrains.compose.resources.stringResource

/**
 * File-size text for the stream: the host's label when it supplies one (the Android formatter the
 * baseline used), otherwise the common binary-unit formatter. Null when no size is known.
 */
internal fun mediaInfoFileSizeText(state: MediaInfoUi): String? =
    state.fileSizeLabel?.takeIf { it.isNotBlank() }
        ?: formatReportedByteCount(state.format?.contentLengthBytes)

/**
 * Information tab: the overview rows (each copies its value), the writers card when lyric metadata
 * names them, and the description. The description waits for [MediaInfoUi.video] to load.
 */
@Composable
internal fun MediaInfoInformationTab(
    state: MediaInfoUi,
    actions: MediaInfoActions,
) {
    val unknownText = stringResource(Res.string.m9_media_info_unknown)
    val pleaseWaitText = stringResource(Res.string.m9_media_info_please_wait)
    val descriptionLabel = stringResource(Res.string.m9_media_info_description)
    val copyText = stringResource(Res.string.m9_media_info_copy)
    val songTitleLabel = stringResource(Res.string.m9_media_info_song_title)
    val songArtistsLabel = stringResource(Res.string.m9_media_info_song_artists)
    val durationLabel = stringResource(Res.string.m9_media_info_ti_duration)
    val mediaIdLabel = stringResource(Res.string.m9_media_info_media_id)
    val writtenByLabel = stringResource(Res.string.m9_media_info_credits_written_by)

    val song = state.song
    val video = state.video
    val songArtists = song?.artists?.takeIf { it.isNotEmpty() }?.joinToString()
    val songDurationSeconds = song?.durationSeconds?.takeIf { it > 0L }
    val overview =
        listOfNotNull(
            MediaInfoDetailRow(
                icon = Res.drawable.m9_media_info_solar_music_note,
                label = songTitleLabel,
                value = song?.title ?: video?.title ?: unknownText,
            ),
            MediaInfoDetailRow(
                icon = Res.drawable.m9_media_info_solar_users,
                label = songArtistsLabel,
                value = songArtists ?: video?.author ?: unknownText,
            ),
            songDurationSeconds?.let { seconds ->
                MediaInfoDetailRow(
                    icon = Res.drawable.m9_media_info_timer,
                    label = durationLabel,
                    value = formatDuration(seconds * 1000L, 0L),
                )
            },
            MediaInfoDetailRow(
                icon = Res.drawable.m9_media_info_solar_hash,
                label = mediaIdLabel,
                value = state.mediaId,
            ),
        )
    val credits =
        if (state.lyricsWriters.isNotBlank()) {
            listOf(
                MediaInfoDetailRow(
                    icon = Res.drawable.m9_media_info_edit,
                    label = writtenByLabel,
                    value = state.lyricsWriters,
                ),
            )
        } else {
            emptyList()
        }

    MediaInfoRowsCard(rows = overview, onCopy = actions.onCopy)
    if (credits.isNotEmpty()) {
        MediaInfoRowsCard(rows = credits, onCopy = actions.onCopy)
    }
    if (video == null) {
        MediaInfoPendingCard(title = descriptionLabel, message = pleaseWaitText)
    } else {
        val description = video.description?.takeIf { it.isNotBlank() }
        MediaInfoNarrativeCard(
            icon = Res.drawable.m9_media_info_solar_text,
            title = descriptionLabel,
            body = description ?: unknownText,
            copyContentDescription = copyText,
            onCopy = {
                if (description != null) actions.onCopy(description)
            },
        )
    }
}

/**
 * Numbers tab: subscribers, views, likes and dislikes, two per row. Shows a loading card until the
 * provider details arrive.
 */
@Composable
internal fun MediaInfoNumbersTab(state: MediaInfoUi) {
    val video = state.video
    if (video == null) {
        MediaInfoPendingCard(
            title = stringResource(Res.string.m9_media_info_tab_numbers),
            message = stringResource(Res.string.m9_media_info_please_wait),
        )
        return
    }
    val unknownText = stringResource(Res.string.m9_media_info_unknown)
    val metrics =
        listOf(
            MediaInfoMetricRow(
                icon = Res.drawable.m9_media_info_solar_users,
                label = stringResource(Res.string.m9_media_info_subscribers),
                value = video.subscribers ?: unknownText,
            ),
            MediaInfoMetricRow(
                icon = Res.drawable.m9_media_info_solar_eye,
                label = stringResource(Res.string.m9_media_info_views),
                value = video.viewCount?.let(::formatGroupedCount) ?: unknownText,
            ),
            MediaInfoMetricRow(
                icon = Res.drawable.m9_media_info_solar_heart,
                label = stringResource(Res.string.m9_media_info_likes),
                value = video.likeCount?.let(::formatGroupedCount) ?: unknownText,
            ),
            MediaInfoMetricRow(
                icon = Res.drawable.m9_media_info_solar_dislike,
                label = stringResource(Res.string.m9_media_info_dislikes),
                value = video.dislikeCount?.let(::formatGroupedCount) ?: unknownText,
            ),
        )
    metrics.chunked(2).forEach { rowMetrics ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            rowMetrics.forEach { metric ->
                MediaInfoMetricCard(
                    icon = metric.icon,
                    label = metric.label,
                    value = metric.value,
                    modifier = Modifier.weight(1f),
                )
            }
            if (rowMetrics.size == 1) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * A card of label/value rows. Each row copies its value when [onCopy] is supplied; a null
 * [onCopy] keeps the rows display-only.
 */
@Composable
internal fun MediaInfoRowsCard(
    rows: List<MediaInfoDetailRow>,
    onCopy: ((String) -> Unit)?,
) {
    MediaInfoCard {
        rows.forEachIndexed { index, item ->
            val value = item.value
            MediaInfoRow(
                icon = item.icon,
                label = item.label,
                value = value,
                showDivider = index != rows.lastIndex,
                onClick = if (onCopy != null) ({ onCopy(value) }) else null,
            )
        }
    }
}
