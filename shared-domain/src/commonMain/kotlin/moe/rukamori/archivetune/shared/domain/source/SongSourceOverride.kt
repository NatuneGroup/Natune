/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.source

/**
 * Codec for the per-song "play from" pins, stored as `songId=SOURCE` entries joined by `;` in one
 * preference string so backups carry it. A YOUTUBE pin means "always use YouTube for this song". An
 * entry that is malformed or names an unknown source is skipped, and the last valid pin for a song wins.
 */
object SongSourceOverride {
    fun parse(raw: String?): Map<String, AudioSourceType> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, AudioSourceType>()
        raw.split(';').forEach { entry ->
            val idx = entry.indexOf('=')
            if (idx <= 0) return@forEach
            val id = entry.substring(0, idx).trim()
            val source = parseAudioSourceType(entry.substring(idx + 1))
            if (id.isNotEmpty() && source != null) out[id] = source
        }
        return out
    }

    fun serialize(map: Map<String, AudioSourceType>): String =
        map.entries.joinToString(";") { "${it.key}=${it.value.name}" }

    fun get(raw: String?, songId: String): AudioSourceType? = parse(raw)[songId]

    /** The updated raw value with [songId] pinned to [source], or cleared when [source] is null. */
    fun withOverride(raw: String?, songId: String, source: AudioSourceType?): String {
        val map = LinkedHashMap(parse(raw))
        if (source == null) map.remove(songId) else map[songId] = source
        return serialize(map)
    }
}

/**
 * Codec for per-song Qobuz track-id pins. It shares the `songId=value;` shape with [SongSourceOverride],
 * but stores the id as text.
 */
object SongSourceQobuzTrackId {
    fun parse(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        raw.split(';').forEach { entry ->
            val idx = entry.indexOf('=')
            if (idx <= 0) return@forEach
            val id = entry.substring(0, idx).trim()
            val trackId = entry.substring(idx + 1).trim()
            if (id.isNotEmpty() && trackId.isNotEmpty()) out[id] = trackId
        }
        return out
    }

    fun serialize(map: Map<String, String>): String =
        map.entries.joinToString(";") { "${it.key}=${it.value}" }

    fun get(raw: String?, songId: String): String? = parse(raw)[songId]

    /** The updated raw value with [songId] set to [trackId], or cleared when [trackId] is null. */
    fun withOverride(raw: String?, songId: String, trackId: String?): String {
        val map = LinkedHashMap(parse(raw))
        if (trackId == null) map.remove(songId) else map[songId] = trackId
        return serialize(map)
    }
}

/** Codec for per-song Qobuz-backup video-id pins. It reuses the Qobuz track-id codec. */
object SongSourceQobuzBackupVideoId {
    fun parse(raw: String?): Map<String, String> = SongSourceQobuzTrackId.parse(raw)

    fun serialize(map: Map<String, String>): String = SongSourceQobuzTrackId.serialize(map)

    fun get(raw: String?, songId: String): String? = SongSourceQobuzTrackId.get(raw, songId)

    /** The updated raw value with [songId] set to [videoId], or cleared when [videoId] is null. */
    fun withOverride(raw: String?, songId: String, videoId: String?): String =
        SongSourceQobuzTrackId.withOverride(raw, songId, videoId)
}
