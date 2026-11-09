package moe.rukamori.archivetune.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** A queue changed while persistence was off still needs a checkpoint when it is switched back on. */
internal fun observeQueuePersistence(
    scope: CoroutineScope,
    enabled: Flow<Boolean>,
    restoreCompleted: () -> Boolean,
    checkpoint: () -> Unit,
): Job = scope.launch {
    enabled.distinctUntilChanged().collect { isEnabled ->
        if (isEnabled && restoreCompleted()) checkpoint()
    }
}
