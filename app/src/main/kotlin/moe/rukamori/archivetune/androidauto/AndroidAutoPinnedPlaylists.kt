package moe.rukamori.archivetune.androidauto

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.rukamori.archivetune.playback.MusicService

enum class AndroidAutoPinnedPlaylistKind { LOCAL, ONLINE }

/**
 * One driving-playlist pin: a local library playlist or a YouTube (online) playlist, kept in the
 * order the user pinned them.
 */
@Serializable
data class AndroidAutoPinnedPlaylist(
    val kind: AndroidAutoPinnedPlaylistKind,
    val id: String,
)

object AndroidAutoPinnedPlaylists {
    const val MAX_PINNED_PLAYLISTS = 6

    private const val KIND_SEGMENT_LOCAL = "local"
    private const val KIND_SEGMENT_ONLINE = "online"

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(entries: List<AndroidAutoPinnedPlaylist>): String = json.encodeToString(entries)

    fun decode(raw: String?): List<AndroidAutoPinnedPlaylist> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<AndroidAutoPinnedPlaylist>>(raw) }
            .getOrDefault(emptyList())
            .filter { it.id.isNotBlank() }
    }

    /**
     * Adds [entry] at the end when absent, removes it when present. New pins past
     * [MAX_PINNED_PLAYLISTS] are dropped instead of evicting earlier picks.
     */
    fun toggle(
        entries: List<AndroidAutoPinnedPlaylist>,
        entry: AndroidAutoPinnedPlaylist,
        maxPinned: Int = MAX_PINNED_PLAYLISTS,
    ): List<AndroidAutoPinnedPlaylist> =
        if (entry in entries) {
            entries - entry
        } else {
            (entries + entry).take(maxPinned)
        }

    fun isPinned(
        entries: List<AndroidAutoPinnedPlaylist>,
        entry: AndroidAutoPinnedPlaylist,
    ): Boolean = entry in entries

    fun mediaIdFor(entry: AndroidAutoPinnedPlaylist): String =
        "${MusicService.PINNED_PLAYLISTS}/${segmentForKind(entry.kind)}/${entry.id}"

    data class PinnedMediaRef(
        val kind: AndroidAutoPinnedPlaylistKind,
        val playlistId: String,
        val songMediaId: String?,
    )

    /**
     * Parses `pinned_playlists/<kind>/<playlistId>[/<songMediaId>]`. Song ids may themselves
     * contain `/`, so everything after the playlist id is kept as one segment.
     */
    fun parseMediaId(mediaId: String): PinnedMediaRef? {
        if (!mediaId.startsWith("${MusicService.PINNED_PLAYLISTS}/")) return null
        val rest = mediaId.removePrefix("${MusicService.PINNED_PLAYLISTS}/")
        val kind = kindForSegment(rest.substringBefore('/')) ?: return null
        val remainder = rest.substringAfter('/', "")
        val playlistId = remainder.substringBefore('/')
        if (playlistId.isBlank()) return null
        val songMediaId = remainder.substringAfter('/', "").takeIf { it.isNotBlank() }
        return PinnedMediaRef(kind, playlistId, songMediaId)
    }

    fun segmentForKind(kind: AndroidAutoPinnedPlaylistKind): String =
        when (kind) {
            AndroidAutoPinnedPlaylistKind.LOCAL -> KIND_SEGMENT_LOCAL
            AndroidAutoPinnedPlaylistKind.ONLINE -> KIND_SEGMENT_ONLINE
        }

    private fun kindForSegment(segment: String): AndroidAutoPinnedPlaylistKind? =
        when (segment) {
            KIND_SEGMENT_LOCAL -> AndroidAutoPinnedPlaylistKind.LOCAL
            KIND_SEGMENT_ONLINE -> AndroidAutoPinnedPlaylistKind.ONLINE
            else -> null
        }
}
