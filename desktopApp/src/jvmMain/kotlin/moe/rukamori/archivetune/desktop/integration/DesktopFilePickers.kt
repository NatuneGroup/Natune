package moe.rukamori.archivetune.desktop.integration

import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.Window
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Real GTK3 / IFileOpenDialog / NSOpenPanel. Cancellation returns an empty selection; errors throw.
 * Linux choosers run on the browser library's single GTK owner, so this class starts no GTK loop of its own.
 */
class DesktopFilePickers(private val window: Window? = null) : AutoCloseable {
    private val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "archivetune-file-picker").apply { isDaemon = true } }
        .asCoroutineDispatcher()
    @Volatile private var closing = false

    suspend fun files(multiple: Boolean = true): List<Path> = choose(folder = false, multiple)
    suspend fun folder(): Path? = choose(folder = true, multiple = false).singleOrNull()

    private suspend fun choose(folder: Boolean, multiple: Boolean): List<Path> = withContext(dispatcher) {
        val title = DesktopStrings[if (folder) "open_folder" else "open_file"]
        val paths = if (System.getProperty("os.name").startsWith("Linux")) {
            // The poll rechecks cancellation and disposal, so a chooser the user never answers cannot pin this executor.
            GtkFileChooser.choose(folder, multiple, title) { closing || !isActive }
        } else {
            NativeDesktopBridge.ensureLoaded()
            NativeDesktopBridge.pickFiles(window?.let { Pointer.nativeValue(Native.getComponentPointer(it)) } ?: 0L,
                folder, multiple, title)?.toList().orEmpty()
        }
        paths.map { Path.of(it).toAbsolutePath().normalize() }
    }

    override fun close() {
        closing = true
        dispatcher.close()
    }
}

/**
 * The Linux chooser is created and answered on the browser library's one GTK owner. This side only starts a ticket and
 * polls it, so a cancelled coroutine or a closing picker returns within one poll interval.
 */
private object GtkFileChooser {
    private const val POLL_MILLIS = 250L

    fun choose(folder: Boolean, multiple: Boolean, title: String, shouldCancel: () -> Boolean): List<String> {
        NativeBrowserLibrary.ensureLoaded()
        val ticket = NativeGtkFilePicker.start(folder, multiple, title, DesktopStrings["select"], DesktopStrings["cancel"])
        check(ticket != 0L) { "GTK file chooser needs a desktop session" }
        while (true) {
            NativeGtkFilePicker.await(ticket, POLL_MILLIS)?.let { return it.toList() }
            if (shouldCancel()) {
                NativeGtkFilePicker.cancel(ticket)
                return emptyList()
            }
        }
    }
}
