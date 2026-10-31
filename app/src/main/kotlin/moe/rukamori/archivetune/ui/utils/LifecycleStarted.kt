package moe.rukamori.archivetune.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.map

/**
 * Whether the host activity is at least STARTED. Decorative and polling loops key on this so they stop
 * while the app is in the background: Compose keeps its frame clock running for an unseen activity otherwise.
 */
@Composable
fun rememberLifecycleStarted(): State<Boolean> {
    val lifecycleOwner = LocalLifecycleOwner.current
    return produceState(
        initialValue = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
        key1 = lifecycleOwner,
    ) {
        lifecycleOwner.lifecycle.currentStateFlow
            .map { it.isAtLeast(Lifecycle.State.STARTED) }
            .collect { value = it }
    }
}
