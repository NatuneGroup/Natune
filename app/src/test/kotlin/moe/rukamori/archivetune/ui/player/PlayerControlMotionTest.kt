package moe.rukamori.archivetune.ui.player

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerControlMotionTest {
    @Test
    fun repeat_spinsIntoAll_popsIntoOne_dipsIntoOff() {
        assertEquals(
            PlayerControlGesture.Spin,
            playerControlGesture(PlayerControlKind.Repeat, Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ALL),
        )
        assertEquals(
            PlayerControlGesture.Pop,
            playerControlGesture(PlayerControlKind.Repeat, Player.REPEAT_MODE_ALL, Player.REPEAT_MODE_ONE),
        )
        assertEquals(
            PlayerControlGesture.Dip,
            playerControlGesture(PlayerControlKind.Repeat, Player.REPEAT_MODE_ONE, Player.REPEAT_MODE_OFF),
        )
    }

    @Test
    fun toggles_answerTheirState() {
        assertEquals(PlayerControlGesture.Swap, playerControlGesture(PlayerControlKind.Shuffle, false, true))
        assertEquals(PlayerControlGesture.Dip, playerControlGesture(PlayerControlKind.Shuffle, true, false))
        assertEquals(PlayerControlGesture.Pop, playerControlGesture(PlayerControlKind.Like, false, true))
        assertEquals(PlayerControlGesture.Dip, playerControlGesture(PlayerControlKind.Like, true, false))
        assertEquals(PlayerControlGesture.Squash, playerControlGesture(PlayerControlKind.PlayPause, false, true))
        assertEquals(PlayerControlGesture.Squash, playerControlGesture(PlayerControlKind.PlayPause, true, false))
    }

    @Test
    fun unchangedState_neverAnimates() {
        PlayerControlKind.entries.forEach { kind ->
            assertNull(playerControlGesture(kind, true, true))
            assertNull(playerControlGesture(kind, Player.REPEAT_MODE_ALL, Player.REPEAT_MODE_ALL))
        }
    }

    @Test
    fun skips_moveOnlyOnTap_inTheirDirection() {
        assertNull(playerControlGesture(PlayerControlKind.Next, null, Unit))
        assertNull(playerControlGesture(PlayerControlKind.Previous, null, Unit))
        assertEquals(PlayerControlGesture.NudgeForward, playerControlTapGesture(PlayerControlKind.Next))
        assertEquals(PlayerControlGesture.NudgeBackward, playerControlTapGesture(PlayerControlKind.Previous))
        listOf(
            PlayerControlKind.PlayPause,
            PlayerControlKind.Shuffle,
            PlayerControlKind.Repeat,
            PlayerControlKind.Like,
        ).forEach { assertNull(playerControlTapGesture(it)) }
    }
}
