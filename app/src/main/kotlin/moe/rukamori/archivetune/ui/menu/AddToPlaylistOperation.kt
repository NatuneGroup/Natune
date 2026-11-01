/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.menu

import moe.rukamori.archivetune.db.entities.Playlist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

internal data class PlaylistAddState(
    val loading: Boolean = false,
    val failed: Boolean = false,
    /** Tracks fully processed across the batch, for the "Processed X of Y tracks" line. */
    val processed: Int = 0,
    val total: Int = 0,
)

/** One refused playlist add: the playlist id, its title and the sanitized reason class name. */
internal data class FailedPlaylistAdd(val playlistId: String, val name: String, val reason: String)

/**
 * The batch result one [AddToPlaylistOperation] insert returns: the deferred-duplicate playlists, the ids that
 * committed, the refused and unconfirmed playlists, and the settled/unconfirmed track counts for the report text.
 */
internal data class AddBatchResult(
    val withDuplicates: List<Playlist>,
    val duplicatesMap: Map<String, List<String>>,
    val addedIds: Set<String>,
    val failed: List<FailedPlaylistAdd>,
    val uncertainIds: Set<String>,
    val settledTracks: Int,
    val unconfirmedTracks: Int,
)

/** The per-playlist outcome the dialog keeps open on, so a retry can be chosen without losing what committed. */
internal data class PlaylistAddReport(
    val failedIds: Set<String>,
    val failed: List<FailedPlaylistAdd>,
    val uncertainIds: Set<String>,
    val settledTracks: Int,
    val unconfirmedTracks: Int,
)

/** Owns the actual dialog fetch/insert work, even while its remembered scope stays composed. */
internal class AddToPlaylistOperation(private val scope: CoroutineScope) {
    @Volatile private var revision = 0L
    private var job: Job? = null
    private val mutableState = MutableStateFlow(PlaylistAddState())
    val state = mutableState.asStateFlow()

    fun cancel() {
        revision++
        job?.cancel()
        job = null
        mutableState.value = PlaylistAddState()
    }

    fun <T, R> start(
        targets: List<T>,
        load: suspend () -> List<String>,
        insert: suspend Attempt.(List<T>, List<String>) -> R,
        onSuccess: (R) -> Unit,
    ): Job {
        cancel()
        val attempt = Attempt(revision)
        val targetSnapshot = targets.toList()
        mutableState.value = PlaylistAddState(loading = true)
        val launched = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val songs = load().toList()
                attempt.ensureCurrent()
                val result = attempt.insert(targetSnapshot, songs)
                attempt.ensureCurrent()
                onSuccess(result)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Never expose transport URLs, account details or provider error messages.
                if (attempt.isCurrent()) mutableState.value = PlaylistAddState(failed = true)
            } finally {
                if (attempt.isCurrent()) {
                    mutableState.value = mutableState.value.copy(loading = false)
                    job = null
                }
            }
        }
        job = launched
        launched.start()
        return launched
    }

    inner class Attempt internal constructor(private val origin: Long) {
        internal fun isCurrent() = origin == revision

        suspend fun ensureCurrent() {
            coroutineContext.ensureActive()
            if (!isCurrent()) throw CancellationException("Playlist add operation replaced")
        }

        /**
         * The running track count of the batch. A superseded attempt reports nothing, so a replaced operation's
         * stale totals never reach the open dialog.
         */
        internal fun reportProgress(processed: Int, total: Int) {
            if (isCurrent()) {
                mutableState.value = mutableState.value.copy(processed = processed, total = total)
            }
        }
    }
}
