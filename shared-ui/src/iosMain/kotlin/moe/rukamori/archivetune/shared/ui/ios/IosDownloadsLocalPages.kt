package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.ui.host.HostDownloadsInputs
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphPage
import moe.rukamori.archivetune.shared.ui.screens.DownloadActions
import moe.rukamori.archivetune.shared.ui.screens.DownloadLibraryTab
import moe.rukamori.archivetune.shared.ui.screens.LocalScanUi
import moe.rukamori.archivetune.shared.ui.screens.LocalSongSort
import moe.rukamori.archivetune.shared.ui.screens.LocalSongsActions
import moe.rukamori.archivetune.shared.ui.screens.LocalSongsUi

/** The occurrence id prefix a downloaded song plays as; the rest is the download's provider-qualified id. */
private const val DOWNLOAD_OCCURRENCE_PREFIX = "download:"

/**
 * The downloads page over this host's owner: the live queue and the committed rows are one snapshot, and every
 * command addresses a download by the provider-qualified id the owner minted. Opening a downloaded song replaces the
 * queue with the downloaded songs and starts the one tapped, so an offline list plays without resolving a source.
 */
@Composable
internal fun iosDownloadsPage(graph: IosSessionGraph, back: () -> Unit): ArchiveGraphPage.Downloads {
    // One flow per session: a flow built during composition would restart its collection on every recomposition.
    val snapshots = remember(graph) { graph.downloads.inputs() }
    val inputs by snapshots.collectAsState(HostDownloadsInputs(emptyList(), emptyList()))
    var tab by remember { mutableStateOf(DownloadLibraryTab.DOWNLOADED) }
    var query by remember { mutableStateOf("") }
    fun run(ids: List<String>, command: suspend (String) -> Unit) {
        ids.forEach { id -> graph.downloads.run { command(id) } }
    }
    val actions = DownloadActions(
        tab = { tab = it },
        query = { query = it },
        back = back,
        // A queued or stopped transfer is resumed by its own row's button; the page has no batch retry here.
        retry = {},
        pause = { ids -> run(ids, graph.downloads::stop) },
        resume = { ids -> run(ids, graph.downloads::resume) },
        remove = { ids -> run(ids, graph.downloads::remove) },
        open = { entry ->
            val occurrences = inputs.downloadedOccurrences()
            val start = occurrences.firstOrNull { it.occurrenceId == DOWNLOAD_OCCURRENCE_PREFIX + entry.id }
            if (start != null) {
                playOccurrences(graph, occurrences, start.occurrenceId, "the downloaded song was not played")
            }
        },
    )
    return ArchiveGraphPage.Downloads(inputs.page(tab, query), actions)
}

/**
 * The local songs page: the imported files the library lists, the picker that adds another one, the rescan, the
 * minimum-duration rule and playback. Folder include/exclude rules are not offered, because this host has one
 * imported folder and no media store to filter by folder.
 */
@Composable
internal fun iosLocalSongsPage(
    graph: IosSessionGraph,
    menu: IosItemMenuState,
    back: () -> Unit,
): ArchiveGraphPage.LocalSongs {
    val owner = graph.localMedia
    val rows by owner.rows.collectAsState(emptyList())
    val scanning by owner.scanning.collectAsState()
    val summary by owner.summary.collectAsState()
    val minimum by owner.minimumDurationSeconds.collectAsState()
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(LocalSongSort.MODIFIED) }
    var descending by remember { mutableStateOf(true) }
    val scan = LocalScanUi(
        permissionGranted = true,
        scanning = scanning,
        scanned = summary?.scanned,
        removed = summary?.removed ?: 0,
        minimumDurationSeconds = minimum,
    )
    val actions = LocalSongsActions(
        back = back,
        query = { query = it },
        sort = { type, descendingOrder -> sort = type; descending = descendingOrder },
        // The Files picker is this host's permission, so the panel never asks for a media-store grant.
        requestPermission = owner::rescan,
        scan = owner::rescan,
        minimumDuration = owner::setMinimumDuration,
        // This host imports into one folder and has no media store to filter, so no folder rules are offered.
        pickFolder = null,
        removeFolder = null,
        play = { selected, visible ->
            playOccurrences(graph, visible, selected.occurrenceId, "the local song was not played")
        },
        retry = owner::rescan,
        more = { occurrence -> menu.open(occurrence) },
        importFiles = owner::importFiles,
    )
    return ArchiveGraphPage.LocalSongs(
        LocalSongsUi(rows = rows, query = query, sort = sort, descending = descending, scan = scan),
        actions,
    )
}

/** Replaces the queue with [occurrences] and starts [startOccurrenceId]; a refusal is reported, never swallowed. */
private fun playOccurrences(
    graph: IosSessionGraph,
    occurrences: List<ContentOccurrence>,
    startOccurrenceId: String,
    refusal: String,
) {
    graph.scope.launchCommand(graph, refusal) {
        graph.queue.replace(occurrences, startOccurrenceId, playWhenReady = true, staysFinite = true)
    }
}

private fun CoroutineScope.launchCommand(graph: IosSessionGraph, refusal: String, command: suspend () -> Unit) =
    launch {
        try {
            command()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            graph.report(refusal + ": " + failure::class.simpleName)
        }
    }
