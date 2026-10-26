package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import moe.rukamori.archivetune.shared.domain.lyrics.SyncedLine
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.lyrics.*
import kotlin.test.*
import androidx.compose.runtime.setValue

/** Real M3 renderer composition, with static accepted-snapshot fixtures; not a native/disk flow. */
@OptIn(ExperimentalTestApi::class)
class PlayerLyricsSlotsTest {
    @Test fun realFullscreenLyricsStayMountedAndSeekThroughPlayerAcrossLiveLooks() = runComposeUiTest {
        var style by mutableStateOf(PlayerDesignStyle.V4)
        val playback=MutableStateFlow(playerFixture().playback)
        val romanization=LyricsRomanizationState(LyricsRomanizationPresentation.off("session"))
        val callbacks=PlayerTestActions()
        val interaction=PlayerInteractionState().apply { panel=PlayerPanel.LYRICS }
        setContent {
            MaterialTheme {
                val lyrics=playerLyricsSlots(
                    listOf(SyncedLine(10000,"Adapter line"),SyncedLine(14000,"Next line")),
                    "session",playback,romanization,LyricsSurfaceSettings(reduceMotion=true),
                    LyricsSurfaceActions(callbacks.callbacks.seekTo,{}),LyricsCompactStyle.SIMPMUSIC,
                    fullVisible=true,compactVisible=false,resumed=true,
                )
                val media=playerSlots()
                val slots=PlayerSlots(media.artwork,media.canvas,media.customBackground,
                    lyrics.full,lyrics.compact,media.queue)
                Box(Modifier.requiredSize(400.dp,740.dp)) {
                    BottomSheetPlayer(playerFixture(),callbacks.callbacks,
                        PreferenceSnapshot.of(PlayerDesignStyleKey to style.name),slots,true,interaction=interaction)
                }
            }
        }
        val originalList=onNodeWithTag("lyrics-list").fetchSemanticsNode().id
        for(design in listOf(PlayerDesignStyle.V4,PlayerDesignStyle.APPLE_MUSIC,PlayerDesignStyle.METROLIST_KMP)) {
            runOnIdle { style=design }
            onNodeWithTag("lyrics-row-0").assertIsDisplayed().performClick()
            assertEquals(originalList,onNodeWithTag("lyrics-list").fetchSemanticsNode().id)
        }
        runOnIdle { assertEquals(List(3) { 10000L },callbacks.seeks) }
    }

    @Test fun realCompactStripsFollowAcceptedPlaybackAndLiveTapSetting() = runComposeUiTest {
        var compactStyle by mutableStateOf(LyricsCompactStyle.BITCHORD)
        var tapToSeek by mutableStateOf(true)
        val playback=MutableStateFlow(playerFixture().playback)
        val romanization=LyricsRomanizationState(LyricsRomanizationPresentation.off("session"))
        val seeks=mutableListOf<Long>()
        setContent {
            MaterialTheme {
                val lyrics=playerLyricsSlots(
                    listOf(SyncedLine(10000,"Adapter line"),SyncedLine(14000,"Next line")),
                    "session",playback,romanization,
                    LyricsSurfaceSettings(tapToSeek=tapToSeek,reduceMotion=true),
                    LyricsSurfaceActions(seeks::add,{}),compactStyle,
                    fullVisible=false,compactVisible=true,resumed=true,
                )
                Box(Modifier.requiredSize(400.dp,150.dp)) { lyrics.compact(Modifier.fillMaxWidth()) }
            }
        }
        for(design in LyricsCompactStyle.entries) {
            runOnIdle { compactStyle=design }
            onNodeWithText("Adapter line").assertIsDisplayed().performTouchInput { click() }
        }
        runOnIdle {
            assertEquals(listOf(10000L,10000L),seeks)
            tapToSeek=false
            playback.value=playback.value!!.copy(positionMs=14500,discontinuityGeneration=1)
        }
        onNodeWithText("Next line").assertIsDisplayed().performTouchInput { click() }
        runOnIdle { assertEquals(listOf(10000L,10000L),seeks) }
    }
}
