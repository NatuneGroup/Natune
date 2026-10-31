package moe.rukamori.archivetune.shared.ui.ios

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.data.localmedia.IosLocalMediaSource
import moe.rukamori.archivetune.shared.data.localmedia.LocalMediaScanOwner
import moe.rukamori.archivetune.shared.data.persistence.RoomLocalMediaLibrary
import moe.rukamori.archivetune.shared.data.platform.ios.IosMediaImporter
import moe.rukamori.archivetune.shared.domain.localmedia.LocalMediaScanConfig
import moe.rukamori.archivetune.shared.domain.localmedia.LocalScanSummary
import moe.rukamori.archivetune.shared.domain.localmedia.LocalTrackRecord
import moe.rukamori.archivetune.shared.ui.IosHost
import moe.rukamori.archivetune.shared.ui.host.localSongRows
import moe.rukamori.archivetune.shared.ui.screens.LocalSongUi
import platform.Foundation.NSFileManager

/**
 * The `local songs` owner on this host. Its one folder is the app's imported-media directory: audio the user picks
 * through the Files picker is copied into it, and every scan walks exactly that tree with the shared scan pipeline,
 * so `local` songs join the same portable library the rest of the app reads.
 *
 * The scan rules are the shared ones; only the minimum duration is offered here, because the folder include/exclude
 * rules describe an Android media store this host does not have. Permission is never requested: the picker is the
 * permission, so the page reports it granted and its primary button rescans.
 */
internal class IosLocalMediaOwner(
    private val host: IosHost,
    private val importer: IosMediaImporter,
    private val library: RoomLocalMediaLibrary,
    source: IosLocalMediaSource,
    scope: CoroutineScope,
    private val report: (String) -> Unit,
) {
    /** The owner's own job, so [close] stops a running scan and waits for it before the graph closes the database. */
    private val ownerJob = SupervisorJob(scope.coroutineContext[Job])
    private val ownerScope = CoroutineScope(scope.coroutineContext + ownerJob)
    private val minimumDuration = MutableStateFlow(0)
    private val records = MutableStateFlow<List<LocalTrackRecord>>(emptyList())
    private val paths = MutableStateFlow<Map<String, String>>(emptyMap())

    private val scanOwner = LocalMediaScanOwner(
        source = source,
        library = library,
        config = { LocalMediaScanConfig(minimumDurationSeconds = minimumDuration.value) },
        scope = ownerScope,
    )

    val scanning: StateFlow<Boolean> = scanOwner.scanning
    val summary: StateFlow<LocalScanSummary?> = scanOwner.summary

    /** The minimum duration the page's filter offers, the one scan rule this host exposes. */
    val minimumDurationSeconds: StateFlow<Int> = minimumDuration

    /** The library's `local` songs joined to the scan records for the album title and the file time. */
    val rows: Flow<List<LocalSongUi>> = combine(scanOwner.observeSongs(), records) { items, scanned ->
        localSongRows(items, scanned)
    }

    /** Publishes the last scan's records, so the rows and the file lookup carry what the library items do not. */
    suspend fun start() {
        try {
            publish(library.previousScan())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            report("the imported songs were not read: ${failure::class.simpleName}")
        }
    }

    /** The file of [mediaId], for the playback locator. A file the user removed outside the app names no path. */
    fun pathOf(mediaId: String): String? =
        paths.value[mediaId]?.takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }

    /** Presents the Files picker and rescans once a file was imported. A cancelled picker changes nothing. */
    fun importFiles() = launchCommand {
        val imported = importer.pick(host.viewController)
        if (imported == null) return@launchCommand
        scanNow()
        report("imported the picked file " + imported.substringAfterLast('/'))
    }

    fun rescan() = launchCommand { scanNow() }

    fun setMinimumDuration(seconds: Int) {
        minimumDuration.value = seconds.coerceAtLeast(0)
    }

    /** Runs one scan and republishes the records it committed. Failure is reported once, by [launchCommand]. */
    private suspend fun scanNow() {
        scanOwner.scan()
        publish(library.previousScan())
    }

    /** Keeps the records and the id-to-file map of one scan result together, so a path always has its record. */
    private fun publish(scanned: List<LocalTrackRecord>) {
        records.value = scanned
        paths.value = scanned.associate { it.id to it.path }
    }

    /** Stops a running scan or import and waits for it to unwind; the graph closes the database only after this. */
    suspend fun close() = ownerJob.cancelAndJoin()

    private fun launchCommand(action: suspend () -> Unit) {
        if (scanOwner.scanning.value) return
        ownerScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report("the local songs command failed: ${failure::class.simpleName}")
            }
        }
    }
}
