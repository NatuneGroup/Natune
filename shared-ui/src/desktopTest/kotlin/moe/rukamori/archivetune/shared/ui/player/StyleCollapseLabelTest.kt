package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.host.LocalAndroidPhonePresentation
import kotlin.test.*

class StyleCollapseLabelTest {
    @Test fun v5() = collapseLabelFollowsTheTap(PlayerDesignStyle.V5)
    @Test fun v9() = collapseLabelFollowsTheTap(PlayerDesignStyle.V9)
    @Test fun v10() = collapseLabelFollowsTheTap(PlayerDesignStyle.V10)
    @Test fun bitChord() = collapseLabelFollowsTheTap(PlayerDesignStyle.BITCHORD)
    @Test fun tikTok() = collapseLabelFollowsTheTap(PlayerDesignStyle.TIKTOK)
    @Test fun flamingo() = collapseLabelFollowsTheTap(PlayerDesignStyle.FLAMINGO)
    @Test fun looper() = collapseLabelFollowsTheTap(PlayerDesignStyle.LOOPER)
    @Test fun metrolist() = collapseLabelFollowsTheTap(PlayerDesignStyle.METROLIST_KMP)
    @Test fun spatialFlow() = collapseLabelFollowsTheTap(PlayerDesignStyle.SPATIALFLOW)
}

/** On a phone, COLLAPSE only collapses from the artwork page; with a panel open the same control reads "Back to player". */
@OptIn(ExperimentalTestApi::class)
private fun collapseLabelFollowsTheTap(style: PlayerDesignStyle) = runComposeUiTest {
    val collapse = "Collapse player"
    val back = "Back to player"
    val ownTag = "player-style-collapse"
    var landscape by mutableStateOf(false)
    val interaction = PlayerInteractionState()
    val actions = PlayerTestActions()
    setContent {
        MaterialTheme {
            CompositionLocalProvider(LocalAndroidPhonePresentation provides true) {
                Box(Modifier.requiredSize(if (landscape) 852.dp else 393.dp, if (landscape) 393.dp else 852.dp)) {
                    BottomSheetPlayer(playerFixture(), actions.callbacks,
                        PreferenceSnapshot.of(PlayerDesignStyleKey to style.name), playerSlots(), false, interaction = interaction)
                }
            }
        }
    }
    for (orientation in listOf(false, true)) {
        val where = "${style.name}, landscape=$orientation"
        // V9 draws its own close only in portrait; in landscape the shared phone close is its only close.
        val ownsClose = style != PlayerDesignStyle.V9 || !orientation
        runOnIdle { landscape = orientation; interaction.panel = PlayerPanel.LYRICS }
        waitForIdle()
        assertTrue(onAllNodesWithContentDescription(collapse).fetchSemanticsNodes().isEmpty(),
            "$where: a panel is open but a control still reads \"$collapse\"")
        if (ownsClose) onNodeWithTag(ownTag).assertContentDescriptionEquals(back)
        runOnIdle { interaction.panel = PlayerPanel.ARTWORK }
        waitForIdle()
        if (ownsClose) onNodeWithTag(ownTag).assertContentDescriptionEquals(collapse)
    }
}
