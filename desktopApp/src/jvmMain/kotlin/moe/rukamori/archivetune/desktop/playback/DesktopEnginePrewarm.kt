package moe.rukamori.archivetune.desktop.playback

import java.awt.GraphicsEnvironment
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where this process's one background engine warm stands. */
internal sealed interface DesktopEnginePrewarmState {
    /** Constructed but not started: no path was read and no native code was loaded. */
    data object Idle : DesktopEnginePrewarmState

    /** The load is running in the background. A play that races it waits only for the load it would do anyway. */
    data object Loading : DesktopEnginePrewarmState

    /** The engine is loaded and its ABI was checked; the first play pays no load. */
    data object Ready : DesktopEnginePrewarmState

    /** This host has no engine library, so there is nothing to warm. Playback keeps its own failure. */
    data object LibraryAbsent : DesktopEnginePrewarmState

    /** The process has no window; loading native audio code nothing can use would only cost startup time. */
    data object Headless : DesktopEnginePrewarmState

    /** The library is there but did not load. [detail] is the loader's reason, which playback reports again. */
    data class Failed(val detail: String) : DesktopEnginePrewarmState
}

/**
 * Warms the desktop playback engine once per process, off the window thread.
 *
 * The window root starts this after its first composition, not from the composition body: the
 * `dlopen` and, on Windows, its staged FFmpeg closure cost 50-300 ms, which otherwise lands on the
 * first play. [PlaybackEngineLibrary.ensureLoaded] keeps its one cached [Result], so a warm that
 * fails, or a host with no library, leaves playback the same failure and message it already
 * reported; nothing here retries or substitutes an engine.
 *
 * The scope lives for the process, matching the library's lifetime, and the collaborators are
 * injectable so the one-shot and timing behaviour are tested without a window or a native engine.
 */
internal class DesktopEnginePrewarm(
    private val libraryPresent: () -> Boolean = PlaybackEngineLibrary::isLibraryPresent,
    private val load: () -> Unit = PlaybackEngineLibrary::ensureLoaded,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val headless: () -> Boolean = GraphicsEnvironment::isHeadless,
) {
    // Nothing is created before the first start, so constructing the host's holder stays off the frame path too.
    private val scope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + dispatcher) }
    private val started = AtomicBoolean(false)
    private val mutableState = MutableStateFlow<DesktopEnginePrewarmState>(DesktopEnginePrewarmState.Idle)
    val state: StateFlow<DesktopEnginePrewarmState> = mutableState.asStateFlow()

    /** Starts the one warm. Every later call is a no-op, including one after a failure, so no load is retried. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            try {
                if (headless()) {
                    mutableState.value = DesktopEnginePrewarmState.Headless
                    return@launch
                }
                mutableState.value = DesktopEnginePrewarmState.Loading
                if (!libraryPresent()) {
                    mutableState.value = DesktopEnginePrewarmState.LibraryAbsent
                    return@launch
                }
                load()
                mutableState.value = DesktopEnginePrewarmState.Ready
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                mutableState.value = DesktopEnginePrewarmState.Failed(failure.message ?: failure.javaClass.simpleName)
                System.err.println("engine prewarm failed: ${failure.javaClass.simpleName}")
            }
        }
    }
}
