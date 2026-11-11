package moe.rukamori.archivetune.shared.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.playback.PlaybackCommand
import moe.rukamori.archivetune.shared.domain.playback.PlaybackFailure
import moe.rukamori.archivetune.shared.domain.playback.PlaybackOutputRoute
import moe.rukamori.archivetune.shared.domain.playback.PlaybackPort
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.queue.UserQueuedKind
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.ContinuationQueueController
import moe.rukamori.archivetune.shared.domain.repository.QueueLoadRequest
import moe.rukamori.archivetune.shared.domain.repository.QueueState
import moe.rukamori.archivetune.shared.domain.settings.PreferenceEditor
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.SettingsChange
import moe.rukamori.archivetune.shared.domain.settings.SettingsStore
import moe.rukamori.archivetune.shared.ui.desktop.DesktopShellMetrics
import moe.rukamori.archivetune.shared.ui.glass.OpticalCapabilities
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphPage
import moe.rukamori.archivetune.shared.ui.player.PlayerAction
import moe.rukamori.archivetune.shared.ui.player.PlayerNativeControls
import moe.rukamori.archivetune.shared.ui.player.PlayerNativeState
import moe.rukamori.archivetune.shared.ui.player.PlayerRepeat
import moe.rukamori.archivetune.shared.ui.player.PlayerSlots
import moe.rukamori.archivetune.shared.ui.theme.Space

/**
 * The desktop shell always calls its player-bar slot, so the host decides whether the bar exists. With an empty
 * queue no strip may be reserved under the page (desktop audit defect 3); with a queued track the page clears
 * exactly the bar's height.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopPlayerBarReservationTest {
    @Test
    fun anEmptyQueueReservesNoStripUnderThePage() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val port = BarPlaybackPort(null)
        val player = MusicPlayerController(port, scope)
        val queue = BarQueue(player, emptyList())
        try {
            setContent { DesktopShellRootUnderTest(barHost(queue, scope)) }

            onNodeWithTag("desktop-player-bar").assertDoesNotExist()
            val stripHeight = stripHeightUnderPage()
            assertEquals(Space.xs.value, stripHeight, 1f)
        } finally {
            player.close()
            scope.cancel()
        }
    }

    @Test
    fun aQueuedTrackShowsTheBarAndThePageClearsIt() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val port = BarPlaybackPort(barPlayback())
        val player = MusicPlayerController(port, scope)
        val queue = BarQueue(player, listOf(BAR_OCCURRENCE))
        try {
            setContent { DesktopShellRootUnderTest(barHost(queue, scope)) }

            onNodeWithTag("desktop-player-bar").assertIsDisplayed()
            val stripHeight = stripHeightUnderPage()
            assertEquals((Space.xs + DesktopShellMetrics.playerBarHeight).value, stripHeight, 1f)
        } finally {
            player.close()
            scope.cancel()
        }
    }

    /** The vertical gap between the page surface's bottom edge and the window's bottom edge. */
    private fun ComposeUiTest.stripHeightUnderPage(): Float {
        val shell = onNodeWithTag("desktop-shell").getUnclippedBoundsInRoot()
        val page = onNodeWithTag("desktop-content-surface").getUnclippedBoundsInRoot()
        return (shell.bottom - page.bottom).value
    }

    @Composable
    private fun DesktopShellRootUnderTest(host: ArchiveTuneHostBindings) {
        Box(Modifier.size(800.dp, 600.dp)) {
            ArchiveTuneRoot(
                host = host,
                visible = true,
                resumed = true,
                opticalCapabilities = OpticalCapabilities(blur = false, refraction = false),
                desktopShell = true,
            )
        }
    }

    private fun barHost(queue: BarQueue, scope: CoroutineScope) = ArchiveTuneHostBindings(
        queue = queue,
        settings = BarSettings(),
        actionScope = scope,
        routes = emptyFlow(),
        backRequests = MutableSharedFlow(extraBufferCapacity = 1),
        page = { ArchiveGraphPage.Host },
        artwork = { _, modifier -> Box(modifier) },
        newsImage = { _, modifier -> Box(modifier) },
        formatNewsDate = { "Date" },
        librarySourceSelector = {},
        hostScreen = { _, _ -> Box(Modifier.fillMaxSize().testTag("fixture-page")) },
        overlays = {},
        playerSlots = { _, _, _ -> barPlayerSlots() },
        playerControls = BarPlayerControls(),
        more = {},
        playOccurrence = { _, _ -> error("No playback request expected") },
        openExternal = { error("No external route expected") },
        onFailure = { error("No host failure expected") },
        onBackAtRoot = {},
        newsAvailable = false,
    )

    private companion object {
        const val BAR_OCCURRENCE = "track@0"
    }
}

private fun barPlayback() = PlaybackSnapshot(
    BAR_PLAYBACK_OCCURRENCE, "song", 1, 0, 12_000, 0, 180_000,
    true, 1f, true, false, false, PlaybackOutputRoute.LOCAL,
)

private const val BAR_PLAYBACK_OCCURRENCE = "track@0"

@Composable
private fun barPlayerSlots() = PlayerSlots(
    artwork = { _, modifier -> Box(modifier) },
    canvas = { _, _, modifier -> Box(modifier) },
    customBackground = { _, modifier -> Box(modifier) },
    lyrics = { modifier -> Box(modifier) },
    compactLyrics = { modifier -> Box(modifier) },
    queue = { modifier -> Box(modifier) },
)

private class BarPlaybackPort(initial: PlaybackSnapshot?) : PlaybackPort {
    override val snapshots = MutableStateFlow(initial)
    override val failures = MutableSharedFlow<PlaybackFailure>()
    override fun submit(command: PlaybackCommand) = Unit
    override fun close() = Unit
}

private class BarSettings : SettingsStore {
    override val snapshots = MutableStateFlow<PreferenceSnapshot?>(PreferenceSnapshot.EMPTY)
    override suspend fun edit(change: PreferenceEditor.() -> Unit): SettingsChange {
        val before = requireNotNull(snapshots.value)
        val after = before.toEditor().apply(change).snapshot()
        snapshots.value = after
        return SettingsChange(before, after)
    }
}

private class BarQueue(
    override val player: MusicPlayerController,
    occurrenceIds: List<String>,
) : ContinuationQueueController {
    private val entries = occurrenceIds.map { id ->
        ContentOccurrence(
            id,
            ContentItem(ContentRef("fixture", ContentKind.SONG, "song-$id"), "Fixture $id", durationMs = 180_000),
        )
    }
    override val state = MutableStateFlow(
        QueueState(1, entries, entries.firstOrNull()?.occurrenceId, occurrenceIds),
    )

    override suspend fun remove(occurrenceIds: Set<String>) = error("Not called")
    override suspend fun replace(
        entries: List<ContentOccurrence>,
        startOccurrenceId: String,
        playWhenReady: Boolean,
        staysFinite: Boolean,
    ) = error("Not called")

    override suspend fun insert(entries: List<ContentOccurrence>, kind: UserQueuedKind): Int = error("Not called")
    override suspend fun move(occurrenceId: String, beforeOccurrenceId: String?) = error("Not called")
    override suspend fun select(occurrenceId: String, positionMs: Long) = error("Not called")
    override suspend fun load(request: QueueLoadRequest) = error("Not called")
    override suspend fun checkpoint(): Boolean = error("Not called")
    override suspend fun resume(): Boolean = error("Not called")
}

private class BarPlayerControls : PlayerNativeControls {
    override val state = MutableStateFlow(
        PlayerNativeState(
            occurrenceId = "track@0",
            playbackGeneration = 1,
            canPrevious = false,
            canNext = false,
            canSeek = true,
            shuffle = false,
            repeat = PlayerRepeat.OFF,
            liked = false,
            volume = 1f,
            canvasAvailable = false,
            palette = emptyList(),
        ),
    )

    override fun previous() = Unit
    override fun next() = Unit
    override fun setShuffle(enabled: Boolean) = Unit
    override fun setRepeat(repeat: PlayerRepeat) = Unit
    override fun setVolume(volume: Float) = Unit
    override fun seekStep(forward: Boolean) = Unit
    override fun onAction(action: PlayerAction, occurrenceId: String?) = Unit
}
