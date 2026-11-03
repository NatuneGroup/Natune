package moe.rukamori.archivetune.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphPage
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoutes
import moe.rukamori.archivetune.shared.ui.screens.DownloadActions
import moe.rukamori.archivetune.shared.ui.screens.DownloadEntryUi
import moe.rukamori.archivetune.shared.ui.screens.DownloadLibraryTab
import moe.rukamori.archivetune.shared.ui.screens.DownloadMediaType
import moe.rukamori.archivetune.shared.ui.screens.DownloadSectionUi
import moe.rukamori.archivetune.shared.ui.screens.DownloadsUi
import moe.rukamori.archivetune.shared.ui.screens.LocalScanUi
import moe.rukamori.archivetune.shared.ui.screens.LocalSongSort
import moe.rukamori.archivetune.shared.ui.screens.LocalSongsActions
import moe.rukamori.archivetune.shared.ui.screens.LocalSongsUi
import moe.rukamori.archivetune.shared.data.persistence.storedOccurrence

@Composable
internal fun desktopLocalSongsPage(
    graph: DesktopSessionGraph,
    menu: DesktopItemMenuState,
): ArchiveGraphPage {
    val owner = graph.localMedia
    var query by remember { mutableStateOf("") }
    val (sort, descending) = owner.sort()
    LaunchedEffect(owner) {
        // The baseline scans on first entry; rescanning on every mount matches its refresh.
        if (!owner.scannedOnce) owner.scan()
    }
    val state = LocalSongsUi(
        rows = owner.rows,
        query = query,
        sort = sort,
        descending = descending,
        loading = owner.scanning,
        failed = owner.failed,
        scan = LocalScanUi(
            permissionGranted = owner.permissionGranted,
            scanning = owner.scanning,
            failed = owner.failed,
            scanned = owner.scannedCount,
            removed = owner.removedCount,
            minimumDurationSeconds = owner.minimumDurationSeconds(),
            includedFolders = owner.includedFolders(),
            excludedFolders = owner.excludedFolders(),
        ),
    )
    val actions = LocalSongsActions(
        back = {},
        query = { query = it },
        sort = owner::setSort,
        requestPermission = {},
        scan = owner::scan,
        minimumDuration = owner::setMinimumDuration,
        pickFolder = { included, _ -> owner.pickFolder(included, "Choose a folder") },
        removeFolder = owner::removeFolder,
        play = { selected, visibleQueue -> owner.play(selected, visibleQueue) },
        retry = owner::scan,
        more = { occurrence -> menu.open(occurrence) },
    )
    return ArchiveGraphPage.LocalSongs(state, actions)
}

@Composable
internal fun desktopDownloadsPage(graph: DesktopSessionGraph): ArchiveGraphPage {
    val owner = graph.downloads
    var tab by remember { mutableStateOf(DownloadLibraryTab.DOWNLOADED) }
    var query by remember { mutableStateOf("") }
    val rows by owner.storedRows().collectAsState(initial = emptyList())
    val transfers by owner.observe().collectAsState(initial = emptyList())
    val itemsById = rows.associate { it.mediaId to it.storedOccurrence().item }
    fun entryOf(ref: ContentRef, paused: Boolean, failed: Boolean, progress: Float?): DownloadEntryUi? {
        val item = itemsById[ref.id] ?: return null
        return DownloadEntryUi(
            id = ref.id,
            item = item,
            songIds = listOf(ref.id),
            progress = progress,
            paused = paused,
            failed = failed,
        )
    }
    val done = transfers.filter { it.phase == moe.rukamori.archivetune.shared.domain.repository.DownloadPhase.COMPLETED }
        .mapNotNull { entryOf(it.ref, paused = false, failed = false, progress = null) }
    val active = transfers.filter {
        it.phase != moe.rukamori.archivetune.shared.domain.repository.DownloadPhase.COMPLETED
    }.mapNotNull { transfer ->
        entryOf(
            transfer.ref,
            paused = transfer.phase == moe.rukamori.archivetune.shared.domain.repository.DownloadPhase.STOPPED,
            failed = transfer.phase == moe.rukamori.archivetune.shared.domain.repository.DownloadPhase.FAILED,
            progress = null,
        )
    }
    val busy = transfers
        .filter {
            it.phase == moe.rukamori.archivetune.shared.domain.repository.DownloadPhase.QUEUED ||
                it.phase == moe.rukamori.archivetune.shared.domain.repository.DownloadPhase.DOWNLOADING ||
                it.phase == moe.rukamori.archivetune.shared.domain.repository.DownloadPhase.REMOVING
        }
        .map { it.ref.id }
        .toSet()
    val state = DownloadsUi(
        tab = tab,
        query = query,
        downloaded = if (done.isEmpty()) emptyList() else listOf(DownloadSectionUi(DownloadMediaType.SONG, done)),
        progress = if (active.isEmpty()) emptyList() else listOf(DownloadSectionUi(DownloadMediaType.SONG, active)),
        loading = false,
        failed = false,
        busySongIds = busy,
    )
    fun run(action: suspend (String) -> Unit, ids: List<String>) {
        ids.forEach { id ->
            graph.scope.launch {
                try {
                    action("youtube:$id".let { id })
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    graph.report("the download action failed: ${failure.javaClass.simpleName}")
                }
            }
        }
    }
    val actions = DownloadActions(
        tab = { tab = it },
        query = { query = it },
        back = {},
        retry = {},
        pause = { ids -> run(owner::stop, ids.map { downloadIdOf(it, transfers) }) },
        resume = { ids -> run(owner::resume, ids.map { downloadIdOf(it, transfers) }) },
        remove = { ids -> run(owner::remove, ids.map { downloadIdOf(it, transfers) }) },
        open = { entry ->
            val item = itemsById[entry.id] ?: return@DownloadActions
            val playable = done.mapNotNull { itemsById[it.id] }
                .map { ContentOccurrence("download:${it.ref.provider}:${it.ref.id}", it) }
            val start = playable.firstOrNull { it.item.ref == item.ref } ?: return@DownloadActions
            graph.scope.launch {
                try {
                    graph.queue.replace(playable, start.occurrenceId, playWhenReady = true, staysFinite = true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    graph.report("the download was not played: ${failure.javaClass.simpleName}")
                }
            }
        },
    )
    return ArchiveGraphPage.Downloads(state, actions)
}

/** A song id names a download by its provider-qualified id, which the rows carry. */
private fun downloadIdOf(
    mediaId: String,
    transfers: List<moe.rukamori.archivetune.shared.domain.repository.ContentDownload>,
): String = transfers.firstOrNull { it.ref.id == mediaId }?.id ?: "youtube:$mediaId"

@Composable
internal fun desktopTrackInfoPage(
    graph: DesktopSessionGraph,
    trackId: String,
): ArchiveGraphPage {
    var state by remember(trackId) { mutableStateOf<moe.rukamori.archivetune.shared.ui.screens.mediainfo.TrackInfoUi?>(null) }
    LaunchedEffect(trackId) {
        try {
            state = graph.mediaInfo.trackInfo(trackId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            graph.report("the track info was not loaded: ${failure.javaClass.simpleName}")
        }
    }
    return ArchiveGraphPage.TrackInfo(
        state = state ?: moe.rukamori.archivetune.shared.ui.screens.mediainfo.TrackInfoUi(trackId = trackId),
        artwork = { url, modifier -> DesktopRemoteArtwork(url, graph.artworkLoader, modifier) },
    )
}

@Composable
internal fun desktopMediaInfoPage(
    graph: DesktopSessionGraph,
    mediaId: String,
): ArchiveGraphPage {
    val queueState = graph.queue.state.value
    val currentId = queueState.entries.firstOrNull { it.occurrenceId == queueState.currentOccurrenceId }?.item?.ref?.id
    var state by remember(mediaId, currentId) {
        mutableStateOf<moe.rukamori.archivetune.shared.ui.screens.mediainfo.MediaInfoUi?>(null)
    }
    LaunchedEffect(mediaId, currentId) {
        try {
            state = graph.mediaInfo.mediaInfo(mediaId, isLive = currentId == mediaId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            graph.report("the media info was not loaded: ${failure.javaClass.simpleName}")
        }
    }
    return ArchiveGraphPage.MediaInfo(
        state = state ?: moe.rukamori.archivetune.shared.ui.screens.mediainfo.MediaInfoUi(mediaId = mediaId),
        actions = moe.rukamori.archivetune.shared.ui.screens.mediainfo.MediaInfoActions(
            onCopy = { text -> copyToDesktopClipboard(text) },
            onShare = { text -> copyToDesktopClipboard(text) },
        ),
        artwork = { url, modifier -> DesktopRemoteArtwork(url, graph.artworkLoader, modifier) },
    )
}

