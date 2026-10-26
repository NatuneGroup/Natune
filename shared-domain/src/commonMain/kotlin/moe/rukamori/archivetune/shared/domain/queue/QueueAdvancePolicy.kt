/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.queue

/**
 * The repeat mode a queue applies when a song ends or fails. [storedValue] is the Media3 `REPEAT_MODE_*` integer the
 * Android `RepeatModeKey` preference stores, so a saved choice reads the same on every host.
 */
enum class QueueRepeatMode(val storedValue: Int) {
    OFF(0),
    ONE(1),
    ALL(2),
    ;

    companion object {
        /** Unknown stored values read as [OFF], which is also the Android default. */
        fun fromStoredValue(value: Int): QueueRepeatMode = entries.firstOrNull { it.storedValue == value } ?: OFF
    }
}

/**
 * Pure rules for what plays next after a song ends or fails. Every queue owner uses these, so the hosts cannot drift
 * apart on repeat or on skipping past a broken song.
 */
object QueueAdvancePolicy {
    /**
     * Android's `MAX_CONSECUTIVE_ERR`. Each automatic skip after a failure adds [FAILURE_SCORE_STEP] to the score, and
     * the queue pauses once the score is above this value.
     */
    const val MAX_FAILURE_SCORE = 5

    /** The amount one failure adds to the failure score. Android adds two, so one failure never reaches the limit alone. */
    const val FAILURE_SCORE_STEP = 2

    /**
     * The occurrence that follows [current] in [playOrder] under [mode], or null when the queue stops there. Repeat-one
     * returns [current] itself. Repeat-all wraps from the last occurrence back to the first one in play order. A
     * [current] that is not in [playOrder] has no successor.
     */
    fun successor(mode: QueueRepeatMode, playOrder: List<String>, current: String): String? {
        val position = playOrder.indexOf(current)
        if (position < 0) return null
        return when (mode) {
            QueueRepeatMode.ONE -> current
            QueueRepeatMode.ALL -> playOrder.getOrNull(position + 1) ?: playOrder.first()
            QueueRepeatMode.OFF -> playOrder.getOrNull(position + 1)
        }
    }
}
