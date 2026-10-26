package moe.rukamori.archivetune.shared.ui.screens.customartwork

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkKeys
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkLimits
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkOverrides
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkResult
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkSource
import moe.rukamori.archivetune.shared.domain.repository.CustomArtworkRepository

/**
 * The host's system chooser for one [CustomArtworkKind]: images for a cover, images or video for a canvas. It suspends
 * until the user picks a file and yields that file's [CustomArtworkSource], or cancels and yields null. The host owns
 * the platform chooser and its access grant; this module never opens a picker itself.
 */
typealias CustomArtworkSourcePicker = suspend (kind: CustomArtworkKind) -> CustomArtworkSource?

private const val BYTES_PER_MEGABYTE = 1024L * 1024L

private data class CustomArtworkPresentation(
    val busy: Boolean = false,
    val outcome: CustomArtworkOutcome? = null,
)

/**
 * Presentation state for one song's custom artwork dialog, bound to the single [CustomArtworkRepository]. The repository
 * stores and reports. This binding owns only what the dialog shows: whether a pick or a store is in flight, the last
 * settled outcome, and whether a result may still reach the screen.
 *
 * A cancelled pick changes nothing and claims nothing. A removal that finds no override claims neither a removal nor a
 * failure. Every repository call runs on [ioDispatcher], because the Android source reads its MIME type through the
 * content resolver, so no file work happens on the caller's thread. [close] is idempotent. A result that lands after it
 * is dropped, while a write already under way still completes inside the repository.
 *
 * [artworkUrl] and [songId] identify what the dialog edits. A cover needs an artwork identity and a canvas needs a song
 * id. Without one, the row stays hidden or reports unsupported, and no picker opens. Create one binding per target.
 */
class CustomArtworkEditBinding(
    private val artworkUrl: String?,
    private val songId: String?,
    private val repository: CustomArtworkRepository,
    private val pick: CustomArtworkSourcePicker,
    scope: CoroutineScope,
    private val onDismiss: () -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val job: Job = SupervisorJob(scope.coroutineContext[Job])
    private val work = CoroutineScope(scope.coroutineContext + job)
    private val presentation = MutableStateFlow(CustomArtworkPresentation())

    private val coverStorable: Boolean = CustomArtworkKeys.coverKey(artworkUrl) != null
    private val canvasStorable: Boolean = CustomArtworkKeys.canvasKey(songId) != null

    /** The dialog state. Overrides come from the repository; busy and the outcome come from this binding. */
    val state: StateFlow<CustomArtworkEditUi> =
        combine(repository.overrides, presentation) { overrides, local -> project(overrides, local) }
            .stateIn(work, SharingStarted.Eagerly, project(repository.overrides.value, presentation.value))

    val actions: CustomArtworkEditActions =
        CustomArtworkEditActions(
            setCover = { pickCover() },
            setCanvas = { pickCanvas() },
            removeCover = { removeCover() },
            removeCanvas = { removeCanvas() },
            dismiss = { if (!presentation.value.busy && job.isActive) onDismiss() },
        )

    init {
        // Scans both folders once, so the first lookup does not wait on disk. A failed scan leaves the overrides empty
        // until the next operation retries it, and that operation reports its own outcome.
        work.launch {
            try {
                withContext(ioDispatcher) { repository.ensureLoaded() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Reported by the next operation rather than raised here.
            }
        }
    }

    /** Ends the binding. A repository write already under way finishes, but its result no longer reaches this dialog. */
    fun close() {
        job.cancel()
    }

    private fun project(overrides: CustomArtworkOverrides, local: CustomArtworkPresentation): CustomArtworkEditUi =
        CustomArtworkEditUi(
            hasCoverSource = coverStorable,
            hasCustomCover = overrides.hasCover(artworkUrl),
            hasCustomCanvas = overrides.hasCanvas(songId),
            busy = local.busy,
            outcome = local.outcome,
        )

    private fun pickCover() {
        if (!admit()) return
        val url = artworkUrl
        if (url == null || !coverStorable) {
            settle(CustomArtworkOutcome.Unsupported)
            return
        }
        runOperation {
            val source = pick(CustomArtworkKind.COVER) ?: return@runOperation null
            withContext(ioDispatcher) { repository.setCover(url, source) }.toOutcome(CustomArtworkKind.COVER)
        }
    }

    private fun pickCanvas() {
        if (!admit()) return
        val id = songId
        if (id == null || !canvasStorable) {
            settle(CustomArtworkOutcome.Unsupported)
            return
        }
        runOperation {
            val source = pick(CustomArtworkKind.CANVAS) ?: return@runOperation null
            withContext(ioDispatcher) { repository.setCanvas(id, source) }.toOutcome(CustomArtworkKind.CANVAS)
        }
    }

    private fun removeCover() {
        if (!admit()) return
        val url = artworkUrl
        runOperation {
            val removed = withContext(ioDispatcher) { repository.removeCover(url) }
            if (removed) CustomArtworkOutcome.Removed(CustomArtworkKind.COVER) else null
        }
    }

    private fun removeCanvas() {
        if (!admit()) return
        val id = songId
        runOperation {
            val removed = withContext(ioDispatcher) { repository.removeCanvas(id) }
            if (removed) CustomArtworkOutcome.Removed(CustomArtworkKind.CANVAS) else null
        }
    }

    /** Admits one operation at a time. Busy stays set from the pick until the outcome settles. */
    private fun admit(): Boolean {
        if (!job.isActive || presentation.value.busy) return false
        presentation.value = CustomArtworkPresentation(busy = true)
        return true
    }

    private fun runOperation(block: suspend () -> CustomArtworkOutcome?) {
        work.launch {
            val outcome =
                try {
                    block()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    CustomArtworkOutcome.Failed
                }
            settle(outcome)
        }
    }

    /** Publishes the settled outcome. A null outcome means a cancelled pick or a no-op, so nothing is claimed. */
    private fun settle(outcome: CustomArtworkOutcome?) {
        if (!job.isActive) return
        presentation.value = CustomArtworkPresentation(busy = false, outcome = outcome)
    }
}

private fun CustomArtworkResult.toOutcome(kind: CustomArtworkKind): CustomArtworkOutcome =
    when (this) {
        CustomArtworkResult.SAVED -> CustomArtworkOutcome.Saved(kind)
        CustomArtworkResult.TOO_LARGE -> CustomArtworkOutcome.TooLarge(limitMegabytes(kind))
        CustomArtworkResult.UNSUPPORTED -> CustomArtworkOutcome.Unsupported
        CustomArtworkResult.FAILED -> CustomArtworkOutcome.Failed
    }

private fun limitMegabytes(kind: CustomArtworkKind): Int {
    val bytes =
        if (kind == CustomArtworkKind.COVER) CustomArtworkLimits.MAX_COVER_BYTES else CustomArtworkLimits.MAX_CANVAS_BYTES
    return (bytes / BYTES_PER_MEGABYTE).toInt()
}

/**
 * Mounts the custom artwork dialog for one song with its real dependencies. The binding is created with the composition
 * and keyed on the song's identity. It is closed exactly once when the composition leaves or the identity changes, so
 * a later visit starts fresh and no stale result reaches the screen. [preview] draws the current artwork, supplied by
 * the host as for [CustomArtworkEditDialog].
 */
@Composable
fun BoundCustomArtworkEditDialog(
    artworkUrl: String?,
    songId: String?,
    repository: CustomArtworkRepository,
    pick: CustomArtworkSourcePicker,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    preview: (@Composable (Modifier) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val currentPick by rememberUpdatedState(pick)
    val currentDismiss by rememberUpdatedState(onDismiss)
    val binding =
        remember(artworkUrl, songId, repository, ioDispatcher) {
            CustomArtworkEditBinding(
                artworkUrl = artworkUrl,
                songId = songId,
                repository = repository,
                pick = { kind -> currentPick(kind) },
                scope = scope,
                onDismiss = { currentDismiss() },
                ioDispatcher = ioDispatcher,
            )
        }
    DisposableEffect(binding) { onDispose { binding.close() } }
    val state by binding.state.collectAsState()
    CustomArtworkEditDialog(state = state, actions = binding.actions, modifier = modifier, preview = preview)
}
