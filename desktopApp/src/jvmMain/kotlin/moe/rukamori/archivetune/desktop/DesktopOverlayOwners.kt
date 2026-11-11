package moe.rukamori.archivetune.desktop

/**
 * The owners the desktop overlays read for the life of the window. A navigation entry disposes its overlays, so an
 * overlay must never close them; the root mount closes this once, when its composition leaves.
 */
internal class DesktopOverlayOwners {
    private val pending = mutableListOf<() -> Unit>()
    private var closed = false

    /** Owners added after [close] are closed at once, so none outlives the window. */
    fun own(vararg closers: () -> Unit) {
        if (closed) {
            closers.forEach { it() }
        } else {
            pending += closers
        }
    }

    fun close() {
        if (closed) return
        closed = true
        pending.forEach { it() }
        pending.clear()
    }
}
