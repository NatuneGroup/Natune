package moe.rukamori.archivetune.shared.domain.automix

import kotlinx.coroutines.CancellationException
import platform.Foundation.NSThread

internal actual fun defaultAutomixCheckActive(): () -> Unit = {
    if (NSThread.currentThread.isCancelled()) {
        throw CancellationException("Compact automix analysis interrupted")
    }
}
