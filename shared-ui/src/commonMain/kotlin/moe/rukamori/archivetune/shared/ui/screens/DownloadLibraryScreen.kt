package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.songmetadata.withSongMetadataOverrides
import moe.rukamori.archivetune.shared.ui.songmetadata.LocalSongMetadataOverrides
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.navigation.LocalShellContentPadding
import org.jetbrains.compose.resources.stringResource

enum class DownloadLibraryTab { DOWNLOADED, PROGRESS }
enum class DownloadMediaType { PLAYLIST, ALBUM, SONG }
/** Presentation snapshots from the real download owner; IDs are opaque and never inferred from URLs. */
data class DownloadEntryUi(
    val id: String, val item: ContentItem, val songIds: List<String>, val progress: Float? = null,
    val paused: Boolean = false, val failed: Boolean = false, val bytesPerSecond: Long = 0,
)
data class DownloadSectionUi(val type: DownloadMediaType, val entries: List<DownloadEntryUi>)
data class DownloadsUi(
    val tab: DownloadLibraryTab = DownloadLibraryTab.DOWNLOADED, val query: String = "",
    val downloaded: List<DownloadSectionUi> = emptyList(), val progress: List<DownloadSectionUi> = emptyList(),
    val loading: Boolean = false, val failed: Boolean = false, val busySongIds: Set<String> = emptySet(),
)
class DownloadActions(
    val tab: (DownloadLibraryTab) -> Unit, val query: (String) -> Unit, val back: () -> Unit, val retry: () -> Unit,
    val pause: (List<String>) -> Unit, val resume: (List<String>) -> Unit, val remove: (List<String>) -> Unit,
    val open: (DownloadEntryUi) -> Unit,
)

fun visibleDownloadSections(state: DownloadsUi): List<DownloadSectionUi> =
    (if (state.tab == DownloadLibraryTab.DOWNLOADED) state.downloaded else state.progress).map { section ->
        section.copy(entries = section.entries.filter {
            state.query.isBlank() || it.item.title.contains(state.query.trim(), true) ||
                it.item.subtitle.contains(state.query.trim(), true)
        })
    }.filter { it.entries.isNotEmpty() }

@Composable
fun DownloadLibraryScreen(
    state: DownloadsUi, actions: DownloadActions, artwork: ContentArtwork,
    modifier: Modifier = Modifier, contentPadding: PaddingValues = LocalShellContentPadding.current,
) {
    val edits = LocalSongMetadataOverrides.current
    fun project(sections: List<DownloadSectionUi>) = sections.map { section ->
        section.copy(entries = section.entries.map { it.copy(item = it.item.withSongMetadataOverrides(edits)) })
    }
    val sections = visibleDownloadSections(state.copy(downloaded = project(state.downloaded), progress = project(state.progress)))
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ManagementHeader(stringResource(Res.string.m4_downloads), actions.back)
        OutlinedTextField(state.query, actions.query, Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            label = { Text(stringResource(Res.string.m4_search_query)) }, singleLine = true)
        Row(Modifier.fillMaxWidth()) {
            DownloadLibraryTab.entries.forEach { tab ->
                FilterChip(state.tab == tab, { actions.tab(tab) }, modifier = Modifier.testTag("downloads-tab-${tab.name}"),
                    label = { Text(stringResource(if (tab == DownloadLibraryTab.DOWNLOADED)
                        Res.string.m4_downloaded else Res.string.m4_in_progress)) })
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = contentPadding) {
            item("feedback") { LoadFeedback(state.loading, state.failed, sections.isEmpty(), actions.retry) }
            sections.forEach { section ->
                val ids = section.entries.flatMap { it.songIds }.distinct()
                val paused = section.entries.all { it.paused }
                val enabled = ids.isNotEmpty() && ids.none { it in state.busySongIds }
                item("section-${section.type}") {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(stringResource(when (section.type) {
                            DownloadMediaType.PLAYLIST -> Res.string.m4_playlists
                            DownloadMediaType.ALBUM -> Res.string.m4_albums
                            DownloadMediaType.SONG -> Res.string.m4_songs
                        }), style = MaterialTheme.typography.titleLarge)
                        Row {
                            if (state.tab == DownloadLibraryTab.PROGRESS) {
                                TextButton({ if (paused) actions.resume(ids) else actions.pause(ids) }, enabled = enabled,
                                    modifier = Modifier.testTag("download-section-${if (paused) "resume" else "pause"}-${section.type}")) {
                                    Text(stringResource(if (paused) Res.string.m4_resume else Res.string.m4_pause))
                                }
                            }
                            TextButton({ actions.remove(ids) }, enabled = enabled) { Text(stringResource(Res.string.m4_remove)) }
                        }
                    }
                }
                items(section.entries, key = { "entry-${section.type}-${it.id}" }) { entry ->
                    val entryEnabled = entry.songIds.isNotEmpty() && entry.songIds.none { it in state.busySongIds }
                    ListItem(
                        headlineContent = { Text(entry.item.title) },
                        leadingContent = { artwork(entry.item, Modifier.size(56.dp)) },
                        supportingContent = {
                            Column {
                                Text(entry.item.subtitle)
                                if (state.tab == DownloadLibraryTab.PROGRESS) {
                                    val fraction = entry.progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
                                    fraction?.let { LinearProgressIndicator(progress = { it }, Modifier.fillMaxWidth()) }
                                    Text(stringResource(when {
                                        entry.failed -> Res.string.m4_download_failed
                                        entry.paused -> Res.string.m4_paused
                                        else -> Res.string.m4_downloading
                                    }))
                                    Text(stringResource(Res.string.m4_download_speed, entry.bytesPerSecond.coerceAtLeast(0)))
                                }
                            }
                        },
                        trailingContent = {
                            Column {
                                if (state.tab == DownloadLibraryTab.PROGRESS) {
                                    TextButton({ if (entry.paused) actions.resume(entry.songIds) else actions.pause(entry.songIds) },
                                        enabled = entryEnabled) {
                                        Text(stringResource(if (entry.paused) Res.string.m4_resume else Res.string.m4_pause))
                                    }
                                }
                                TextButton({ actions.remove(entry.songIds) }, enabled = entryEnabled,
                                    modifier = Modifier.testTag("download-remove-${entry.id}")) {
                                    Text(stringResource(Res.string.m4_remove))
                                }
                            }
                        },
                        modifier = Modifier.clickable { actions.open(entry) },
                    )
                }
            }
        }
    }
}
