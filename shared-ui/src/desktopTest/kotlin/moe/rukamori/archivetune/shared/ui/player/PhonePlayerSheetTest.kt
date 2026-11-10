package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.ui.desktop.DesktopExpandedPlayer
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class PhonePlayerSheetTest {
    @Test fun springHasIntermediateFramesAndRetainsContentDuringCollapse() = runComposeUiTest {
        mainClock.autoAdvance = false
        var expanded by mutableStateOf(false)
        lateinit var state: PhonePlayerSheetState
        setContent {
            MaterialTheme {
                state = rememberPhonePlayerSheetState()
                Box(Modifier.requiredSize(393.dp, 740.dp).background(Color.LightGray).testTag("motion-root")) {
                    Text("Home", Modifier.align(Alignment.TopCenter))
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(72.dp).background(Color.Cyan)
                        .clickable { expanded = true }.testTag("motion-mini")) { Text("Get Lucky") }
                    PhonePlayerSheet(expanded, { expanded = it }, true, state) {
                        BottomSheetPlayer(playerFixture(), PlayerTestActions().callbacks, PreferenceSnapshot.EMPTY, playerSlots(), false)
                    }
                }
            }
        }
        fun capture(name: String) {
            val directory = File(System.getProperty("natune.renderDir"), "motion").apply { mkdirs() }
            val bitmap = onNodeWithTag("motion-root").captureToImage()
            Image.makeFromBitmap(bitmap.asSkiaBitmap()).use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use { File(directory, "$name.png").writeBytes(it.bytes) }
            }
        }
        mainClock.advanceTimeByFrame()
        capture("expand-000")
        runOnIdle { expanded = true }
        mainClock.advanceTimeBy(96)
        val first = state.progress
        assertTrue(first > 0f && first < 1f)
        capture("expand-096")
        mainClock.advanceTimeBy(160)
        val second = state.progress
        assertTrue(second > first && second < 1f)
        capture("expand-256")
        mainClock.advanceTimeBy(1500)
        assertEquals(1f, state.progress)
        capture("expanded")
        runOnIdle { expanded = false }
        mainClock.advanceTimeBy(96)
        assertTrue(state.progress > 0f && state.progress < 1f)
        onNodeWithTag("player-V4-portrait").assertExists()
        capture("collapse-096")
        mainClock.advanceTimeBy(1500)
        assertEquals(0f, state.progress)
        onNodeWithTag("phone-player-sheet").assertDoesNotExist()
    }

    @Test fun reducedMotionSnapsAndVerticalDragsExpandAndCollapse() = runComposeUiTest {
        var expanded by mutableStateOf(false)
        lateinit var state: PhonePlayerSheetState
        setContent {
            MaterialTheme {
                state = rememberPhonePlayerSheetState()
                Box(Modifier.requiredSize(393.dp, 740.dp)) {
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(72.dp)
                        .phonePlayerExpandDrag { expanded = true }.testTag("drag-mini"))
                    PhonePlayerSheet(expanded, { expanded = it }, false, state) { Box(Modifier.fillMaxSize()) }
                }
            }
        }
        onNodeWithTag("drag-mini").performTouchInput { swipeUp() }
        runOnIdle { assertTrue(expanded); assertEquals(1f, state.progress) }
        onNodeWithTag("phone-player-sheet").performTouchInput { swipeDown() }
        runOnIdle { assertFalse(expanded); assertEquals(0f, state.progress) }
    }

    @Test fun clearingTheQueueDropsThePreviousSheetPosition() = runComposeUiTest {
        var current by mutableStateOf(true)
        var expanded by mutableStateOf(true)
        lateinit var state: PhonePlayerSheetState
        setContent {
            state = rememberPhonePlayerSheetState()
            LaunchedEffect(current) { if (!current) { expanded = false; state.reset() } }
            Box(Modifier.requiredSize(393.dp, 740.dp)) {
                if (current) PhonePlayerSheet(expanded, { expanded = it }, false, state) { Box(Modifier.fillMaxSize()) }
            }
        }
        runOnIdle { assertEquals(1f, state.progress); current = false }
        runOnIdle { assertEquals(0f, state.progress); current = true }
        onNodeWithTag("phone-player-sheet").assertDoesNotExist()
    }

    @Test fun desktopExpansionHasIntermediateFramesAndKeepsDragForItsContents() = runComposeUiTest {
        mainClock.autoAdvance = false
        var expanded by mutableStateOf(false)
        lateinit var state: PhonePlayerSheetState
        setContent {
            MaterialTheme {
                state = rememberPhonePlayerSheetState()
                Box(Modifier.requiredSize(1000.dp, 700.dp).background(Color.LightGray).testTag("desktop-motion-root")) {
                    Text("Home")
                    PhonePlayerSheet(expanded, { expanded = it }, true, state, dragEnabled = false) {
                        DesktopExpandedPlayer(playerFixture(), playerSlots(), {}, {}, {}, {}, {})
                    }
                }
            }
        }
        fun capture(name: String) {
            val directory = File(System.getProperty("natune.renderDir"), "motion").apply { mkdirs() }
            val bitmap = onNodeWithTag("desktop-motion-root").captureToImage()
            Image.makeFromBitmap(bitmap.asSkiaBitmap()).use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use { File(directory, "$name.png").writeBytes(it.bytes) }
            }
        }
        mainClock.advanceTimeByFrame()
        capture("desktop-expand-000")
        runOnIdle { expanded = true }
        mainClock.advanceTimeBy(96)
        val first = state.progress
        assertTrue(first > 0f && first < 1f)
        capture("desktop-expand-096")
        mainClock.advanceTimeBy(160)
        assertTrue(state.progress > first && state.progress < 1f)
        capture("desktop-expand-256")
        mainClock.advanceTimeBy(1500)
        assertEquals(1f, state.progress)
        capture("desktop-expanded")
        onNodeWithTag("phone-player-sheet").performTouchInput { swipeDown() }
        runOnIdle { assertTrue(expanded); assertEquals(1f, state.progress) }
    }
}
