package moe.rukamori.archivetune.shared.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
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
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.playback.*
import moe.rukamori.archivetune.shared.domain.queue.UserQueuedKind
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.glass.OpticalCapabilities
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphPage
import moe.rukamori.archivetune.shared.ui.player.*

@OptIn(ExperimentalTestApi::class)
class ExpandedPlayerRecoveryTest {
    @Test
    fun replacedOrRemovedPlaybackWhileExpandedRestoresChromeAndBack() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val port = RootPlaybackPort(rootPlayback())
        val player = MusicPlayerController(port, scope)
        val queue = RootQueue(player, port, listOf(ROOT_OCCURRENCE))
        val backRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var backAtRoot = 0
        val host = rootHost(queue, scope, backRequests) { backAtRoot++ }
        try {
            setContent { RootUnderTest(host) }

            onNodeWithTag("compact-mini-player").assertIsDisplayed().performClick()
            onNodeWithTag("player-close").assertIsDisplayed()
            onNodeWithTag("shell-bottom-bar").assertDoesNotExist()

            // Playback may move ahead of the queue briefly; the queue's current item keeps the player and its close.
            runOnIdle { port.snapshots.value = rootPlayback().copy(occurrenceId = "track@9") }
            onNodeWithTag("player-close").assertIsDisplayed()

            runOnIdle { port.snapshots.value = rootPlayback() }
            onNodeWithTag("player-close").assertIsDisplayed()
            runOnIdle { scope.launch { queue.remove(setOf(ROOT_OCCURRENCE)) } }

            onNodeWithTag("player-close").assertDoesNotExist()
            onNodeWithTag("fixture-page").assertIsDisplayed()
            onNodeWithTag("shell-bottom-bar").assertIsDisplayed()
            runOnIdle { check(backRequests.tryEmit(Unit)) }
            waitUntil { backAtRoot == 1 }
            assertEquals(1, backAtRoot)
        } finally {
            player.close()
            scope.cancel()
        }
    }

    @Test
    fun removingTheCurrentTrackKeepsThePlayerOpenWhileTheSuccessorLoads() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val port = RootPlaybackPort(rootPlayback())
        val player = MusicPlayerController(port, scope)
        val queue = RootQueue(player, port, listOf(ROOT_OCCURRENCE, SUCCESSOR_OCCURRENCE))
        val host = rootHost(queue, scope, MutableSharedFlow(extraBufferCapacity = 1)) { error("No root back expected") }
        try {
            setContent { RootUnderTest(host) }

            onNodeWithTag("compact-mini-player").assertIsDisplayed().performClick()
            onNodeWithTag("player-close").assertIsDisplayed()

            // The queue publishes the successor first; playback still reports the removed occurrence.
            runOnIdle { scope.launch { queue.remove(setOf(ROOT_OCCURRENCE)) } }
            runOnIdle { assertEquals(SUCCESSOR_OCCURRENCE, queue.state.value.currentOccurrenceId) }
            onNodeWithTag("player-close").assertIsDisplayed()
            onNodeWithTag("shell-bottom-bar").assertDoesNotExist()

            runOnIdle { port.snapshots.value = rootPlayback().copy(occurrenceId = SUCCESSOR_OCCURRENCE) }
            onNodeWithTag("player-close").assertIsDisplayed()
        } finally {
            player.close()
            scope.cancel()
        }
    }

    @Composable
    private fun RootUnderTest(host: ArchiveTuneHostBindings) {
        Box(Modifier.size(400.dp, 800.dp)) {
            ArchiveTuneRoot(
                host = host,
                visible = true,
                resumed = true,
                opticalCapabilities = OpticalCapabilities(blur = false, refraction = false),
            )
        }
    }

    private fun rootHost(
        queue: RootQueue,
        scope: CoroutineScope,
        backRequests: MutableSharedFlow<Unit>,
        onBackAtRoot: () -> Unit,
    ) = ArchiveTuneHostBindings(
        queue = queue,
        settings = RootSettings(),
        actionScope = scope,
        routes = emptyFlow(),
        backRequests = backRequests,
        page = { ArchiveGraphPage.Host },
        artwork = { _, modifier -> Box(modifier) },
        newsImage = { _, modifier -> Box(modifier) },
        formatNewsDate = { "Date" },
        librarySourceSelector = {},
        hostScreen = { _, _ -> Box(Modifier.fillMaxSize().testTag("fixture-page")) },
        overlays = {},
        playerSlots = { _, _, _ -> rootPlayerSlots() },
        playerControls = RootPlayerControls(),
        more = {},
        playOccurrence = { _, _ -> error("No playback request expected") },
        openExternal = { error("No external route expected") },
        onFailure = { error("No host failure expected") },
        onBackAtRoot = onBackAtRoot,
        newsAvailable = false,
    )

    private companion object {
        const val ROOT_OCCURRENCE = "track@0"
        const val SUCCESSOR_OCCURRENCE = "track@1"
    }
}

private fun rootPlayback() = PlaybackSnapshot(
    ROOT_PLAYBACK_OCCURRENCE, "song", 1, 0, 12_000, 0, 180_000,
    true, 1f, true, false, false, PlaybackOutputRoute.LOCAL,
)

private const val ROOT_PLAYBACK_OCCURRENCE = "track@0"

@Composable
private fun rootPlayerSlots() = PlayerSlots(
    artwork = { _, modifier -> Box(modifier) },
    canvas = { _, _, modifier -> Box(modifier) },
    customBackground = { _, modifier -> Box(modifier) },
    lyrics = { modifier -> Box(modifier) },
    compactLyrics = { modifier -> Box(modifier) },
    queue = { modifier -> Box(modifier) },
)

private class RootPlaybackPort(initial: PlaybackSnapshot?) : PlaybackPort {
    override val snapshots = MutableStateFlow(initial)
    override val failures = MutableSharedFlow<PlaybackFailure>()
    override fun submit(command: PlaybackCommand) = Unit
    override fun close() = Unit
}

private class RootSettings : SettingsStore {
    override val snapshots = MutableStateFlow<PreferenceSnapshot?>(PreferenceSnapshot.EMPTY)
    override suspend fun edit(change: PreferenceEditor.() -> Unit): SettingsChange {
        val before = requireNotNull(snapshots.value)
        val after = before.toEditor().apply(change).snapshot()
        snapshots.value = after
        return SettingsChange(before, after)
    }
}

private class RootQueue(
    override val player: MusicPlayerController,
    private val port: RootPlaybackPort,
    occurrenceIds: List<String>,
) : ContinuationQueueController {
    private val entries = occurrenceIds.map { id ->
        ContentOccurrence(
            id,
            ContentItem(ContentRef("fixture", ContentKind.SONG, "song-$id"), "Fixture $id", durationMs = 180_000),
        )
    }
    override val state = MutableStateFlow(QueueState(1, entries, entries.first().occurrenceId, occurrenceIds))

    override suspend fun remove(occurrenceIds: Set<String>) {
        val remaining = state.value.entries.filterNot { it.occurrenceId in occurrenceIds }
        state.value = state.value.copy(
            revision = state.value.revision + 1,
            entries = remaining,
            currentOccurrenceId = remaining.firstOrNull()?.occurrenceId,
            playOrder = state.value.playOrder.filterNot { it in occurrenceIds },
        )
        if (remaining.isEmpty()) port.snapshots.value = null
    }

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

private class RootPlayerControls : PlayerNativeControls {
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
