package moe.rukamori.archivetune.shared.domain.automix

import kotlinx.coroutines.CancellationException

internal actual fun defaultAutomixCheckActive(): () -> Unit = {
    if (Thread.currentThread().isInterrupted) {
        throw CancellationException("Compact automix analysis interrupted")
    }
}
