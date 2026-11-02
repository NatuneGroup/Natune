/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.constants

import android.os.Bundle
import androidx.media3.session.SessionCommand

object MediaSessionConstants {
    const val ACTION_TOGGLE_LIBRARY = "TOGGLE_LIBRARY"
    const val ACTION_TOGGLE_START_RADIO = "TOGGLE_START_RADIO"
    const val ACTION_TOGGLE_LIKE = "TOGGLE_LIKE"
    const val ACTION_TOGGLE_SHUFFLE = "TOGGLE_SHUFFLE"
    const val ACTION_TOGGLE_REPEAT_MODE = "TOGGLE_REPEAT_MODE"
    const val ACTION_SEEK_BACK_30 = "SEEK_BACK_30"
    const val ACTION_SEEK_FORWARD_30 = "SEEK_FORWARD_30"
    const val SEEK_STEP_30_MS = 30_000L
    val CommandToggleLibrary = SessionCommand(ACTION_TOGGLE_LIBRARY, Bundle.EMPTY)
    val CommandToggleLike = SessionCommand(ACTION_TOGGLE_LIKE, Bundle.EMPTY)
    val CommandToggleStartRadio = SessionCommand(ACTION_TOGGLE_START_RADIO, Bundle.EMPTY)
    val CommandToggleShuffle = SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY)
    val CommandToggleRepeatMode = SessionCommand(ACTION_TOGGLE_REPEAT_MODE, Bundle.EMPTY)
    val CommandSeekBack30 = SessionCommand(ACTION_SEEK_BACK_30, Bundle.EMPTY)
    val CommandSeekForward30 = SessionCommand(ACTION_SEEK_FORWARD_30, Bundle.EMPTY)

    val AllCustomCommands =
        listOf(
            CommandToggleLike,
            CommandToggleStartRadio,
            CommandToggleLibrary,
            CommandToggleShuffle,
            CommandToggleRepeatMode,
            CommandSeekBack30,
            CommandSeekForward30,
        )
}
