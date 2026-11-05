package moe.rukamori.archivetune.shared.data.platform.ios

import kotlin.concurrent.Volatile

/**
 * The steady-state interval of the playback port's main-queue read, in milliseconds. Commands wake the read directly,
 * so this only decides how often an idle, playing or backgrounded app looks at the engine on its own.
 *
 * A backgrounded app keeps playing, but it stops reading and publishing at the active rate: nothing draws, and the
 * system surfaces only need a truthful position, so waking the main thread twenty times a second is wasted power.
 */
internal class IosPlaybackPollCadence {
    /** Forwarded by the host on every activation change. */
    @Volatile
    var appActive: Boolean = true

    fun intervalMs(busy: Boolean, blending: Boolean): Long = when {
        !appActive -> BACKGROUND_MS
        !busy -> IDLE_MS
        blending -> BLEND_MS
        else -> ACTIVE_MS
    }

    private companion object {
        const val ACTIVE_MS = 50L
        const val BLEND_MS = 20L
        const val IDLE_MS = 250L
        const val BACKGROUND_MS = 500L
    }
}
