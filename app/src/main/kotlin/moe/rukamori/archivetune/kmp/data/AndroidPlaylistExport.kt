/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.kmp.data

import android.content.Context
import android.net.Uri
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.shared.domain.playlistexport.PlaylistExportCandidate
import moe.rukamori.archivetune.shared.domain.playlistexport.PlaylistExportDestination
import moe.rukamori.archivetune.shared.domain.playlistexport.PlaylistExportSource
import moe.rukamori.archivetune.shared.domain.playlistexport.PlaylistExportTrack
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentRef

/**
 * The Android export source over the retained Room playlists. Every playlist the library lists is a candidate, as in
 * the shipped picker, and candidates are reported newest first. A song's artist text joins all of its artists with
 * "; ", and its album falls back to the stored album name, which is the shipped column rule.
 */
class AndroidPlaylistExportSource(private val database: MusicDatabase) : PlaylistExportSource {
    override fun observeCandidates(): Flow<List<PlaylistExportCandidate>> =
        database.playlistsByCreateDateAsc()
            .map { playlists ->
                playlists.asReversed().map { playlist ->
                    PlaylistExportCandidate(
                        ref = ContentRef(LOCAL_PROVIDER, ContentKind.PLAYLIST, playlist.id),
                        name = playlist.playlist.name,
                        songCount = playlist.songCount,
                    )
                }
            }
            .flowOn(Dispatchers.IO)

    override suspend fun loadTracks(ref: ContentRef): List<PlaylistExportTrack> =
        withContext(Dispatchers.IO) {
            require(ref.kind == ContentKind.PLAYLIST) { "Only playlists export" }
            database.getPlaylistSongs(ref.id).map { playlistSong ->
                val song = playlistSong.song
                PlaylistExportTrack(
                    title = song.song.title,
                    artist = song.artists.joinToString(separator = "; ") { artist -> artist.name },
                    album = song.album?.title ?: song.song.albumName.orEmpty(),
                    mediaId = song.song.id,
                    // The stored length is -1 when unknown, and an unknown length is never written as a number.
                    durationSeconds = song.song.duration.takeIf { seconds -> seconds >= 0 },
                )
            }
        }

    override suspend fun loadTags(ref: ContentRef): List<String> =
        withContext(Dispatchers.IO) {
            require(ref.kind == ContentKind.PLAYLIST) { "Only playlists export" }
            database.playlistTags(ref.id).first().map { tag -> tag.name }
        }

    private companion object {
        const val LOCAL_PROVIDER = "local"
    }
}

/**
 * The document the user picked, written through the content resolver in the shipped truncate mode ("wt"). A stream the
 * provider refuses is a failed write, so the export never reports a document that was not written.
 */
class AndroidDocumentExportDestination(
    private val context: Context,
    private val uri: Uri,
) : PlaylistExportDestination {
    override suspend fun write(bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            val output = context.contentResolver.openOutputStream(uri, "wt")
                ?: throw IOException("Unable to open CSV destination")
            output.use { stream -> stream.write(bytes) }
        }
    }
}
