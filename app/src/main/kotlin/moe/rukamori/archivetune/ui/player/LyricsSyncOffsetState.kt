package moe.rukamori.archivetune.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.flowOf
import moe.rukamori.archivetune.LocalDatabase
import moe.rukamori.archivetune.constants.GlobalLyricsOffsetMsKey
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.utils.rememberPreference

/**
 * The offset a lyrics surface applies: the song's persisted correction plus the global preference.
 * The returned setter commits the effective shift back minus the global share, so a global offset
 * is never double-counted into the per-song row.
 */
@Composable
internal fun rememberLyricsSyncOffset(mediaMetadata: MediaMetadata?): Pair<Int, (Int) -> Unit> {
    val songId = mediaMetadata?.id
    val database = LocalDatabase.current
    val storedOffset by remember(songId) {
        songId?.let(database::lyricsOffset) ?: flowOf(null)
    }.collectAsStateWithLifecycle(initialValue = null)
    val (globalOffsetMs) = rememberPreference(GlobalLyricsOffsetMsKey, 0)
    val onOffsetChange =
        remember(songId, globalOffsetMs, mediaMetadata) {
            { newOffset: Int ->
                songId?.let { id ->
                    database.query {
                        mediaMetadata?.let { insert(it.toSongEntity()) }
                        setLyricsOffset(id, newOffset - globalOffsetMs)
                    }
                }
                Unit
            }
        }
    return (storedOffset ?: 0) + globalOffsetMs to onOffsetChange
}
