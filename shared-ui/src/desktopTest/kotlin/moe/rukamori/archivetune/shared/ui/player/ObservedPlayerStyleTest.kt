package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import moe.rukamori.archivetune.shared.domain.playback.*
import moe.rukamori.archivetune.shared.domain.queue.UserQueuedKind
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.settings.*
import kotlin.test.*
import androidx.compose.runtime.setValue

/** Component observation/dispatch tests, NOT disk restart or real-engine/Enhanced end-to-end evidence. */
@OptIn(ExperimentalTestApi::class)
class ObservedPlayerStyleTest {
    @Test fun replacingSettingsWithUnreadOwnerNeverRendersPriorPreferencesOrSlots() = runComposeUiTest {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val controller=MusicPlayerController(TestPort(),scope)
        val queue=TestQueue(controller)
        var settings by mutableStateOf(TestSettings().apply {
            snapshots.value=PreferenceSnapshot.of(PlayerDesignStyleKey to "APPLE_MUSIC")
        })
        var owner by mutableStateOf(0)
        val rendered=mutableListOf<Int>()
        try {
            setContent {
                MaterialTheme {
                    ObservedBottomSheetPlayer(queue,settings,scope,playerSlots(),true,true,
                        { rendered+=owner; it.copy(reducedMotion=true) },{}, {}, {})
                }
            }
            onNodeWithTag("player-APPLE_MUSIC-landscape").assertExists()
            runOnIdle { settings=TestSettings(); owner=1 }
            onNodeWithTag("player-APPLE_MUSIC-landscape").assertDoesNotExist()
            runOnIdle { assertFalse(1 in rendered,"New unread settings rendered the previous owner's player") }
            runOnIdle { settings.snapshots.value=PreferenceSnapshot.of(PlayerDesignStyleKey to "METROLIST_KMP") }
            onNodeWithTag("player-METROLIST_KMP-landscape").assertExists()
        } finally { controller.close(); scope.cancel() }
    }

    @Test fun replacingQueueAndControllerNeverProjectsPreviousOwnersSnapshot() = runComposeUiTest {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val oldController=MusicPlayerController(TestPort(),scope)
        val newController=MusicPlayerController(TestPort(null),scope)
        var queue by mutableStateOf(TestQueue(oldController))
        var owner by mutableStateOf(0)
        val rendered=mutableListOf<Pair<Int,PlayerState>>()
        val settings=TestSettings().apply { snapshots.value=PreferenceSnapshot.of(PlayerDesignStyleKey to "V4") }
        try {
            setContent {
                MaterialTheme {
                    ObservedBottomSheetPlayer(queue,settings,scope,playerSlots(),true,true,
                        { rendered+=owner to it; it.copy(reducedMotion=true) },{}, {}, {})
                }
            }
            runOnIdle {
                assertTrue(rendered.isNotEmpty())
                queue=TestQueue(newController).apply {
                    state.value=QueueState(2,listOf(ContentOccurrence("new@0",
                        ContentItem(ContentRef("fixture",ContentKind.SONG,"new-media"),"New owner"))),
                        "new@0",listOf("new@0"))
                }
                owner=1
            }
            waitForIdle()
            runOnIdle {
                val replacement=rendered.filter { it.first==1 }.map { it.second }
                assertTrue(replacement.isNotEmpty())
                assertTrue(replacement.all { it.title=="New owner" && it.playback==null },
                    "New queue/controller projected previous-owner data: $replacement")
            }
        } finally { oldController.close(); newController.close(); scope.cancel() }
    }

    @Test fun waitsForPersistedReadAndUsesOneStoreAcrossLiveStylesAndRemount() = runComposeUiTest {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val port=TestPort()
        val controller=MusicPlayerController(port,scope)
        val queue=TestQueue(controller)
        val settings=TestSettings()
        var mounted by mutableStateOf(true)
        try {
            setContent {
                MaterialTheme {
                    if(mounted) ObservedBottomSheetPlayer(queue,settings,scope,playerSlots(),true,true,
                        { it.copy(reducedMotion=true) },{}, {}, {})
                }
            }
            onNodeWithTag("player-V4-landscape").assertDoesNotExist()
            for(style in listOf("V4","APPLE_MUSIC","METROLIST_KMP")) {
                runOnIdle { settings.snapshots.value=PreferenceSnapshot.of(PlayerDesignStyleKey to style) }
                onNodeWithTag("player-$style-landscape").assertExists()
            }
            runOnIdle { mounted=false }
            waitForIdle()
            runOnIdle { mounted=true }
            onNodeWithTag("player-METROLIST_KMP-landscape").assertExists()
            onNodeWithContentDescription("Pause").performScrollTo().performClick()
            runOnIdle { assertEquals(listOf<PlaybackCommand>(PlaybackCommand.Pause),port.commands) }
        } finally { controller.close(); scope.cancel() }
    }

    @Test fun playOrderProjectionKeepsDuplicateOccurrenceMetadataAndBoundaries() {
        val controllerScope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val controller=MusicPlayerController(TestPort(),controllerScope)
        try {
            val queue=TestQueue(controller).state.value
            val state=queuePlayerState(queue,playerFixture().playback,false)
            assertEquals(listOf("track@1","track@0"),state.occurrenceIds)
            assertEquals("Second occurrence",state.title)
            assertFalse(state.canPrevious)
            assertTrue(state.canNext)
            assertFalse(state.active)
        } finally { controller.close(); controllerScope.cancel() }
    }
}

private class TestSettings: SettingsStore {
    override val snapshots=MutableStateFlow<PreferenceSnapshot?>(null)
    override suspend fun edit(change: PreferenceEditor.()->Unit): SettingsChange {
        val before=requireNotNull(snapshots.value)
        val after=before.toEditor().apply(change).snapshot()
        snapshots.value=after
        return SettingsChange(before,after)
    }
}

private class TestPort(initial: PlaybackSnapshot? = playerFixture().playback): PlaybackPort {
    override val snapshots=MutableStateFlow(initial)
    override val failures=MutableSharedFlow<PlaybackFailure>()
    val commands=mutableListOf<PlaybackCommand>()
    override fun submit(command: PlaybackCommand) { commands+=command }
    override fun close() {}
}

private class TestQueue(override val player: MusicPlayerController): QueueController {
    private val ref=ContentRef("fixture",ContentKind.SONG,"same-media")
    override val state=MutableStateFlow(QueueState(1,
        listOf(ContentOccurrence("track@0",ContentItem(ref,"First occurrence")),
            ContentOccurrence("track@1",ContentItem(ref,"Second occurrence"))),
        "track@1",listOf("track@1","track@0")))
    override suspend fun select(occurrenceId: String,positionMs: Long) { state.value=state.value.copy(currentOccurrenceId=occurrenceId) }
    override suspend fun replace(entries: List<ContentOccurrence>,startOccurrenceId: String,playWhenReady: Boolean) { error("not called") }
    override suspend fun insert(entries: List<ContentOccurrence>,kind: UserQueuedKind): Int { error("not called") }
    override suspend fun remove(occurrenceIds: Set<String>) { error("not called") }
    override suspend fun move(occurrenceId: String,beforeOccurrenceId: String?) { error("not called") }
}
