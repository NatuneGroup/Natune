package moe.rukamori.archivetune.shared.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.playback.PlaybackCommand
import moe.rukamori.archivetune.shared.domain.playback.PlaybackFailure
import moe.rukamori.archivetune.shared.domain.playback.PlaybackPort
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.playback.PlaybackOutputRoute
import moe.rukamori.archivetune.shared.domain.repository.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerOccurrenceTest {
    private val first = ContentOccurrence("first", ContentItem(ContentRef("youtube", ContentKind.SONG, "same"), "First occurrence"))
    private val second = ContentOccurrence("second", first.item.copy(title = "Second occurrence"))
    private val queue = QueueState(1, listOf(first, second), "second", listOf("first", "second"))
    private fun snapshot(id: String) = PlaybackSnapshot(id, "same", 1, 0, 0, 0, null, true, 1f,
        true, true, false, PlaybackOutputRoute.LOCAL)

    @Test fun queueAdvanceDoesNotChangeThePlayingItemsActions() {
        assertEquals(first, playerOccurrence(queue, snapshot("first")))
        assertEquals(second, playerOccurrence(queue, snapshot("second")))
    }
    @Test fun missingPlaybackEntryNeverBorrowsAnotherOccurrencesMetadata() {
        assertNull(playerOccurrence(queue, snapshot("removed")))
        assertEquals(second, playerOccurrence(queue, null))
    }
    @Test fun sheetShowsTheQueuedSuccessorWithoutTheRemovedTracksSnapshot() {
        val state = queuePlayerState(queue, snapshot("removed"), active = true)
        assertEquals("Second occurrence", state.title)
        assertNull(state.playback)
        assertEquals(0L, state.positionMs)
        assertEquals(snapshot("first"), queuePlayerState(queue, snapshot("first"), active = true).playback)
    }

    @Test fun standInToggleSendsPlayWithoutPausingTheRemovedOccurrence() = runTest {
        val removed = snapshot("removed")
        val port = OccurrencePlaybackPort(removed)
        val player = MusicPlayerController(port, backgroundScope)
        try {
            runCurrent()
            assertEquals(removed, player.snapshot.value)
            val state = queuePlayerState(queue, player.snapshot.value, active = true)
            assertNull(state.playback)

            // The mini player and both desktop surfaces toggle this displayed snapshot.
            player.togglePlayPause(state.playback)
            assertEquals(listOf<PlaybackCommand>(PlaybackCommand.Play), port.commands)
        } finally {
            player.close()
        }
    }

    @Test fun sheetToggleUsesTheDisplayedPlaybackAcrossStandInAndRecovery() = runTest {
        val port = OccurrencePlaybackPort(snapshot("removed"))
        val player = MusicPlayerController(port, backgroundScope)
        try {
            runCurrent()
            var displayed = queuePlayerState(queue, player.snapshot.value, active = true)
            val actions = controllerPlayerActions(
                controller = player,
                selectOccurrence = {},
                setVolume = {},
                onAction = {},
                onLyricsVisibility = {},
                displayedPlayback = { displayed.playback },
            )
            actions.playPause()
            assertEquals(listOf<PlaybackCommand>(PlaybackCommand.Play), port.commands)

            // The remembered actions must follow the display when the successor finally reports playback.
            port.snapshots.value = snapshot("second").copy(playbackGeneration = 2)
            runCurrent()
            displayed = queuePlayerState(queue, player.snapshot.value, active = true)
            assertEquals("second", displayed.playback?.occurrenceId)
            actions.playPause()
            assertEquals(listOf<PlaybackCommand>(PlaybackCommand.Play, PlaybackCommand.Pause), port.commands)
        } finally {
            player.close()
        }
    }

    @Test fun artworkPagerSelectsTheSuccessorWhilePlaybackIsRemovedOrUnavailable() {
        for (playback in listOf(snapshot("removed"), null)) {
            val state = queuePlayerState(queue, playback, active = true)
            assertNull(state.playback)
            assertEquals("second", state.displayedOccurrenceId)
            assertEquals(1, artworkPageIndex(state))
        }
    }

    @Test fun artworkPagerFollowsMatchingPlaybackBeforeTheQueuedSuccessor() {
        val state = queuePlayerState(queue, snapshot("first"), active = true)
        assertEquals("first", state.displayedOccurrenceId)
        assertEquals(0, artworkPageIndex(state))
    }

    @Test fun settlingOnTheStandInPageDoesNotReselectTheSuccessor() {
        val state = queuePlayerState(queue, snapshot("removed"), active = true)
        assertNull(settledArtworkOccurrence(state.occurrenceIds, state.displayedOccurrenceId,
            settledPage = 1, programmatic = false, userDragged = true))
    }
}

private class OccurrencePlaybackPort(initial: PlaybackSnapshot) : PlaybackPort {
    override val snapshots = MutableStateFlow<PlaybackSnapshot?>(initial)
    override val failures = MutableSharedFlow<PlaybackFailure>()
    val commands = mutableListOf<PlaybackCommand>()
    override fun submit(command: PlaybackCommand) { commands += command }
    override fun close() = Unit
}
