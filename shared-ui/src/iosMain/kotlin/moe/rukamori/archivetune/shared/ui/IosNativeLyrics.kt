package moe.rukamori.archivetune.shared.ui

import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.ui.lyrics.LyricsRenderRow
import moe.rukamori.archivetune.shared.ui.lyrics.NativeLyricsDocument
import moe.rukamori.archivetune.shared.ui.lyrics.NativeLyricsFrame
import moe.rukamori.archivetune.shared.ui.lyrics.NativeLyricsTimeline
import platform.UIKit.UIViewController

/** Builds the native lyric view for one bridge. The Swift app registers it on iOS 26 and later. */
typealias IosNativeLyricsViewFactory = (IosNativeLyrics) -> UIViewController

/** What the native lyric view draws now, and whether its surface is on screen and resumed. */
class IosLyricsUpdate internal constructor(
    val document: NativeLyricsDocument,
    val visible: Boolean,
)

/**
 * The Swift side of one native lyric view. Kotlin owns the rows, the clock and seeking: Swift observes the
 * document, reads [frame] once per display frame and reports taps through [seekTo]. The position comes from the
 * player's snapshots through the shared lyric clock, never from a Swift clock. All calls happen on the main thread.
 */
class IosNativeLyrics internal constructor() {
    private val timeline = NativeLyricsTimeline()
    private var update: IosLyricsUpdate? = null
    private var listener: ((IosLyricsUpdate) -> Unit)? = null
    private var seek: (Long) -> Unit = { }

    /** Delivers the current document at once, when there is one, and then every change. One view observes. */
    fun observe(listener: (IosLyricsUpdate) -> Unit): IosSubscription {
        this.listener = listener
        update?.let(listener)
        return IosSubscription { if (this.listener === listener) this.listener = null }
    }

    /** The feed position and active rows at this instant. Cheap enough to call on every display frame. */
    fun frame(): NativeLyricsFrame = timeline.frame()

    /** A tap on row [index]. It seeks only when the shared tap policy allows it for that row. */
    fun seekTo(index: Int) {
        val line = update?.document?.lines?.getOrNull(index) ?: return
        if (line.seekable) seek(line.timeMs)
    }

    internal fun accept(snapshot: PlaybackSnapshot) {
        timeline.accept(snapshot)
    }

    internal fun present(
        document: NativeLyricsDocument,
        rows: List<LyricsRenderRow>,
        syncOffsetMs: Long,
        visible: Boolean,
        seek: (Long) -> Unit,
    ) {
        this.seek = seek
        timeline.setSyncOffset(syncOffsetMs)
        val current = update
        if (current != null && current.document === document && current.visible == visible) return
        if (current?.document !== document) timeline.setRows(rows)
        val next = IosLyricsUpdate(document, visible)
        update = next
        listener?.invoke(next)
    }

    internal fun close() {
        listener = null
        seek = { }
    }
}
