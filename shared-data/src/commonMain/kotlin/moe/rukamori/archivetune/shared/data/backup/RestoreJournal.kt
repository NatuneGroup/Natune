package moe.rukamori.archivetune.shared.data.backup

/**
 * The durable progress of a staged restore's file swap, one line per step. A host journals a step BEFORE the move it
 * names, so a process that dies between the two finds an interrupted swap it can complete, and the files kept under
 * `previous/` are never unaccounted for:
 *
 * - `moved:<suffix>`: the live `<database><suffix>` file was set aside into `previous/` (empty suffix = the file
 *   itself, then `-wal`, `-shm`, `-journal`).
 * - `swapped`: the staged database was promoted into the live slot.
 * - `settings-staged`: the pre-restore settings snapshot was written to `previous/settings.json`, so a failed
 *   settings apply can put back exactly what it replaced.
 *
 * The file is rewritten whole on every step (it never exceeds a handful of lines), fsynced through a
 * `journal.work` sibling and renamed, which makes each step atomic.
 */
class RestoreJournal(
    val movedAside: MutableList<String> = mutableListOf(),
    var swapped: Boolean = false,
    var settingsStaged: Boolean = false,
) {
    fun noteMoved(suffix: String) {
        if (suffix !in movedAside) movedAside += suffix
    }

    fun encode(): String =
        (movedAside.map { "$MOVED_PREFIX$it" } +
            listOfNotNull(
                if (swapped) SWAPPED else null,
                if (settingsStaged) SETTINGS_STAGED else null,
            ) + "").joinToString("\n")

    companion object {
        private const val MOVED_PREFIX = "moved:"
        private const val SWAPPED = "swapped"
        private const val SETTINGS_STAGED = "settings-staged"

        /** The journal [text] describes; a malformed line makes the journal unreadable rather than half-trusted. */
        fun decode(text: String): RestoreJournal {
            val journal = RestoreJournal()
            text.lines().forEach { line ->
                when {
                    line.isEmpty() -> Unit
                    line.startsWith(MOVED_PREFIX) -> journal.movedAside += line.removePrefix(MOVED_PREFIX)
                    line == SWAPPED -> journal.swapped = true
                    line == SETTINGS_STAGED -> journal.settingsStaged = true
                    else -> throw IllegalArgumentException("Bad restore journal line")
                }
            }
            return journal
        }
    }
}
