package moe.rukamori.archivetune.db

/** Kept pure so the shipped Android queries and legacy data move can also be exercised on bundled SQLite. */
object SongMetadataOverrideSql {
    private const val MATCH =
        "inLibrary IS NOT NULL AND (" +
            "COALESCE((SELECT o.title FROM song_metadata_override o WHERE o.songId = song.id), song.title) LIKE '%' || :query || '%' " +
            "OR CASE WHEN EXISTS(SELECT 1 FROM song_metadata_override o WHERE o.songId = song.id AND o.artistNames IS NOT NULL) " +
            "THEN EXISTS(SELECT 1 FROM song_metadata_override o WHERE o.songId = song.id AND o.artistNames LIKE '%' || :query || '%') " +
            "ELSE EXISTS(SELECT 1 FROM song_artist_map m JOIN artist a ON a.id = m.artistId WHERE m.songId = song.id AND a.name LIKE '%' || :query || '%') END)"

    const val SEARCH_SONGS = "SELECT * FROM song WHERE " + MATCH + " LIMIT :previewSize"
    const val SEARCH_SONGS_COUNT = "SELECT COUNT(1) FROM song WHERE " + MATCH
    const val MOVE_LEGACY_TITLES =
        "INSERT OR IGNORE INTO song_metadata_override (songId, title, artistNames, updatedAt) " +
            "SELECT id, title, NULL, 0 FROM song WHERE titleOverride = 1"
    const val CLEAR_LEGACY_TITLE_FLAG = "UPDATE song SET titleOverride = 0 WHERE titleOverride = 1"
}
