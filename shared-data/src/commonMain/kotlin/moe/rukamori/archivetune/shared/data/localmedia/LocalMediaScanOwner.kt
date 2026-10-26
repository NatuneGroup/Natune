package moe.rukamori.archivetune.shared.data.localmedia

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import moe.rukamori.archivetune.shared.domain.localmedia.*
import moe.rukamori.archivetune.shared.domain.mediainfo.MediaHeaderParser

/**
 * The scan pipeline, the portable form of the retained `LocalSongScanner`: enumerate the host's candidates, drop
 * what the config rules out, read tags of everything that changed since the last scan (same path + modified + size
 * keeps the stored artwork, exactly like `keptArtwork`), normalize names and artists, extract embedded artwork and
 * lyrics, then join in one store transaction that deletes vanished rows. Files are read [EXTRACTION_PARALLELISM]
 * at a time.
 */
class LocalMediaScanOwner(
    private val source: LocalMediaSource,
    private val library: LocalMediaLibrary,
    private val config: suspend () -> LocalMediaScanConfig,
    private val tags: LocalTagReader = LocalTagReader,
    scope: CoroutineScope,
) {
    private val ownerScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val gate = Mutex()
    private val scanningState = MutableStateFlow(false)
    private val lastSummary = MutableStateFlow<LocalScanSummary?>(null)

    val scanning: StateFlow<Boolean> = scanningState.asStateFlow()
    val summary: StateFlow<LocalScanSummary?> = lastSummary.asStateFlow()

    fun observeSongs() = library.observeLocalSongs()

    fun requestScan() {
        if (scanningState.value) return
        ownerScope.launch(Dispatchers.Default) { scan() }
    }

    suspend fun scan(): LocalScanSummary = gate.withLock {
        scanningState.value = true
        try {
            val rules = config()
            val previous = library.previousScan()
            val version = library.extractionVersion()
            val versionChanged = version != EXTRACTION_VERSION
            val previousByPath = previous.associateBy { it.path }

            val candidates = source.enumerate()
                .filter { rules.folderAllowed(it.folder) }
            val byId = candidates.associateBy { LocalStableIds.trackId(it.path) }
            val removedIds = previous.map { it.id }.filter { it !in byId.keys }

            val records = coroutineScope {
                val limit = Semaphore(EXTRACTION_PARALLELISM)
                candidates.map { candidate ->
                    async(Dispatchers.Default) {
                        limit.withPermit { processCandidate(candidate, previousByPath[candidate.path], versionChanged, rules) }
                    }
                }.awaitAll().filterNotNull()
            }

            val kept = records.count { it.reusedArtwork }
            val summary = library.commitScan(
                records.map { it.record },
                removedIds,
                EXTRACTION_VERSION,
            ).let { it.copy(keptUnchanged = kept) }
            lastSummary.value = summary
            summary
        } finally {
            scanningState.value = false
        }
    }

    private class Processed(val record: LocalTrackRecord, val reusedArtwork: Boolean)

    private suspend fun processCandidate(
        candidate: LocalTrackCandidate,
        previous: LocalTrackRecord?,
        versionChanged: Boolean,
        rules: LocalMediaScanConfig,
    ): Processed? {
        val id = LocalStableIds.trackId(candidate.path)
        val unchanged = !versionChanged && previous != null &&
            previous.modifiedEpochMs == candidate.modifiedEpochMs && previous.sizeBytes == candidate.sizeBytes
        if (unchanged && previous != null) {
            // The unchanged rule: the whole prior record stands, artwork and extracted fields included.
            return Processed(previous.copy(id = id), reusedArtwork = true)
        }
        val (head, tail) = source.readTagBytes(candidate)
        val extracted = tags.read(head, tail, candidate.sizeBytes)
        val durationMs = extracted?.durationMs
            ?: MediaHeaderParser.parse(head, candidate.sizeBytes)?.durationMs
        if (rules.minimumDurationSeconds > 0 &&
            durationMs != null && durationMs < rules.minimumDurationSeconds * 1000L) return null
        val artworkUri = extracted?.artwork?.let { bytes ->
            source.storeArtwork(id, bytes, extracted.artworkMime ?: "image/jpeg")
        }
        return Processed(rebuildRecord(id, candidate, artworkUri, extracted, durationMs), reusedArtwork = false)
    }

    private fun rebuildRecord(
        id: String,
        candidate: LocalTrackCandidate,
        artworkUri: String?,
        tags: LocalMediaTags?,
        durationMs: Long?,
    ): LocalTrackRecord {
        val title = tags?.title?.trim().takeUnless { it.isNullOrEmpty() }
            ?: candidate.name.substringBeforeLast('.')
        val artists = (tags?.artists ?: emptyList())
            .flatMap { splitArtistNames(it) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .map { LocalArtistRecord(LocalStableIds.artistId(it), it) }
        val albumTitle = tags?.album?.trim().takeUnless { it.isNullOrEmpty() }
        val albumArtistName = tags?.albumArtist?.trim().takeUnless { it.isNullOrEmpty() }
            ?: artists.firstOrNull()?.name
        val album = albumTitle?.let {
            val albumArtist = albumArtistName?.let { name -> LocalArtistRecord(LocalStableIds.artistId(name), name) }
            LocalAlbumRecord(LocalStableIds.albumId(it, albumArtist?.id), it, albumArtist, tags?.year)
        }
        return LocalTrackRecord(
            id = id, path = candidate.path, title = title, artists = artists, album = album,
            durationMs = durationMs ?: tags?.durationMs,
            modifiedEpochMs = candidate.modifiedEpochMs, sizeBytes = candidate.sizeBytes,
            year = tags?.year, trackNumber = tags?.trackNumber,
            artworkUri = artworkUri, lyricsText = tags?.lyrics,
        )
    }

    /** The retained artist split: `,` `;` `/` `&` separators, trimmed and deduplicated. */
    private fun splitArtistNames(value: String): List<String> =
        value.split(',', ';', '/', '&').map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        /** Bump when the join/extraction rules change; stored rows rescan on the next pass. */
        const val EXTRACTION_VERSION = 1
        const val EXTRACTION_PARALLELISM = 4
    }
}
