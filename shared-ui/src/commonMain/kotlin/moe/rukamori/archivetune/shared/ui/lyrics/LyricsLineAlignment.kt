/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.lyrics

/** Where one lyric row sits across the width of its list. */
enum class LyricsLineAlignment {
    START,
    CENTER,
    END,
}

/**
 * Chooses each row's alignment for one look. The rules are the Android renderers' own, so a duet reads the same on
 * every surface. Agent names compare without case, as they do on Android.
 */
object LyricsAlignmentPolicy {
    private const val FIRST_VOICE = "v1"
    private const val SECOND_VOICE = "v2"

    fun of(mode: LyricsMode, row: LyricsRenderRow): LyricsLineAlignment {
        val agent = row.agent?.lowercase()
        return when (mode) {
            // Enhanced and Spotify move only word-timed second-voice lines to the end. Line-only text starts.
            LyricsMode.ENHANCED, LyricsMode.SPOTIFY ->
                if (row.words != null && agent == SECOND_VOICE) LyricsLineAlignment.END else LyricsLineAlignment.START

            LyricsMode.V2 -> when (agent) {
                null, FIRST_VOICE -> LyricsLineAlignment.START
                SECOND_VOICE -> LyricsLineAlignment.END
                else -> LyricsLineAlignment.CENTER
            }

            // The SimpMusic look follows the script: right-to-left text ends on the right.
            LyricsMode.SIMPMUSIC -> if (row.isRtl) LyricsLineAlignment.END else LyricsLineAlignment.START
        }
    }
}
