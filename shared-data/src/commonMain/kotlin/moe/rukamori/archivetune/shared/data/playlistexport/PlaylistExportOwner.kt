package moe.rukamori.archivetune.shared.data.playlistexport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import moe.rukamori.archivetune.shared.data.persistence.PlaylistExchange
import moe.rukamori.archivetune.shared.data.persistence.PlaylistExchangeTrack
import moe.rukamori.archivetune.shared.domain.playlistexport.ExportablePlaylist
import moe.rukamori.archivetune.shared.domain.playlistexport.PlaylistExportDestination
import moe.rukamori.archivetune.shared.domain.playlistexport.PlaylistExportException
import moe.rukamori.archivetune.shared.domain.playlistexport.PlaylistExportNaming
import moe.rukamori.archivetune.shared.domain.playlistexport.PlaylistExportSource
import moe.rukamori.archivetune.shared.domain.playlistexport.PlaylistExportTrack
import moe.rukamori.archivetune.shared.domain.playlistexport.PlaylistTrackLocations
import moe.rukamori.archivetune.shared.domain.repository.ContentRef

/**
 * The nonempty-playlist exports that the export picker binds to. CSV is the shipped writer, [PlaylistExchange.csv]: a
 * UTF-8 byte-order mark, CRLF rows, quoted fields and the spreadsheet formula guard. JSON is the version-1
 * `archivetune-playlist` document from [PlaylistExchange.json]. The owner decides only which playlists are offered and
 * which rows are written.
 *
 * A playlist with no songs throws before anything is written. A destination failure propagates unchanged, so the
 * caller never reports success for a document that was not written.
 */
class PlaylistExportOwner(private val source: PlaylistExportSource) {
    fun observeExportable(): Flow<List<ExportablePlaylist>> =
        source.observeCandidates()
            .map { candidates -> PlaylistExportNaming.exportable(candidates) }
            .flowOn(Dispatchers.Default)

    suspend fun exportCsv(ref: ContentRef, destination: PlaylistExportDestination) {
        val tracks = nonEmptyTracks(ref)
        val csv = PlaylistExchange.csv(tracks.map { track -> track.toExchangeTrack() })
        destination.write(csv.encodeToByteArray())
    }

    /**
     * The version-1 `archivetune-playlist` JSON document: the playlist [title], its tag names and every track with its
     * duration when known. Track URIs stay empty, because no location policy exists yet and the media ID is the stable
     * key that an import resolves. [title] is the name the picker showed, so the document keeps it verbatim.
     */
    suspend fun exportJson(ref: ContentRef, title: String, destination: PlaylistExportDestination) {
        val tracks = nonEmptyTracks(ref)
        val json = PlaylistExchange.json(
            title = title,
            tracks = tracks.map { track -> track.toExchangeTrack() },
            tags = source.loadTags(ref).toSet(),
        )
        destination.write(json.encodeToByteArray())
    }

    /**
     * The M3U document in playlist order. Every track needs a location from [locations] before anything is written: a
     * missing or unusable one throws [PlaylistExportException], so the file is never half-written and no row is lost.
     * Durations are written as `#EXTINF` values, and the unknown ones as -1.
     */
    suspend fun exportM3u(ref: ContentRef, destination: PlaylistExportDestination, locations: PlaylistTrackLocations) {
        val tracks = nonEmptyTracks(ref)
        val entries = ArrayList<PlaylistExchangeTrack>(tracks.size)
        for (track in tracks) {
            val location = locations.locate(track)
            if (location == null || !isUsableM3uLocation(location)) {
                throw PlaylistExportException("No playable location for ${track.title}")
            }
            entries += track.toExchangeTrack(uri = location)
        }
        destination.write(PlaylistExchange.m3u(entries).encodeToByteArray())
    }

    private suspend fun nonEmptyTracks(ref: ContentRef): List<PlaylistExportTrack> {
        val tracks = source.loadTracks(ref)
        if (tracks.isEmpty()) throw PlaylistExportException("Playlist has no songs")
        return tracks
    }

    /** The same rule [PlaylistExchange.m3u] enforces, checked first so the refusal is an export failure. */
    private fun isUsableM3uLocation(uri: String): Boolean =
        uri.isNotBlank() && !uri.startsWith('#') && uri.none { it == '\r' || it == '\n' || it == '\u0000' }

    private fun PlaylistExportTrack.toExchangeTrack(uri: String = ""): PlaylistExchangeTrack =
        PlaylistExchangeTrack(title, artist, album, mediaId, uri = uri, durationSeconds = durationSeconds)
}
