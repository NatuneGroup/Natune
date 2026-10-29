package moe.rukamori.archivetune.shared.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.ui.navigation.NativeShellChrome
import moe.rukamori.archivetune.shared.ui.navigation.NativeShellCommand
import moe.rukamori.archivetune.shared.ui.navigation.NativeShellState
import moe.rukamori.archivetune.shared.ui.navigation.ShellTab

/** One subscription Swift holds. Cancelling it stops the callbacks; the host's own close also ends it. */
class IosSubscription internal constructor(private val job: Job) {
    fun cancel() {
        job.cancel()
    }
}

/**
 * What the native tab bar and mini player draw. [selectedTab] follows the shared tab order: 0 Home, 1 Search,
 * 2 Library. The labels are the shared resources in the app's language, so the Swift views carry no strings.
 */
class IosChromeState internal constructor(
    val visible: Boolean,
    val selectedTab: Int,
    val hasNowPlaying: Boolean,
    val title: String,
    val subtitle: String,
    val artworkUrl: String?,
    val playing: Boolean,
    val homeLabel: String,
    val searchLabel: String,
    val libraryLabel: String,
    val playLabel: String,
    val pauseLabel: String,
    val expandLabel: String,
)

/**
 * The Swift side of the native chrome. The Swift app calls [setSupported] once when it draws the system glass
 * (iOS 26 and later), observes the state and sends taps back. The "Native iOS interface" setting gates it from here,
 * and the shared root stays the owner of tabs, the player sheet and playback. All calls happen on the main thread.
 */
class IosNativeChrome internal constructor(private val scope: CoroutineScope) {
    internal val shell = NativeShellChrome()
    private var supported = false
    private var enabled = false

    fun setSupported(value: Boolean) {
        supported = value
        shell.setAvailable(supported && enabled)
    }

    internal fun setEnabled(value: Boolean) {
        enabled = value
        shell.setAvailable(supported && enabled)
    }

    /** Delivers the current state at once and then every change, on the main thread. */
    fun observe(listener: (IosChromeState) -> Unit): IosSubscription =
        IosSubscription(scope.launch { shell.state.collect { listener(it.toIos()) } })

    fun selectTab(index: Int) {
        ShellTab.entries.getOrNull(index)?.let { shell.send(NativeShellCommand.SelectTab(it)) }
    }

    fun expandPlayer() {
        shell.send(NativeShellCommand.ExpandPlayer)
    }

    fun togglePlayback() {
        shell.send(NativeShellCommand.TogglePlayback)
    }

    /** The distance from the bottom of the screen to the top of the native chrome, in points. */
    fun setBottomInset(points: Double) {
        shell.setBottomInset(points.toFloat())
    }
}

private fun NativeShellState.toIos(): IosChromeState {
    val song = nowPlaying
    return IosChromeState(
        visible = visible,
        selectedTab = selectedTab.ordinal,
        hasNowPlaying = song != null,
        title = song?.title.orEmpty(),
        subtitle = song?.subtitle.orEmpty(),
        artworkUrl = song?.artworkUri,
        playing = song?.playing == true,
        homeLabel = labels.home,
        searchLabel = labels.search,
        libraryLabel = labels.library,
        playLabel = labels.play,
        pauseLabel = labels.pause,
        expandLabel = labels.expand,
    )
}
