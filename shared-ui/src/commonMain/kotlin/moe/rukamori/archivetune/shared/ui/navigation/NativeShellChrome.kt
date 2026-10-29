package moe.rukamori.archivetune.shared.ui.navigation

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/** The song the platform mini player shows. Labels are resolved by the root, so the platform view adds no strings. */
data class NativeNowPlaying(
    val occurrenceId: String,
    val title: String,
    val subtitle: String,
    val artworkUri: String?,
    val playing: Boolean,
)

/** Labels the platform chrome shows, resolved from the shared resources in the app's own language. */
data class NativeShellLabels(
    val home: String = "",
    val search: String = "",
    val library: String = "",
    val play: String = "",
    val pause: String = "",
    val expand: String = "",
)

/**
 * What a platform-drawn tab bar and mini player show. [visible] is false whenever the shared shell keeps its own
 * chrome or something covers the bottom of the screen, such as the expanded player.
 */
data class NativeShellState(
    val visible: Boolean = false,
    val selectedTab: ShellTab = ShellTab.Home,
    val nowPlaying: NativeNowPlaying? = null,
    val labels: NativeShellLabels = NativeShellLabels(),
)

/** A request from the platform chrome. The shared root applies it, so [ShellNavigation] stays the one owner. */
sealed interface NativeShellCommand {
    data class SelectTab(val tab: ShellTab) : NativeShellCommand
    data object ExpandPlayer : NativeShellCommand
    data object TogglePlayback : NativeShellCommand
}

/**
 * The link between the shared shell and a tab bar and mini player the platform draws above it (the iOS 26 system
 * glass). The host turns it on with [setAvailable]; the shell then draws neither its bar nor its mini player in the
 * layouts the platform chrome replaces, and reserves [bottomInsetDp] beneath the pages instead. Rail layouts keep the
 * shared chrome. Everything here runs on the UI thread.
 */
class NativeShellChrome {
    private val available = MutableStateFlow(false)

    /** True while the host can draw the chrome and the user wants it. The shell reads it to step aside. */
    val active: StateFlow<Boolean> = available.asStateFlow()

    private val inset = MutableStateFlow(0f)

    /** How far the platform chrome reaches up from the bottom edge, in density-independent points. */
    val bottomInsetDp: StateFlow<Float> = inset.asStateFlow()

    private var shellYields = false
    private var published = NativeShellState()
    private val current = MutableStateFlow(NativeShellState())

    /** The state the platform chrome draws. Its [NativeShellState.visible] already folds in every gate. */
    val state: StateFlow<NativeShellState> = current.asStateFlow()

    private val pending = Channel<NativeShellCommand>(COMMAND_CAPACITY, BufferOverflow.DROP_OLDEST)

    /** Requests from the platform chrome, for the root to apply. A burst keeps only the newest few. */
    val commands: Flow<NativeShellCommand> = pending.receiveAsFlow()

    fun setAvailable(value: Boolean) {
        available.value = value
        refresh()
    }

    /** A value that is not a finite, non-negative size is ignored, so a bad measurement never hides the pages. */
    fun setBottomInset(dp: Float) {
        if (dp.isFinite() && dp >= 0f) inset.value = dp.coerceAtMost(MAX_INSET_DP)
    }

    /** Called by the shell: true while its layout is one the platform chrome replaces and it has stepped aside. */
    fun reportShellYields(value: Boolean) {
        shellYields = value
        refresh()
    }

    /** Called by the root with what the chrome should show. [NativeShellState.visible] is the root's own gate. */
    fun publish(state: NativeShellState) {
        published = state
        refresh()
    }

    fun send(command: NativeShellCommand): Boolean = pending.trySend(command).isSuccess

    private fun refresh() {
        current.value = published.copy(visible = published.visible && shellYields && available.value)
    }

    companion object {
        const val COMMAND_CAPACITY = 8

        /** Far above any real tab bar and mini player; a larger report is clamped rather than trusted. */
        const val MAX_INSET_DP = 320f
    }
}
