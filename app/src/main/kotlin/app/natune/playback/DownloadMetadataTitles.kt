package app.natune.playback

import androidx.media3.exoplayer.offline.Download
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import app.natune.db.MusicDatabase
import app.natune.songmetadata.observeSongMetadataOverrideMap

/**
 * The user's title edits layered onto download notifications. Request data stays canonical, so a later
 * reset also restores notification labels; collection and one-off downloads share this projection,
 * independently of MusicService.
 */
internal class DownloadMetadataTitles(database: MusicDatabase, scope: CoroutineScope) {
    private val overrides =
        database.observeSongMetadataOverrideMap().stateIn(scope, SharingStarted.Eagerly, emptyMap())

    fun title(download: Download): String =
        overrides.value[download.request.id]?.title
            ?: download.request.data.toString(Charsets.UTF_8).ifBlank { download.request.id }
}
