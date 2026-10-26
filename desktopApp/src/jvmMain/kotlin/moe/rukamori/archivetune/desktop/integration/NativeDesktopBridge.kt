package moe.rukamori.archivetune.desktop.integration

import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.Window
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.playback.PlaybackOutputRoute

/** App-owned JNI, linked only to the host's public MediaPlayer/WinRT/AppKit/Shell APIs. */
internal object NativeDesktopBridge {
    private val loaded by lazy {
        val explicit = System.getProperty("archivetune.system.library")
        val bundled = System.getProperty("compose.application.resources.dir")?.let {
            File(it, "native/${System.mapLibraryName("archivetune_system")}")
        }
        when {
            explicit != null -> System.load(explicit)
            bundled?.isFile == true -> System.load(bundled.absolutePath)
            else -> System.loadLibrary("archivetune_system")
        }
        true
    }
    fun ensureLoaded() { check(loaded) }
    external fun create(window: Long, callbacks: NativeSystemIntegration, labels: Array<String>): Long
    external fun update(handle: Long, track: String, title: String, artist: String, album: String, status: String,
        positionMicros: Long, durationMicros: Long, speed: Double, seek: Boolean, next: Boolean, previous: Boolean, advancing: Boolean)
    external fun setArtwork(handle: Long, track: String, encodedImage: ByteArray?)
    external fun trayAvailable(handle: Long): Boolean
    external fun release(handle: Long)
    external fun pickFiles(window: Long, folder: Boolean, multiple: Boolean, title: String): Array<String>?
    external fun registerUriScheme(executable: String): Boolean
}

class NativeSystemIntegration(
    window: Window,
    private val controls: DesktopMediaControls,
    private val raise: () -> Unit,
    private val quit: () -> Unit,
    private val activity: (occluded: Boolean, resumed: Boolean) -> Unit,
) : DesktopSystemIntegration {
    @Volatile private var handle: Long = 0L
    private val artworkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var artworkJob: Job? = null
    private var artworkKey: Pair<String, String?>? = null
    init {
        NativeDesktopBridge.ensureLoaded()
        handle = NativeDesktopBridge.create(
            Pointer.nativeValue(Native.getComponentPointer(window)), this,
            arrayOf("show_window", "play_pause", "next_track", "previous_track", "quit").map { DesktopStrings[it] }.toTypedArray(),
        )
        check(handle != 0L) { "Native media controls could not initialize" }
    }

    // JNI looks these up by name/signature. Callback references live until native release unregisters them.
    fun onCommand(command: String, track: String, positionMicros: Long, speed: Double) {
        // Never wait for the bridge monitor on a native main-thread callback: tray queries may
        // already hold it while synchronously consulting that same native main thread.
        if (handle == 0L) return
        try {
            when (command) {
                "raise" -> raise()
                "quit" -> quit()
                "seek" -> controls.setPosition(track, positionMicros)
                "speed" -> if (speed.isFinite()) controls.submit(DesktopTransportAction.Speed(speed.toFloat()))
                "play" -> controls.submit(DesktopTransportAction.Play)
                "pause" -> controls.submit(DesktopTransportAction.Pause)
                "toggle" -> controls.submit(DesktopTransportAction.Toggle)
                "stop" -> controls.submit(DesktopTransportAction.Stop)
                "next" -> controls.submit(DesktopTransportAction.Next)
                "previous" -> controls.submit(DesktopTransportAction.Previous)
            }
        } catch (error: Exception) {
            System.err.println("desktop transport callback failed: ${error.javaClass.simpleName}")
        }
    }
    fun onActivity(occluded: Boolean, resumed: Boolean) = activity(occluded, resumed)
    @get:Synchronized override val trayAvailable: Boolean get() = handle != 0L && NativeDesktopBridge.trayAvailable(handle)
    @Synchronized override fun update(snapshot: PlaybackSnapshot?, metadata: DesktopMediaMetadata?) {
        if (handle == 0L) return
        val safe = metadata?.takeIf { it.occurrenceId == snapshot?.occurrenceId }
        fun micros(ms: Long) = ms.coerceIn(0, Long.MAX_VALUE / 10_000) * 1000
        NativeDesktopBridge.update(handle, snapshot?.let { trackPath(it.occurrenceId) }.orEmpty(),
            safe?.title.orEmpty(), safe?.artists?.joinToString(", ").orEmpty(), safe?.album.orEmpty(), snapshot.mprisStatus(),
            micros(snapshot?.positionMs ?: 0), micros(snapshot?.durationMs ?: 0), snapshot?.playbackSpeed?.toDouble() ?: 1.0,
            snapshot?.isSeekable == true && !snapshot.isEnded, safe?.canNext == true, safe?.canPrevious == true,
            snapshot?.isAudioAdvancing == true && !snapshot.isBuffering && snapshot.outputRoute == PlaybackOutputRoute.LOCAL)
        val key = snapshot?.let { trackPath(it.occurrenceId) }.orEmpty() to safe?.artworkUri
        if (artworkKey != key) {
            artworkKey = key
            artworkJob?.cancel()
            artworkJob = artworkScope.launch {
                val image = readDesktopNativeArtwork(key.second)
                ensureActive()
                synchronized(this@NativeSystemIntegration) {
                    // A late image must not overwrite the next occurrence or resurrect a closed bridge.
                    if (handle != 0L && artworkKey == key) NativeDesktopBridge.setArtwork(handle, key.first, image)
                }
            }
        }
    }
    override fun close() {
        val previous = synchronized(this) { handle.also { handle = 0L } }
        artworkScope.cancel()
        if (previous != 0L) NativeDesktopBridge.release(previous)
    }
}
