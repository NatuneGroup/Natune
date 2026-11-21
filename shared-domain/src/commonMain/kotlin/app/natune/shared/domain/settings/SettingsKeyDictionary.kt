package app.natune.shared.domain.settings

/**
 * How a setting name written by an older build (or another player's backup) maps onto what this build stores.
 *
 * - When a key is renamed, add `"oldName" to "newName"` to [renamed]. Never remove or edit an entry: old backups keep
 *   the old name forever. Point an old name at the current name directly; chains are rejected by the tests.
 * - When a setting is removed, add its name to [retired] so a restore drops it instead of leaving a dead value.
 */
object SettingsKeyDictionary {
    val renamed: Map<String, String> = emptyMap()

    /** Settings older backups carry whose feature is gone; found by diffing real ArchiveTune/Natune backups. */
    val retired: Set<String> =
        setOf(
            // Paxsenix proxy lyrics providers, removed with their providers.
            "enablePaxsenixMusixmatchLyrics",
            "enablePaxsenixNeteaseLyrics",
            "enablePaxsenixSpotifyLyrics",
            "enablePaxsenixYouTubeLyrics",
            "enableLyprioLyrics",
            // The lyrics-screen player controls before the current lyrics page.
            "showLyricsPlayerControls",
            "autoHideLyricsPlayerControls",
            // The first Listen Together client, replaced by the listenTogether* keys.
            "together_client_id",
            "together_last_join_link",
            "together_welcome_shown",
            "dev_manual_source_login",
            "apple_music_headers",
            "spotifyHomeStyle",
            "updateChannelNightlyRenameMigrated",
        )

    /** The name to store [name] under, or null when the setting no longer exists. */
    fun currentName(name: String): String? = if (name in retired) null else renamed[name] ?: name
}
