package moe.rukamori.archivetune.shared.ui.player

import kotlin.test.*

class PlayerControlMotionTest {
    @Test fun repeatSpinsIntoAllPopsIntoOneDipsIntoOff() {
        assertEquals(PlayerControlGesture.Spin, playerControlGesture(PlayerControlKind.Repeat, PlayerRepeat.OFF, PlayerRepeat.ALL))
        assertEquals(PlayerControlGesture.Pop, playerControlGesture(PlayerControlKind.Repeat, PlayerRepeat.ALL, PlayerRepeat.ONE))
        assertEquals(PlayerControlGesture.Dip, playerControlGesture(PlayerControlKind.Repeat, PlayerRepeat.ONE, PlayerRepeat.OFF))
    }

    @Test fun togglesAnswerTheirState() {
        assertEquals(PlayerControlGesture.Swap, playerControlGesture(PlayerControlKind.Shuffle, false, true))
        assertEquals(PlayerControlGesture.Dip, playerControlGesture(PlayerControlKind.Shuffle, true, false))
        assertEquals(PlayerControlGesture.Pop, playerControlGesture(PlayerControlKind.Like, false, true))
        assertEquals(PlayerControlGesture.Dip, playerControlGesture(PlayerControlKind.Like, true, false))
        assertEquals(PlayerControlGesture.Squash,
            playerControlGesture(PlayerControlKind.PlayPause, PlayerSymbol.PLAY, PlayerSymbol.PAUSE))
    }

    @Test fun unchangedStateNeverAnimates() {
        for (kind in PlayerControlKind.entries) {
            assertNull(playerControlGesture(kind, true, true))
            assertNull(playerControlGesture(kind, PlayerRepeat.ALL, PlayerRepeat.ALL))
        }
    }

    @Test fun skipsMoveOnlyOnTapInTheirDirection() {
        assertNull(playerControlGesture(PlayerControlKind.Next, null, Unit))
        assertEquals(PlayerControlGesture.NudgeForward, playerControlTapGesture(PlayerControlKind.Next))
        assertEquals(PlayerControlGesture.NudgeBackward, playerControlTapGesture(PlayerControlKind.Previous))
        for (kind in listOf(PlayerControlKind.PlayPause, PlayerControlKind.Shuffle, PlayerControlKind.Repeat, PlayerControlKind.Like)) {
            assertNull(playerControlTapGesture(kind))
        }
    }
}
