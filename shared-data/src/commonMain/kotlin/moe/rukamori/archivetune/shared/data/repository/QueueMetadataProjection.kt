package moe.rukamori.archivetune.shared.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.QueueState
import moe.rukamori.archivetune.shared.domain.songmetadata.SongMetadataOverride
import moe.rukamori.archivetune.shared.domain.songmetadata.SongMetadataRepository
import moe.rukamori.archivetune.shared.domain.songmetadata.withSongMetadataOverrides

/**
 * The song-title/artist edits layered onto every queue state [PortableQueueOwner] publishes. Stored rows,
 * checkpoints and provider resolution stay canonical; only this observable projection carries the user's
 * names. Every publish goes through [current]'s setter, so a re-edit or reset re-projects the queue in place:
 * same order, revision and audible engine, only its metadata projection changes.
 */
internal class QueueMetadataProjection(private val repository: SongMetadataRepository?) {
    private var edits: Map<ContentRef, SongMetadataOverride> = emptyMap()
    private var initialized = repository == null
    private val published = MutableStateFlow(
        QueueState(revision = 0L, entries = emptyList(), currentOccurrenceId = null, playOrder = emptyList()),
    )

    /** The observable queue, metadata edits applied. There is no second published timeline. */
    val state: StateFlow<QueueState> = published.asStateFlow()

    var current: QueueState
        get() = published.value
        set(value) {
            published.value = value.copy(entries = value.entries.map { it.withSongMetadataOverrides(edits) })
        }

    /**
     * The synchronous read for the owner's first locked access: restore/replace can beat [watch]'s first
     * emission, and publishing before this read would briefly expose provider names or persist an effective
     * input as canonical. Must run under the queue lock, as must [watch]'s [onChange].
     */
    suspend fun initializeNow() {
        if (initialized) return
        edits = repository?.overrides().orEmpty()
        initialized = true
    }

    /** Watches the edits and runs [onChange] after each committed map; null when no repository is bound. */
    fun watch(scope: CoroutineScope, onChange: suspend () -> Unit): Job? =
        repository?.let { repo ->
            scope.launch {
                repo.observeOverrides().collect { latest ->
                    edits = latest
                    initialized = true
                    onChange()
                }
            }
        }
}
