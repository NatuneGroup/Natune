@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.shared.ui.menu.playerqueue

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The baseline waited this long after the last keystroke before it queried any provider. */
private const val SOURCE_SEARCH_DEBOUNCE_MS = 350L

/** Shorter queries are not searched, matching the baseline's two-character minimum. */
private const val SOURCE_SEARCH_MIN_QUERY_LENGTH = 2

/**
 * The song source chooser the Source tile opens over the menu. The list picks the automatic route or one provider.
 * Search mode queries the providers through [PlayerQueueMenuActions.searchSongSources]: a YouTube row seeds a radio, and
 * any other row pins its source for the song. Every pick closes this dialog only; the menu stays open, as the baseline's
 * source chooser did.
 */
@Composable
internal fun PlayerQueueSongSourceDialog(
    track: PlayerQueueTrack,
    state: PlayerQueueMenuState,
    actions: PlayerQueueMenuActions,
    icon: PlayerQueueMenuIconSlot,
    artwork: PlayerQueueArtworkSlot,
    onDismiss: () -> Unit,
) {
    var searchMode by rememberSaveable { mutableStateOf(false) }
    var searchField by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    val searchQuery = searchField.text
    // Saved by name, so the provider filter survives a process restore as the baseline's did. The enum is rebuilt from it.
    var sourceFilterName by rememberSaveable { mutableStateOf<String?>(null) }
    val sourceFilter = AudioSourceType.entries.firstOrNull { it.name == sourceFilterName }

    val backendMissing = sourceFilter != null && sourceFilter !in state.searchableSources
    val includeAmazon = state.amazonSearchEnabled && (sourceFilter == null || sourceFilter == AudioSourceType.AMAZON)

    // The baseline refreshed the song's resolved sources each time the chooser opened.
    LaunchedEffect(track.mediaId) {
        actions.refreshSongSources(track)
    }

    val results by produceState<List<PlayerSourceSearchResult>>(
        emptyList(),
        searchMode,
        searchQuery,
        sourceFilter,
        backendMissing,
        includeAmazon,
    ) {
        if (!searchMode || searchQuery.length < SOURCE_SEARCH_MIN_QUERY_LENGTH || backendMissing) {
            value = emptyList()
            return@produceState
        }
        delay(SOURCE_SEARCH_DEBOUNCE_MS)
        value =
            try {
                actions.searchSongSources(searchQuery, sourceFilter, includeAmazon)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                emptyList()
            }
    }

    PlayerQueueDialog(
        onDismiss = onDismiss,
        buttons = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(Res.string.m5pq_cancel))
            }
        },
    ) {
        Column(modifier = Modifier.padding(top = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.m5pq_play_from),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                val searchLabel = stringResource(Res.string.m5pq_download_source_search)
                IconButton(
                    onClick = {
                        searchMode = !searchMode
                        if (searchMode) {
                            searchField = sourceSearchSeed(track).let { TextFieldValue(it, TextRange(it.length)) }
                        } else {
                            searchField = TextFieldValue("")
                            sourceFilterName = null
                        }
                    },
                    modifier = Modifier.semantics { contentDescription = searchLabel },
                ) {
                    icon(
                        PlayerQueueMenuIcon.SEARCH,
                        if (searchMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        Modifier.size(24.dp),
                    )
                }
            }

            if (searchMode) {
                OutlinedTextField(
                    value = searchField,
                    onValueChange = { searchField = it },
                    placeholder = { Text(stringResource(Res.string.m5pq_download_source_search_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                )
                SourceFilterChipRow(
                    current = sourceFilter,
                    onSelect = { sourceFilterName = it?.name },
                    icon = icon,
                )
                when {
                    backendMissing -> SourceSearchMessage(stringResource(Res.string.m5pq_source_search_no_backend))
                    searchQuery.length < SOURCE_SEARCH_MIN_QUERY_LENGTH ->
                        SourceSearchMessage(stringResource(Res.string.m5pq_source_search_no_results))
                    results.isEmpty() -> SourceSearchMessage(stringResource(Res.string.m5pq_source_search_no_results))
                    else -> {
                        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                            items(results, key = { result -> "${result.source.name}:${result.trackId}" }) { result ->
                                SourceSearchResultRow(
                                    result = result,
                                    icon = icon,
                                    artwork = artwork,
                                    onClick = {
                                        if (result.isYouTubeSong) {
                                            actions.playYouTubeSearchResult(track, result)
                                        } else if (result.trackId.isNotBlank()) {
                                            actions.chooseSongSourceSearchResult(track, result.toOverrideRequest())
                                        }
                                        onDismiss()
                                    },
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            }
                        }
                    }
                }
            } else {
                // "Automatic" clears the override so the song follows the global preferred-source order.
                SongSourceRow(
                    mark = PlayerQueueMenuIcon.TUNE,
                    icon = icon,
                    label = stringResource(Res.string.m5pq_play_from_automatic),
                    checked = state.selectedSource == null,
                    onClick = {
                        actions.chooseSongSource(track, null)
                        onDismiss()
                    },
                )
                state.sourceChoices.forEach { source ->
                    SongSourceRow(
                        mark = source.sourceIcon(),
                        icon = icon,
                        label = stringResource(source.sourceLabel()),
                        checked = state.selectedSource == source,
                        onClick = {
                            actions.chooseSongSource(track, source)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

/** One filter chip: the provider it selects, its label, and the mark shown while it is not selected. */
private data class SourceFilterChip(
    val source: AudioSourceType?,
    val label: StringResource,
    val mark: PlayerQueueMenuIcon,
)

/** The chip order and marks match the baseline's search filter row. A null source is the All chip. */
private fun sourceFilterChips(): List<SourceFilterChip> =
    listOf(
        SourceFilterChip(null, Res.string.m5pq_source_search_filter_all, PlayerQueueMenuIcon.SEARCH),
        SourceFilterChip(AudioSourceType.TIDAL, Res.string.m5pq_source_tidal, PlayerQueueMenuIcon.PROVIDER_TIDAL),
        SourceFilterChip(AudioSourceType.QOBUZ, Res.string.m5pq_source_qobuz, PlayerQueueMenuIcon.PROVIDER_QOBUZ),
        SourceFilterChip(
            AudioSourceType.QOBUZ_BACKUP,
            Res.string.m5pq_source_qobuz_backup,
            PlayerQueueMenuIcon.PROVIDER_QOBUZ,
        ),
        SourceFilterChip(AudioSourceType.DEEZER, Res.string.m5pq_source_deezer, PlayerQueueMenuIcon.PROVIDER_DEEZER),
        SourceFilterChip(
            AudioSourceType.APPLE,
            Res.string.m5pq_source_apple_music,
            PlayerQueueMenuIcon.PROVIDER_GENERIC,
        ),
        SourceFilterChip(AudioSourceType.AMAZON, Res.string.m5pq_source_amazon, PlayerQueueMenuIcon.PROVIDER_GENERIC),
        SourceFilterChip(AudioSourceType.QQ, Res.string.m5pq_source_qq_music, PlayerQueueMenuIcon.PROVIDER_GENERIC),
        SourceFilterChip(AudioSourceType.SPOTIFY, Res.string.m5pq_source_spotify_stream, PlayerQueueMenuIcon.SPOTIFY),
        SourceFilterChip(
            AudioSourceType.JIOSAAVN,
            Res.string.m5pq_source_jiosaavn,
            PlayerQueueMenuIcon.PROVIDER_JIOSAAVN,
        ),
        SourceFilterChip(AudioSourceType.YOUTUBE, Res.string.m5pq_source_youtube, PlayerQueueMenuIcon.PLAY),
    )

/**
 * A horizontally scrolling row of filter chips. The selected chip shows the done mark in place of its provider mark, as
 * the baseline's chip row did.
 */
@Composable
private fun SourceFilterChipRow(
    current: AudioSourceType?,
    onSelect: (AudioSourceType?) -> Unit,
    icon: PlayerQueueMenuIconSlot,
) {
    val chips = remember { sourceFilterChips() }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .horizontalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.width(12.dp))
        chips.forEach { chip ->
            val selected = current == chip.source
            FilterChip(
                selected = selected,
                onClick = { onSelect(chip.source) },
                label = { Text(stringResource(chip.label)) },
                leadingIcon = {
                    icon(
                        if (selected) PlayerQueueMenuIcon.DONE else chip.mark,
                        LocalContentColor.current,
                        Modifier.size(FilterChipDefaults.IconSize),
                    )
                },
                shape = RoundedCornerShape(16.dp),
                border = null,
                colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
private fun SourceSearchMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
    )
}

/** A list row for the automatic route or one provider, with a radio that mirrors the selection. */
@Composable
private fun SongSourceRow(
    mark: PlayerQueueMenuIcon,
    icon: PlayerQueueMenuIconSlot,
    label: String,
    checked: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = checked, onClick = onClick)
        icon(mark, LocalContentColor.current, Modifier.padding(horizontal = 12.dp).size(24.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** One search result: artwork, title, artist and duration, the quality badge when declared, and the provider mark. */
@Composable
private fun SourceSearchResultRow(
    result: PlayerSourceSearchResult,
    icon: PlayerQueueMenuIconSlot,
    artwork: PlayerQueueArtworkSlot,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val thumbnailUrl = result.thumbnailUrl
        if (!thumbnailUrl.isNullOrBlank()) {
            artwork(thumbnailUrl, Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)))
        } else {
            Box(
                modifier =
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                icon(PlayerQueueMenuIcon.MUSIC_NOTE, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = result.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val subtitle = resultSubtitle(result)
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val qualityLabel = result.quality.badgeLabel()
        if (qualityLabel != null) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    text = qualityLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        icon(result.source.sourceIcon(), MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(16.dp))
    }
}

/**
 * Entering search opens on the song's title and artists, so the providers answer without the user typing first.
 * Blank parts are dropped so a missing artist cannot leave a stray space in the query.
 */
private fun sourceSearchSeed(track: PlayerQueueTrack): String =
    (listOf(track.title) + track.artists.map { it.name })
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")

/** The artist line, then the duration when the provider reports one. The search payload may omit it. */
private fun resultSubtitle(result: PlayerSourceSearchResult): String =
    buildString {
        append(result.artist)
        result.durationMs?.let { ms -> append(" · ").append(formatPlayerQueueMinutesSeconds(ms)) }
    }

/** The badge text for a declared quality. Lossless is declared by the provider and never verified here. */
@Composable
private fun PlayerSourceQuality.badgeLabel(): String? =
    when (this) {
        PlayerSourceQuality.NONE -> null
        PlayerSourceQuality.AAC_256 -> stringResource(Res.string.m5pq_quality_badge_aac)
        PlayerSourceQuality.AAC_320 -> stringResource(Res.string.m5pq_quality_badge_saavn)
        PlayerSourceQuality.MP3_320 -> stringResource(Res.string.m5pq_quality_badge_mp3)
        PlayerSourceQuality.LOSSLESS_DECLARED ->
            stringResource(
                Res.string.m5pq_quality_declared_unverified,
                stringResource(Res.string.m5pq_quality_badge_lossless),
            )
    }

/**
 * Maps a picked search row to the override the host persists. Amazon keeps its row metadata, Qobuz keeps its catalogue
 * id, Qobuz Backup keeps its mirror video id, and every other source is pinned by source alone.
 */
private fun PlayerSourceSearchResult.toOverrideRequest(): PlayerSourceOverrideRequest =
    when (source) {
        AudioSourceType.AMAZON -> PlayerSourceOverrideRequest.AmazonPick(title, artist, album, durationMs)
        AudioSourceType.QOBUZ -> PlayerSourceOverrideRequest.QobuzTrack(trackId)
        AudioSourceType.QOBUZ_BACKUP -> PlayerSourceOverrideRequest.QobuzBackupVideo(trackId)
        else -> PlayerSourceOverrideRequest.Plain(source)
    }

private fun AudioSourceType.sourceLabel(): StringResource =
    when (this) {
        AudioSourceType.TIDAL -> Res.string.m5pq_source_tidal
        AudioSourceType.QOBUZ -> Res.string.m5pq_source_qobuz
        AudioSourceType.QOBUZ_BACKUP -> Res.string.m5pq_source_qobuz_backup
        AudioSourceType.DEEZER -> Res.string.m5pq_source_deezer
        AudioSourceType.APPLE -> Res.string.m5pq_source_apple_music
        AudioSourceType.AMAZON -> Res.string.m5pq_source_amazon
        AudioSourceType.MODULES -> Res.string.source_modules
        AudioSourceType.QQ -> Res.string.m5pq_source_qq_music
        AudioSourceType.SPOTIFY -> Res.string.m5pq_source_spotify_stream
        AudioSourceType.JIOSAAVN -> Res.string.m5pq_source_jiosaavn
        AudioSourceType.YOUTUBE -> Res.string.m5pq_source_youtube
    }

/**
 * The provider mark. Apple, Amazon and QQ share the generic mark, as the baseline did, because no dedicated asset
 * ships for them.
 */
private fun AudioSourceType.sourceIcon(): PlayerQueueMenuIcon =
    when (this) {
        AudioSourceType.TIDAL -> PlayerQueueMenuIcon.PROVIDER_TIDAL
        AudioSourceType.QOBUZ, AudioSourceType.QOBUZ_BACKUP -> PlayerQueueMenuIcon.PROVIDER_QOBUZ
        AudioSourceType.DEEZER -> PlayerQueueMenuIcon.PROVIDER_DEEZER
        AudioSourceType.APPLE, AudioSourceType.AMAZON, AudioSourceType.QQ, AudioSourceType.MODULES -> PlayerQueueMenuIcon.PROVIDER_GENERIC
        AudioSourceType.SPOTIFY -> PlayerQueueMenuIcon.SPOTIFY
        AudioSourceType.JIOSAAVN -> PlayerQueueMenuIcon.PROVIDER_JIOSAAVN
        AudioSourceType.YOUTUBE -> PlayerQueueMenuIcon.PLAY
    }
