package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.settings.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class PlayerReviewRegressionTest {
    @Test fun continuedSeekGestureCannotRetargetAfterAcceptedIdentityChanges() {
        val a=playerFixture()
        for(snapshot in listOf(
            a.playback!!.copy(occurrenceId="track@2"),
            a.playback.copy(playbackGeneration=2),
            a.playback.copy(discontinuityGeneration=1),
        )) {
            val interaction=PlayerInteractionState()
            val b=a.copy(playback=snapshot)
            val seeks=mutableListOf<Long>()
            interaction.preview(.3f,a)
            interaction.reconcile(snapshot)
            interaction.preview(.7f,b) // The same finger is still down.
            assertNull(interaction.previewMs)
            interaction.commit(b,seeks::add)
            assertTrue(seeks.isEmpty(),"The old gesture sought the new identity")
            interaction.preview(.5f,b) // A fresh gesture after release is valid.
            interaction.commit(b,seeks::add)
            assertEquals(listOf(90000L),seeks)
        }
    }

    @Test fun continuedSeekGestureRejectsIdentityChangeBeforeReconcileRuns() {
        val interaction=PlayerInteractionState()
        val a=playerFixture()
        val b=a.copy(playback=a.playback!!.copy(occurrenceId="track@2"))
        interaction.preview(.2f,a)
        interaction.preview(.8f,b)
        interaction.commit(b) { fail("Gesture rebound before reconciliation") }
    }

    @Test fun cancelledArtworkDragFollowsPlaybackThatChangedDuringTouchWithoutSelecting() = runComposeUiTest {
        var state by mutableStateOf(playerFixture())
        var touchLeafMounted by mutableStateOf(true)
        val callbacks=PlayerTestActions()
        lateinit var sync: PlayerArtworkPagerState
        setContent {
            MaterialTheme {
                sync=rememberPlayerArtworkPager(state,callbacks.callbacks)
                if(touchLeafMounted) {
                    HorizontalPager(sync.pager,Modifier.requiredSize(400.dp).testTag("review-pager")) { page ->
                        Text(state.occurrenceIds[page])
                    }
                }
            }
        }
        onNodeWithTag("review-pager").performTouchInput {
            down(center)
            moveTo(center.copy(x=center.x+80f),delayMillis=200)
        }
        runOnIdle {
            assertTrue(sync.dragged)
            state=state.copy(playback=state.playback!!.copy(occurrenceId="track@2",playbackGeneration=2))
        }
        runOnIdle { assertNotEquals(2,sync.pager.currentPage) }
        onNodeWithTag("review-pager").performTouchInput { cancel() }
        // Skiko's synthetic cancel alone leaves the foundation drag active. Detaching the touch
        // leaf sends the real DragInteraction.Cancel while retaining the ONE hoisted pager owner.
        runOnIdle { touchLeafMounted=false }
        runOnIdle {
            assertTrue(sync.cancelledDragGeneration>0,"Foundation drag was not cancelled")
            touchLeafMounted=true
        }
        waitForIdle()
        runOnIdle {
            assertEquals(2,sync.pager.currentPage,
                "cancelled=${sync.cancelledDragGeneration}, dragged=${sync.dragged}, scrolling=${sync.pager.isScrollInProgress}, selected=${callbacks.selected}")
            assertEquals(2,sync.pager.settledPage)
            assertFalse(sync.dragged)
            assertFalse(sync.programmatic)
            assertTrue(callbacks.selected.isEmpty())
        }
    }

    @Test fun flamingoTransportAndSeekRemainReachableOnLyricsAndQueueInBothOrientations() = runComposeUiTest {
        var wide by mutableStateOf(false)
        val callbacks=PlayerTestActions()
        setContent {
            MaterialTheme {
                key(wide) {
                    Box(Modifier.requiredSize(if(wide) 1000.dp else 400.dp,if(wide) 600.dp else 740.dp)) {
                        BottomSheetPlayer(playerFixture(),callbacks.callbacks,
                            PreferenceSnapshot.of(PlayerDesignStyleKey to "FLAMINGO"),playerSlots(),true)
                    }
                }
            }
        }
        for(landscape in listOf(false,true)) {
            runOnIdle { wide=landscape }
            if(landscape) onNodeWithTag("lyrics-owner-slot").assertExists()
            for(panel in listOf("Lyrics","Queue")) {
                onNodeWithText(panel).performClick()
                onNodeWithContentDescription("Pause").assertIsDisplayed().performClick()
                onNodeWithContentDescription("Previous track").assertIsDisplayed().performClick()
                onNodeWithContentDescription("Next track").assertIsDisplayed().performClick()
                onNodeWithContentDescription("Playback position").assertIsDisplayed()
                    .performSemanticsAction(SemanticsActions.SetProgress) { it(.4f) }
            }
        }
        runOnIdle {
            assertEquals(4,callbacks.plays)
            assertEquals(4,callbacks.actions.count { it==PlayerAction.PREVIOUS })
            assertEquals(4,callbacks.actions.count { it==PlayerAction.NEXT })
            assertEquals(List(4) { 72000L },callbacks.seeks)
        }
    }

    @Test fun looperShuffleAndRepeatDispatchOnceAndReflectAcceptedSelection() = runComposeUiTest {
        var state by mutableStateOf(playerFixture().copy(shuffle=true,repeat=PlayerRepeat.ONE))
        val callbacks=PlayerTestActions()
        setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(400.dp,740.dp)) {
                    BottomSheetPlayer(state,callbacks.callbacks,
                        PreferenceSnapshot.of(PlayerDesignStyleKey to "LOOPER"),playerSlots(),true)
                }
            }
        }
        onNodeWithContentDescription("Shuffle").performScrollTo().assertIsSelected().performClick()
        onNodeWithContentDescription("Repeat").performScrollTo().assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Repeat one")).performClick()
        runOnIdle {
            assertEquals(listOf(PlayerAction.SHUFFLE,PlayerAction.REPEAT),callbacks.actions)
            state=state.copy(shuffle=false,repeat=PlayerRepeat.OFF)
        }
        onNodeWithContentDescription("Shuffle").assertIsNotSelected()
        onNodeWithContentDescription("Repeat").assertIsNotSelected()
    }

    @Test fun hidingOrdinarySleevesDeactivatesCanvasWithoutPlaybackChanges() = runComposeUiTest {
        var style by mutableStateOf(PlayerDesignStyle.APPLE_MUSIC)
        var hidden by mutableStateOf(false)
        val callbacks=PlayerTestActions()
        val state=playerFixture().copy(canvasAvailable=true)
        setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(400.dp,740.dp)) {
                    BottomSheetPlayer(state,callbacks.callbacks,
                        PreferenceSnapshot.of(PlayerDesignStyleKey to style.name,HidePlayerThumbnailKey to hidden),
                        playerSlots(),true)
                }
            }
        }
        for(design in listOf(PlayerDesignStyle.APPLE_MUSIC,PlayerDesignStyle.SPATIALFLOW,
            PlayerDesignStyle.LOOPER,PlayerDesignStyle.FLAMINGO)) {
            runOnIdle { style=design; hidden=false }
            onNodeWithTag("canvas-true").assertExists()
            runOnIdle { hidden=true }
            onNodeWithTag("canvas-true").assertDoesNotExist()
            onNodeWithTag("canvas-false").assertExists()
        }
        runOnIdle {
            assertTrue(callbacks.actions.isEmpty())
            assertTrue(callbacks.seeks.isEmpty())
            assertEquals(0,callbacks.plays)
        }
    }
}
